package org.nextcolor.dashcamviewer.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A13-only client. No public arbitrary endpoint interface exists. */
class A13ProtocolClient(
    private val transport: CameraTransport,
    private val clock: Clock = Clock(System::currentTimeMillis),
    private val session: () -> CameraSessionState?,
    private val liveSource: LiveVideoSource? = null,
    private val onSessionInvalidated: () -> Unit = {},
) : A13Client {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val issuedPaths = linkedMapOf<String, Pair<MediaFolder, Int>>()
    private val prepared = mutableMapOf<String, PreparedAction>()
    private val used = mutableSetOf<String>()
    private val mutex = Mutex()
    /** Excludes camera writes from an in-flight file transfer and vice versa. */
    private val mediaMutationMutex = Mutex()
    private var mutationAttempted = false
    private var identityCache: CameraIdentity? = null
    private var snapshotCache: CameraSnapshot? = null
    private var liveEntered = false
    private val liveCollecting = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun readSnapshot(): CameraSnapshot = io {
        val attrs = infoObject("getdeviceattr")
        val product = infoObject("getproductinfo")
        val company = product.optionalString("company")
        val sp = product.optionalString("sp")
        val identity = CameraIdentity(attrs.requiredString("uuid"),
            listOfNotNull(company, sp).firstOrNull { it.equals("HUAXIN", true) } ?: company ?: sp ?: "",
            product.optionalString("soc") ?: "", attrs.optionalString("softver") ?: "", attrs.optionalString("hwver") ?: "")
        if (!isSupportedA13(identity)) throw CameraReadException.WrongCamera
        val options = infoArray("getparamitems", "param" to "all")
        val values = infoArray("getparamvalue", "param" to "all")
        val current = values.associate { it.jsonObject.requiredString("name") to it.jsonObject.requiredInt("value") }
        val descriptors = options.mapNotNull { element ->
            val option = element.jsonObject
            val key = option.optionalString("name") ?: return@mapNotNull null
            val labels = option["items"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }.orEmpty()
            val indices = option["index"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: return@mapNotNull null }.orEmpty()
            if (labels.size != indices.size) return@mapNotNull null
            SettingDescriptor(key, labels, indices, current[key])
        }
        val sd = safeInfoObject("getsdinfo")
        val battery = safeInfoObject("getbatteryinfo")
        val lock = safeInfoObject("getlockvideostatus")
        val duration = safeInfoObject("getrecduration")
        val cap = safeInfoObject("capability")?.optionalString("value")
        val snapshot = CameraSnapshot(identity, cap, descriptors, current, current["rec"]?.let { it == 1 },
            sd?.entries?.associate { it.key to it.value.toString().trim('"') }.orEmpty() +
                duration?.entries?.associate { "duration_${it.key}" to it.value.toString().trim('"') }.orEmpty(),
            battery?.entries?.associate { it.key to it.value.toString().trim('"') }?.toString(),
            lock?.optionalInt("status")?.let { it != 0 })
        identityCache = identity
        snapshotCache = snapshot
        snapshot
    }

    override suspend fun listFiles(folder: MediaFolder, start: Int, limit: Int): MediaPage = io {
        require(start >= 0 && limit in 1..50) { "Page range must be nonnegative and at most 50." }
        val identity = identityCache ?: readSnapshot().identity
        val info = checkedInfo(request("getfilelist", "folder" to folder.name.lowercase(), "start" to "$start", "end" to "${start + limit - 1}"))
        val groups = when (info) {
            is JsonArray -> info
            is JsonObject -> JsonArray(listOf(info))
            else -> throw CameraReadException.MalformedResponse()
        }
        val files = groups.flatMap { it.jsonObject["files"]?.jsonArray ?: JsonArray(emptyList()) }
        val items = files.map { element ->
            val row = element.jsonObject
            val path = row.optionalString("name") ?: row.optionalString("file") ?: throw CameraReadException.MalformedResponse()
            validateMediaPath(path)
            RemoteMedia(identity, path, folder, row.entries.associate { it.key to it.value.toString().trim('"') }, row.optionalLong("size"))
        }
        synchronized(issuedPaths) { items.forEach { issuedPaths[it.exactCameraPath] = folder to start } }
        MediaPage(items, start, limit)
    }

    override fun download(file: RemoteMedia, sink: TransferSink): Flow<TransferEvent> = channelFlow {
        if (!mediaMutationMutex.tryLock()) throw IllegalStateException("A camera mutation or media transfer is already active.")
        var transferred = 0L
        var expected: Long? = null
        try {
            validateIssued(file)
            withContext(Dispatchers.IO) {
                transport.stream(file.exactCameraPath, sink) { bytes, total ->
                    transferred = bytes; expected = total; trySend(TransferEvent(bytes, total))
                }
            }
            sink.commit()
            trySend(TransferEvent(transferred, expected, complete = true))
        } catch (t: Throwable) {
            withContext(NonCancellable) { sink.rollback() }
            if (t is kotlinx.coroutines.CancellationException) throw t
            throw mapFailure(t)
        } finally {
            mediaMutationMutex.unlock()
        }
    }

    override fun liveVideo(): Flow<VideoEvent> = channelFlow {
        check(liveEntered) { "Use the explicit Start Live control first." }
        val source = liveSource ?: throw IllegalStateException("This client has no network-bound RTSP source.")
        check(liveCollecting.compareAndSet(false, true)) { "Live view is already active." }
        try { source.collect { trySend(it) } }
        finally {
            withContext(NonCancellable) {
                try {
                    if (liveEntered) {
                        val stopAction = prepare(CameraCommand.StopLive)
                        execute(stopAction, ActionConfirmation(acknowledged = true))
                    } else source.stop()
                } catch (_: Throwable) { runCatching { source.stop() } }
                liveCollecting.set(false)
            }
        }
    }

    override suspend fun prepare(command: CameraCommand): PreparedAction = mutex.withLock {
        val state = session() ?: throw CameraReadException.DisconnectedNetwork
        val identity = identityCache ?: readSnapshot().identity
        require(isSupportedA13(identity) && identity.uuid == state.identity.uuid) { "Unsupported or mismatched camera." }
        require(!state.downloadActive) { "Mutations are disabled during downloads." }
        when (command) {
            is CameraCommand.SetSetting -> {
                require(command.key in WRITABLE_SETTINGS) { "Setting is read-only or unsupported." }
                val fresh = readSnapshot()
                val descriptor = fresh.advertisedSettings.firstOrNull { it.key == command.key }
                    ?: throw IllegalStateException("Camera does not advertise ${command.key}.")
                require(command.value in descriptor.optionValues) { "Requested value is not advertised." }
                require(descriptor.rawCurrentValue == null || descriptor.rawCurrentValue in descriptor.optionValues) {
                    "Current value: ${descriptor.rawCurrentValue}; camera options inconsistent. Writes disabled."
                }
                require(fresh.currentSettingValues[command.key] != command.value) { "Value already desired." }
                if (command.key == "rec") {
                    require(command.value in 0..1)
                    if (command.value == 0) require(state.recording) { "Recording is already off." }
                    else require(!state.recording) { "Recording is already on." }
                }
            }
            CameraCommand.Snapshot -> Unit
            CameraCommand.LockCurrentRecording -> require(state.recording) { "There is no active recording to protect as the current clip." }
            CameraCommand.StartLive -> {
                require(state.recording) { "Recording must be on before live view." }
                require(!state.liveActive && !state.remotePlaybackActive && !state.downloadActive) { "Stop other media activity first." }
            }
            CameraCommand.StopLive -> require(state.liveActive || liveEntered) { "Live view is not active." }
            CameraCommand.StartRecording -> require(!state.recording) { "Recording is already on." }
            CameraCommand.StopRecording -> require(state.recording) { "Recording is already off." }
            is CameraCommand.SwitchCamera -> {
                val option = readSnapshot().advertisedSettings.firstOrNull { it.key == "switchcam" }
                    ?: throw IllegalStateException("Camera switching is not advertised.")
                require(command.value in option.optionValues)
            }
            is CameraCommand.SetParking -> {
                val fresh = readSnapshot()
                val option = fresh.advertisedSettings.firstOrNull { it.key == "parking_monitor" }
                require(option != null && "parking_monitor" in fresh.currentSettingValues) {
                    "Not exposed by the camera in its current state. Use the camera’s physical menu."
                }
                require((if (command.open) 1 else 0) in option.optionValues)
            }
            is CameraCommand.SetClock -> {
                val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                require(LocalDateTime.parse(command.date, fmt).format(fmt) == command.date)
                ZoneId.of(command.zoneId)
            }
            is CameraCommand.SetWifiName -> validateWifiText(command.ssid, false)
            is CameraCommand.SetWifiPassword -> { validateWifiText(command.password, true); require(command.password == command.confirmation) { "Passwords do not match." } }
            CameraCommand.RestartWifi -> Unit
            is CameraCommand.DeleteOneFile -> { validateIssued(command.media); require(command.typedFilename == command.media.exactCameraPath.substringAfterLast('/')) }
            is CameraCommand.FormatCard -> { require(command.typedPhrase == "ERASE ALL RECORDINGS"); requireMaintenanceReady(state) }
            is CameraCommand.FactoryReset -> { require(command.typedPhrase == "RESET CAMERA"); requireMaintenanceReady(state) }
        }
        val requirement = when (command) {
            is CameraCommand.SetSetting -> when {
                command.key == "switchcam" -> ConfirmationKind.SWITCH_CAMERA
                command.key == "rec" && command.value == 0 -> ConfirmationKind.STOP_RECORDING
                else -> ConfirmationKind.TAP
            }
            CameraCommand.Snapshot, CameraCommand.StartRecording -> ConfirmationKind.TAP
            CameraCommand.StopRecording -> ConfirmationKind.STOP_RECORDING
            CameraCommand.LockCurrentRecording -> ConfirmationKind.LOCK_CURRENT
            CameraCommand.StartLive, CameraCommand.StopLive -> ConfirmationKind.TAP
            is CameraCommand.SwitchCamera -> ConfirmationKind.SWITCH_CAMERA
            is CameraCommand.SetParking -> ConfirmationKind.PARKING
            is CameraCommand.SetClock, is CameraCommand.SetWifiName, is CameraCommand.SetWifiPassword, CameraCommand.RestartWifi -> ConfirmationKind.EXPERIMENTAL
            is CameraCommand.DeleteOneFile -> ConfirmationKind.EXPERIMENTAL_TYPED_FILENAME
            is CameraCommand.FormatCard -> ConfirmationKind.ERASE_ALL
            is CameraCommand.FactoryReset -> ConfirmationKind.RESET_CAMERA
        }
        val action = PreparedAction(state.identity.uuid, state.generation, command, currentValue(command), requirement,
            clock.nowMillis() + ACTION_TTL_MS, UUID.randomUUID().toString())
        prepared[action.nonce] = action
        action
    }

    override suspend fun execute(action: PreparedAction, confirmation: ActionConfirmation): CommandOutcome {
        if (!mediaMutationMutex.tryLock()) {
            mutex.withLock {
                if (prepared[action.nonce] === action) {
                    prepared.remove(action.nonce)
                    used += action.nonce
                }
            }
            return CommandOutcome.Rejected
        }
        return try { mutex.withLock {
        if (prepared[action.nonce] !== action || action.nonce in used) return@withLock CommandOutcome.Rejected
        if (clock.nowMillis() > action.expiresAtEpochMillis) {
            prepared.remove(action.nonce); used += action.nonce
            return@withLock CommandOutcome.Rejected
        }
        used += action.nonce
        prepared.remove(action.nonce)
        val state = session() ?: return@withLock CommandOutcome.Rejected
        if (state.identity.uuid != action.cameraUuid || state.generation != action.connectionGeneration || !isSupportedA13(state.identity)) return@withLock CommandOutcome.Rejected
        if (!confirmation.acknowledged || !validConfirmation(action, confirmation)) return@withLock CommandOutcome.Rejected
        val stoppingLive = action.command == CameraCommand.StopLive
        if (state.downloadActive || (action.command !in setOf(CameraCommand.Snapshot, CameraCommand.LockCurrentRecording) && (state.remotePlaybackActive || (state.liveActive && !stoppingLive)))) return@withLock CommandOutcome.Rejected
        mutationAttempted = false
        try {
            when (val cmd = action.command) {
                is CameraCommand.SetSetting -> {
                    val fresh = readSnapshot()
                    val descriptor = fresh.advertisedSettings.firstOrNull { it.key == cmd.key } ?: return@withLock CommandOutcome.Rejected
                    if (cmd.value !in descriptor.optionValues || fresh.currentSettingValues[cmd.key]?.toString() != action.relevantCurrentValue) return@withLock CommandOutcome.Rejected
                    if (fresh.currentSettingValues[cmd.key] == cmd.value) return@withLock CommandOutcome.AlreadyDesired
                    write("setparamvalue", "param" to cmd.key, "value" to cmd.value.toString())
                    verifyValue(cmd.key, cmd.value)
                }
                CameraCommand.Snapshot -> { write("snapshot"); CommandOutcome.AcceptedUnverified }
                CameraCommand.StartRecording -> {
                    val current = readSnapshot().currentSettingValues["rec"]
                    if (current == 1) CommandOutcome.AlreadyDesired else if (current != 0) CommandOutcome.Rejected else writeAndVerify("rec", 1)
                }
                CameraCommand.StopRecording -> {
                    val current = readSnapshot().currentSettingValues["rec"]
                    if (current == 0) CommandOutcome.AlreadyDesired else if (current != 1) CommandOutcome.Rejected else writeAndVerify("rec", 0)
                }
                CameraCommand.LockCurrentRecording -> {
                    if (readSnapshot().recording != true) return@withLock CommandOutcome.Rejected
                    write("lockvideo")
                    if (checkedInfo(request("getlockvideostatus")).jsonObjectOrMalformed().optionalInt("status") == 1) CommandOutcome.Confirmed else CommandOutcome.Unconfirmed
                }
                CameraCommand.StartLive -> {
                    if (!state.recording || state.downloadActive || state.remotePlaybackActive) return@withLock CommandOutcome.Rejected
                    if (readSnapshot().recording != true) return@withLock CommandOutcome.Rejected
                    write("enterrecorder")
                    liveEntered = true
                    CommandOutcome.Confirmed
                }
                CameraCommand.StopLive -> try { stopLiveInternal(liveSource, bestEffort = false); CommandOutcome.Confirmed } catch (_: Throwable) { CommandOutcome.Unconfirmed }
                is CameraCommand.SwitchCamera -> {
                    val fresh = readSnapshot(); val descriptor = fresh.advertisedSettings.firstOrNull { it.key == "switchcam" }
                    if (descriptor == null || cmd.value !in descriptor.optionValues || fresh.currentSettingValues["switchcam"]?.toString() != action.relevantCurrentValue) return@withLock CommandOutcome.Rejected
                    if (fresh.currentSettingValues["switchcam"] == cmd.value) CommandOutcome.AlreadyDesired else writeAndVerify("switchcam", cmd.value)
                }
                is CameraCommand.SetParking -> {
                    val fresh = readSnapshot(); val descriptor = fresh.advertisedSettings.firstOrNull { it.key == "parking_monitor" }
                    val value = if (cmd.open) 1 else 0
                    if (descriptor == null || "parking_monitor" !in fresh.currentSettingValues || value !in descriptor.optionValues || fresh.currentSettingValues["parking_monitor"]?.toString() != action.relevantCurrentValue) return@withLock CommandOutcome.Rejected
                    if (fresh.currentSettingValues["parking_monitor"] == value) CommandOutcome.AlreadyDesired else writeAndVerify("parking_monitor", value)
                }
                is CameraCommand.SetClock -> { write("setsystime", "date" to cmd.date); CommandOutcome.AcceptedUnverified }
                is CameraCommand.SetWifiName -> { write("setwifi", "wifissid" to cmd.ssid); CommandOutcome.AcceptedUnverified }
                is CameraCommand.SetWifiPassword -> { write("setwifi", "wifipwd" to cmd.password); CommandOutcome.AcceptedUnverified }
                CameraCommand.RestartWifi -> {
                    try { write("wifireboot"); CommandOutcome.AcceptedUnverified }
                    finally { clearSession() }
                }
                is CameraCommand.DeleteOneFile -> {
                    write("deletefile", "file" to cmd.media.exactCameraPath)
                    val (folder, start) = synchronized(issuedPaths) { issuedPaths[cmd.media.exactCameraPath] } ?: return@withLock CommandOutcome.Unconfirmed
                    val check = checkedInfo(request("getfilelist", "folder" to folder.name.lowercase(), "start" to "$start", "end" to "${start + 49}"))
                    if (!check.toString().contains(cmd.media.exactCameraPath)) CommandOutcome.Confirmed else CommandOutcome.Unconfirmed
                }
                is CameraCommand.FormatCard -> {
                    try { write("sdformat"); CommandOutcome.AcceptedUnverified }
                    finally { clearSession() }
                }
                is CameraCommand.FactoryReset -> {
                    try { write("reset"); CommandOutcome.AcceptedUnverified }
                    finally { clearSession() }
                }
            }
        } catch (_: CameraReadException.UnsupportedResponse) { CommandOutcome.Rejected }
        catch (_: CameraCommandRejected) { CommandOutcome.Rejected }
        catch (_: Throwable) { if (mutationAttempted) CommandOutcome.Unconfirmed else CommandOutcome.Rejected }
        } } finally { mediaMutationMutex.unlock() }
    }

    private suspend fun writeAndVerify(key: String, value: Int): CommandOutcome {
        write("setparamvalue", "param" to key, "value" to value.toString())
        return verifyValue(key, value)
    }
    private suspend fun verifyValue(key: String, value: Int): CommandOutcome {
        val actual = checkedInfo(request("getparamvalue", "param" to key)).jsonObjectOrMalformed().optionalInt("value")
        return if (actual == value) CommandOutcome.Confirmed else CommandOutcome.Unconfirmed
    }
    private suspend fun write(endpoint: String, vararg query: Pair<String, String>) {
        mutationAttempted = true
        checkedInfo(request(endpoint, *query))
    }
    private suspend fun stopLiveInternal(source: LiveVideoSource?, bestEffort: Boolean) {
        if (!liveEntered) return
        liveEntered = false
        var failure: Throwable? = null
        try { source?.stop() } catch (t: Throwable) { failure = t }
        try { write("exitrecorder") } catch (t: Throwable) { if (failure == null) failure = t }
        if (!bestEffort && failure != null) throw failure
    }
    private suspend fun request(endpoint: String, vararg query: Pair<String, String>): JsonElement {
        require(endpoint in ALLOWED_ENDPOINTS) { "Unsupported camera operation" }
        val reply = try { transport.get("/app/$endpoint", query.toList()) } catch (t: Throwable) { throw mapFailure(t) }
        if (reply.status !in 200..299) throw CameraReadException.HttpFailure(reply.status)
        if (reply.body.size > MAX_CONTROL_BYTES) throw CameraReadException.MalformedResponse()
        return try { json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)) } catch (t: Throwable) { throw CameraReadException.MalformedResponse(t) }
    }
    private suspend fun infoObject(endpoint: String) = checkedInfo(request(endpoint)).jsonObjectOrMalformed()
    private suspend fun infoArray(endpoint: String, vararg query: Pair<String, String>) = checkedInfo(request(endpoint, *query)).jsonArrayOrMalformed()
    private suspend fun safeInfoObject(endpoint: String): JsonObject? = try { infoObject(endpoint) } catch (_: CameraReadException.HttpFailure) { null }
    private fun checkedInfo(root: JsonElement): JsonElement {
        val obj = root as? JsonObject ?: throw CameraReadException.MalformedResponse()
        val result = obj["result"]?.jsonPrimitive?.intOrNull ?: throw CameraReadException.MalformedResponse()
        if (result == 98 && obj.keys == setOf("result")) throw CameraReadException.UnsupportedResponse
        if (result == 98) throw CameraReadException.MalformedResponse()
        if (result != 0) throw CameraCommandRejected
        return obj["info"] ?: JsonNull
    }
    private fun validateIssued(file: RemoteMedia) {
        if (!isSupportedA13(file.cameraIdentity) || identityCache?.uuid != file.cameraIdentity.uuid) throw CameraReadException.WrongCamera
        validateMediaPath(file.exactCameraPath)
        require(synchronized(issuedPaths) { issuedPaths[file.exactCameraPath]?.first == file.folder }) { "Media must come from this session’s listing." }
    }
    private fun validConfirmation(action: PreparedAction, c: ActionConfirmation): Boolean = when (action.requiredConfirmation) {
        ConfirmationKind.TAP -> true
        ConfirmationKind.STOP_RECORDING -> c.exactDisplayedValue == "Stop recording"
        ConfirmationKind.LOCK_CURRENT -> c.exactDisplayedValue == "Protect current recording"
        ConfirmationKind.SWITCH_CAMERA -> c.exactDisplayedValue == if ((action.command as? CameraCommand.SwitchCamera)?.value == 1 || (action.command as? CameraCommand.SetSetting)?.value == 1) "Rear" else "Front"
        ConfirmationKind.PARKING -> c.exactDisplayedValue == if ((action.command as? CameraCommand.SetParking)?.open == true) "Open" else "Closed"
        ConfirmationKind.EXPERIMENTAL -> c.exactDisplayedValue == when (val command = action.command) {
            is CameraCommand.SetClock -> "${command.date} ${command.zoneId}"
            is CameraCommand.SetWifiName -> command.ssid
            is CameraCommand.SetWifiPassword -> "Set the camera Wi-Fi password"
            CameraCommand.RestartWifi -> "Reconnect manually after the camera Wi-Fi restarts"
            else -> null
        }
        ConfirmationKind.EXPERIMENTAL_TYPED_FILENAME -> c.phrase == (action.command as CameraCommand.DeleteOneFile).media.exactCameraPath.substringAfterLast('/')
        ConfirmationKind.ERASE_ALL -> c.phrase == "ERASE ALL RECORDINGS"
        ConfirmationKind.RESET_CAMERA -> c.phrase == "RESET CAMERA"
    }
    private fun currentValue(c: CameraCommand): String? = when (c) {
        is CameraCommand.SetSetting -> snapshotCache?.currentSettingValues?.get(c.key)?.toString()
        is CameraCommand.SwitchCamera -> snapshotCache?.currentSettingValues?.get("switchcam")?.toString()
        is CameraCommand.SetParking -> snapshotCache?.currentSettingValues?.get("parking_monitor")?.toString()
        CameraCommand.StartRecording, CameraCommand.StopRecording -> session()?.recording?.toString()
        else -> null
    }
    private fun requireMaintenanceReady(s: CameraSessionState) {
        require(!s.liveActive && !s.remotePlaybackActive && !s.downloadActive) { "Stop live view, remote playback, and downloads first." }
        require(!s.recording) { "Turn recording off manually and confirm it is off before maintenance." }
    }
    private fun clearSession() {
        identityCache = null; snapshotCache = null; synchronized(issuedPaths) { issuedPaths.clear() }
        onSessionInvalidated()
    }
    private fun validateWifiText(value: String, password: Boolean) {
        val min = if (password) 8 else 1; val max = if (password) 63 else 32
        require(value.length in min..max && value.matches(Regex("[A-Za-z0-9_.~-]+"))) { "Use $min–$max ASCII letters, digits, or _.~- only." }
    }
    private fun mapFailure(t: Throwable): Throwable = when (t) {
        is CameraReadException -> t
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> CameraReadException.Timeout
        else -> CameraReadException.DisconnectedNetwork
    }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val MAX_CONTROL_BYTES = 1024 * 1024
        private const val ACTION_TTL_MS = 60_000L
        val WRITABLE_SETTINGS = setOf("mic", "osd", "rec_resolution", "rec_split_duration", "key_tone", "gsr_sensitivity", "rec", "light_fre", "screen_standby", "switchcam")
        val ALLOWED_ENDPOINTS = setOf("getdeviceattr", "getproductinfo", "getsdinfo", "getbatteryinfo", "getrecduration", "getlockvideostatus", "getparamitems", "getparamvalue", "capability", "getfilelist", "setparamvalue", "snapshot", "lockvideo", "enterrecorder", "getmediainfo", "exitrecorder", "setsystime", "setwifi", "wifireboot", "deletefile", "sdformat", "reset")
    }
}

