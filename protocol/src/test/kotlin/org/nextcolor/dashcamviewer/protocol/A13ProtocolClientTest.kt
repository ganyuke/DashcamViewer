package org.nextcolor.dashcamviewer.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class A13ProtocolClientTest {
    private val fixture =
        javaClass.classLoader!!
            .getResourceAsStream("a13-snapshot-fixture.json")!!
            .bufferedReader()
            .use { it.readText() }
    private val parsed = kotlinx.serialization.json.Json.parseToJsonElement(fixture).jsonObject
    private val identity =
        CameraIdentity("fixture-a13", "HUAXIN", "eeasytech", "DVR-A13-20250808", "H30D-LMT-V1.1")

    @Test
    fun optionalUnquotedUnsupportedReplyDoesNotRejectA13Identity() = runBlocking {
        val transport =
            FixtureTransport(parsed).apply {
                overrideEndpoint = "capability"
                overrideBody = "{result: 98}"
            }
        val snapshot = makeClient(transport).readSnapshot()
        assertEquals("fixture-a13", snapshot.identity.uuid)
        assertNull(snapshot.rawCapability)
    }

    @Test
    fun ioFailureDoesNotClaimTheWifiNetworkDisconnected() = runBlocking {
        val failing =
            object : CameraTransport {
                override suspend fun get(
                    path: String,
                    query: List<Pair<String, String>>,
                ): HttpReply = throw java.net.ConnectException("Connection refused")

                override suspend fun stream(
                    path: String,
                    sink: TransferSink,
                    onBytes: (Long, Long?) -> Unit,
                ) = error("Not used")

                override fun openSocket(): CameraSocket = error("Not used")
            }
        val client = A13ProtocolClient(failing, session = { null })
        try {
            client.readSnapshot()
            fail("Expected a communication failure")
        } catch (error: CameraReadException.CommunicationFailure) {
            assertTrue(
                generateSequence(error as Throwable?) { it.cause }
                    .any { it is java.net.ConnectException }
            )
        }
    }

    @Test
    fun cancellingVerificationPropagatesCancellation() = runBlocking {
        val blocked =
            object : CameraTransport {
                override suspend fun get(
                    path: String,
                    query: List<Pair<String, String>>,
                ): HttpReply = kotlinx.coroutines.awaitCancellation()

                override suspend fun stream(
                    path: String,
                    sink: TransferSink,
                    onBytes: (Long, Long?) -> Unit,
                ) = error("Not used")

                override fun openSocket(): CameraSocket = error("Not used")
            }
        val client = A13ProtocolClient(blocked, session = { null })
        try {
            kotlinx.coroutines.withTimeout(50) { client.readSnapshot() }
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {}
    }

    @Test
    fun slowOptionalStatusDoesNotHoldVerificationOpen() = runBlocking {
        val fixture = FixtureTransport(parsed)
        val delayed =
            object : CameraTransport by fixture {
                override suspend fun get(
                    path: String,
                    query: List<Pair<String, String>>,
                ): HttpReply {
                    if (path.endsWith("/getbatteryinfo")) kotlinx.coroutines.awaitCancellation()
                    return fixture.get(path, query)
                }
            }
        val snapshot =
            kotlinx.coroutines.withTimeout(3_000) {
                A13ProtocolClient(delayed, session = { null }).readSnapshot()
            }
        assertEquals("fixture-a13", snapshot.identity.uuid)
        assertNull(snapshot.batteryValue)
    }

    @Test
    fun savedFixtureShowsTheCurrentResolutionMismatchAndNoParkingApi() = runBlocking {
        val transport = FixtureTransport(parsed)
        val client = makeClient(transport)
        val snapshot = client.readSnapshot()
        val resolution = snapshot.advertisedSettings.single { it.key == "rec_resolution" }
        assertEquals(2, resolution.rawCurrentValue)
        assertEquals(listOf(0, 1), resolution.optionValues)
        assertNull(snapshot.advertisedSettings.firstOrNull { it.key == "parking_monitor" })
        assertFalse("parking_monitor" in snapshot.currentSettingValues)
    }

    @Test
    fun resolutionMismatchAndAbsentParkingProhibitWrites() = runBlocking {
        val transport = FixtureTransport(parsed)
        val client = makeClient(transport)
        client.readSnapshot()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.prepare(CameraCommand.SetSetting("rec_resolution", 1)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.prepare(CameraCommand.SetParking(true)) }
        }
        assertEquals(0, transport.mutations)
    }

    @Test
    fun confirmationMissingExpiredWrongCameraAndWrongSessionSendNoWrite() = runBlocking {
        val transport = FixtureTransport(parsed)
        var now = 1_000L
        var session = CameraSessionState(identity, 7, true, false, false, false)
        val client = A13ProtocolClient(transport, Clock { now }, { session })
        client.readSnapshot()
        val stop = client.prepare(CameraCommand.StopRecording)
        assertEquals(CommandOutcome.Rejected, client.execute(stop, ActionConfirmation(true)))
        assertEquals(0, transport.mutations)

        val expired = client.prepare(CameraCommand.StopRecording)
        now += 60_001
        assertEquals(
            CommandOutcome.Rejected,
            client.execute(
                expired,
                ActionConfirmation(true, exactDisplayedValue = "Stop recording"),
            ),
        )
        assertEquals(0, transport.mutations)

        now += 1
        val stale = client.prepare(CameraCommand.StopRecording)
        session = session.copy(generation = 8)
        assertEquals(
            CommandOutcome.Rejected,
            client.execute(stale, ActionConfirmation(true, exactDisplayedValue = "Stop recording")),
        )
        assertEquals(0, transport.mutations)
    }

    @Test
    fun result98IsUnsupportedAndMalformedResponseIsTyped() {
        runBlocking {
            val unsupported =
                FixtureTransport(parsed).apply {
                    overrideEndpoint = "getproductinfo"
                    overrideBody = "{\"result\":98}"
                }
            assertThrows(CameraReadException.UnsupportedResponse::class.java) {
                runBlocking { makeClient(unsupported).readSnapshot() }
            }
            val malformed =
                FixtureTransport(parsed).apply {
                    overrideEndpoint = "getproductinfo"
                    overrideBody = "{bad"
                }
            assertThrows(CameraReadException.MalformedResponse::class.java) {
                runBlocking { makeClient(malformed).readSnapshot() }
            }
            val nonExactUnsupported =
                FixtureTransport(parsed).apply {
                    overrideEndpoint = "getproductinfo"
                    overrideBody = "{\"result\":98,\"info\":{}}"
                }
            assertThrows(CameraReadException.MalformedResponse::class.java) {
                runBlocking { makeClient(nonExactUnsupported).readSnapshot() }
            }
        }
    }

    @Test
    fun advertisedSettingUsesExactlyOneWriteAndActionIsSingleUse() = runBlocking {
        val transport = FixtureTransport(parsed)
        val client = makeClient(transport)
        client.readSnapshot()
        val action = client.prepare(CameraCommand.SetSetting("mic", 0))
        assertEquals(CommandOutcome.Confirmed, client.execute(action, ActionConfirmation(true)))
        assertEquals(1, transport.writes.size)
        assertEquals("/app/setparamvalue", transport.writes.single().first)
        assertEquals(listOf("param" to "mic", "value" to "0"), transport.writes.single().second)
        assertEquals(CommandOutcome.Rejected, client.execute(action, ActionConfirmation(true)))
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun failedDeleteReadbackIsUnconfirmedNotSuccess() = runBlocking {
        val transport = FixtureTransport(parsed)
        val client = makeClient(transport)
        val media = client.listFiles(MediaFolder.LOOP, 50).items.single()
        transport.failNextFileCheck = true
        val action = client.prepare(CameraCommand.DeleteOneFile(media, "clip.mp4"))
        val result =
            client.execute(
                action,
                ActionConfirmation(true, phrase = "clip.mp4", exactDisplayedValue = "clip.mp4"),
            )
        assertEquals(CommandOutcome.Unconfirmed, result)
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun mutationTimeoutIsUnconfirmedAndNeverRetried() = runBlocking {
        val transport = FixtureTransport(parsed).apply { timeoutNextMutation = true }
        val client = makeClient(transport)
        client.readSnapshot()
        val action = client.prepare(CameraCommand.SetSetting("mic", 0))
        assertEquals(CommandOutcome.Unconfirmed, client.execute(action, ActionConfirmation(true)))
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun mutationCannotOverlapAnActiveDownload() = runBlocking {
        val transport = FixtureTransport(parsed)
        val client = makeClient(transport)
        client.readSnapshot()
        val media = client.listFiles(MediaFolder.LOOP, 0).items.single()
        val action = client.prepare(CameraCommand.SetSetting("mic", 0))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.streamStarted = started
        transport.releaseStream = release
        val sink =
            object : TransferSink {
                override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

                override suspend fun commit() = Unit

                override suspend fun rollback() = Unit
            }
        val transfer = launch { client.download(media, sink).collect {} }
        started.await()
        assertEquals(CommandOutcome.Rejected, client.execute(action, ActionConfirmation(true)))
        assertTrue(transport.writes.isEmpty())
        release.complete(Unit)
        transfer.join()
    }

    @Test
    fun liveFlowCleanupRoutesExitThroughPreparedStopAction() = runBlocking {
        val transport = FixtureTransport(parsed)
        var session = CameraSessionState(identity, 7, true, false, false, false)
        var sourceStopped = false
        val source =
            object : LiveVideoSource {
                override suspend fun collect(emit: suspend (VideoEvent) -> Unit) {
                    emit(VideoEvent.Failure("fixture end"))
                }

                override suspend fun stop() {
                    sourceStopped = true
                }
            }
        val client = A13ProtocolClient(transport, session = { session }, liveSource = source)
        client.readSnapshot()
        val start = client.prepare(CameraCommand.StartLive)
        assertEquals(CommandOutcome.Confirmed, client.execute(start, ActionConfirmation(true)))
        session = session.copy(liveActive = true)
        client.liveVideo().collect {}
        assertTrue(sourceStopped)
        assertEquals(
            listOf("enterrecorder", "exitrecorder"),
            transport.writes.map { it.first.substringAfterLast('/') },
        )
    }

    @Test
    fun mediaPathMustBeAListedSafeCardFile() {
        listOf("/mnt/card/x.mp4", "/mnt/card/event/a.JPG", "/mnt/card/a.ts", "/mnt/card/a.mov")
            .forEach(::validateMediaPath)
        listOf(
                "/mnt/card/../private.mp4",
                "/mnt/card/a\\b.mp4",
                "/mnt/card/a.mp4?x=1",
                "/other/a.mp4",
                "/mnt/card/file.txt",
                "/mnt/card/%2e%2e/x.mp4#q",
            )
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { validateMediaPath(path) }
            }
    }

    @Test
    fun unsupportedCameraProfileIsRejected() {
        assertFalse(isSupportedA13(identity.copy(soc = "other")))
        assertFalse(isSupportedA13(identity.copy(manufacturer = "Other")))
        assertFalse(isSupportedA13(identity.copy(firmware = "DVR-A12-2025")))
        assertTrue(isSupportedA13(identity))
    }

    private fun makeClient(transport: FixtureTransport) =
        A13ProtocolClient(
            transport,
            session = { CameraSessionState(identity, 7, true, false, false, false) },
        )

    private class FixtureTransport(private val fixture: kotlinx.serialization.json.JsonObject) :
        CameraTransport {
        var mutations = 0
        var overrideEndpoint: String? = null
        var overrideBody: String? = null
        val writes = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        var failNextFileCheck = false
        var timeoutNextMutation = false
        var streamStarted: CompletableDeferred<Unit>? = null
        var releaseStream: CompletableDeferred<Unit>? = null
        private val settings =
            fixture
                .getValue("values")
                .jsonObject
                .getValue("info")
                .jsonArray
                .associate {
                    val row = it.jsonObject
                    row.getValue("name").jsonPrimitive.content to
                        row.getValue("value").jsonPrimitive.int
                }
                .toMutableMap()
        private var clipExists = true

        override suspend fun get(path: String, query: List<Pair<String, String>>): HttpReply {
            val endpoint = path.substringAfterLast('/')
            if (
                endpoint in
                    setOf(
                        "setparamvalue",
                        "snapshot",
                        "lockvideo",
                        "enterrecorder",
                        "exitrecorder",
                        "setsystime",
                        "setwifi",
                        "wifireboot",
                        "deletefile",
                        "sdformat",
                        "reset",
                    )
            )
                mutations++
            if (
                endpoint in
                    setOf(
                        "setparamvalue",
                        "snapshot",
                        "lockvideo",
                        "enterrecorder",
                        "exitrecorder",
                        "setsystime",
                        "setwifi",
                        "wifireboot",
                        "deletefile",
                        "sdformat",
                        "reset",
                    )
            )
                writes += path to query
            if (
                endpoint in
                    setOf(
                        "setparamvalue",
                        "snapshot",
                        "lockvideo",
                        "enterrecorder",
                        "exitrecorder",
                        "setsystime",
                        "setwifi",
                        "wifireboot",
                        "deletefile",
                        "sdformat",
                        "reset",
                    ) && timeoutNextMutation
            ) {
                timeoutNextMutation = false
                throw java.net.SocketTimeoutException()
            }
            if (endpoint == "setparamvalue") {
                val key = query.first { it.first == "param" }.second
                settings[key] = query.first { it.first == "value" }.second.toInt()
            }
            if (endpoint == "deletefile") clipExists = false
            if (endpoint == "getfilelist" && failNextFileCheck) {
                failNextFileCheck = false
                return HttpReply(500, emptyMap(), ByteArray(0))
            }
            val body =
                if (endpoint == overrideEndpoint) overrideBody!!
                else
                    when (endpoint) {
                        "getdeviceattr" -> fixture.getValue("device").toString()
                        "getproductinfo" -> fixture.getValue("product").toString()
                        "capability" -> fixture.getValue("capability").toString()
                        "getparamitems" -> fixture.getValue("settings").toString()
                        "getparamvalue" -> {
                            val requested = query.firstOrNull { it.first == "param" }?.second
                            if (requested == "all") {
                                val info =
                                    kotlinx.serialization.json.JsonArray(
                                        settings.map { (key, value) ->
                                            kotlinx.serialization.json.buildJsonObject {
                                                put(
                                                    "name",
                                                    kotlinx.serialization.json.JsonPrimitive(key),
                                                )
                                                put(
                                                    "value",
                                                    kotlinx.serialization.json.JsonPrimitive(value),
                                                )
                                            }
                                        }
                                    )
                                kotlinx.serialization.json
                                    .buildJsonObject {
                                        put("result", kotlinx.serialization.json.JsonPrimitive(0))
                                        put("info", info)
                                    }
                                    .toString()
                            } else "{\"result\":0,\"info\":{\"value\":${settings[requested] ?: 0}}}"
                        }
                        "getsdinfo" -> fixture.getValue("sd").toString()
                        "getbatteryinfo" -> fixture.getValue("battery").toString()
                        "getlockvideostatus" -> fixture.getValue("lock").toString()
                        "getrecduration" -> fixture.getValue("duration").toString()
                        "getfilelist" ->
                            if (clipExists)
                                "{\"result\":0,\"info\":[{\"folder\":\"loop\",\"count\":1,\"files\":[{\"name\":\"/mnt/card/video_front/clip.mp4\",\"size\":123,\"time\":\"2024-03-11 11:40:02\"}]}]}"
                            else
                                "{\"result\":0,\"info\":[{\"folder\":\"loop\",\"count\":0,\"files\":[]}]}"
                        else -> "{\"result\":0,\"info\":{}}"
                    }
            return HttpReply(200, emptyMap(), body.toByteArray())
        }

        override suspend fun stream(
            path: String,
            sink: TransferSink,
            onBytes: (Long, Long?) -> Unit,
        ) {
            streamStarted?.complete(Unit)
            releaseStream?.await()
        }

        override fun openSocket(): CameraSocket = error("Not used by fixture test")
    }
}