private data object CameraCommandRejected : Exception()

private fun JsonObject.requiredString(key: String) = optionalString(key) ?: throw CameraReadException.MalformedResponse()
private fun JsonObject.optionalString(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.optionalInt(key: String) = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.optionalLong(key: String) = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.requiredInt(key: String) = optionalInt(key) ?: throw CameraReadException.MalformedResponse()
private fun JsonElement.jsonObjectOrMalformed() = this as? JsonObject ?: throw CameraReadException.MalformedResponse()
private fun JsonElement.jsonArrayOrMalformed() = this as? JsonArray ?: throw CameraReadException.MalformedResponse()

fun isSupportedA13(identity: CameraIdentity): Boolean = identity.soc.equals("eeasytech", true) && identity.manufacturer.equals("HUAXIN", true) && identity.firmware.startsWith("DVR-A13-", true)

fun validateMediaPath(path: String) {
    require(path.startsWith("/mnt/card/") && path.length > "/mnt/card/".length)
    require('\\' !in path && path.none { it.code < 0x20 || it.code == 0x7f })
    require('?' !in path && '#' !in path && !path.contains("://"))
    require(path.removePrefix("/").split('/').none { it.isEmpty() || it == "." || it == ".." })
    require(!Regex("(?i)%2e|%2f|%5c").containsMatchIn(path))
    require(path.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "ts", "jpg"))
}
