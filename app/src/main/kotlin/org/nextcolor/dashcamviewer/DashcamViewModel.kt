package org.nextcolor.dashcamviewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Network
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.nextcolor.dashcamviewer.android.CameraConnectionState
import org.nextcolor.dashcamviewer.android.ConnectionStage
import org.nextcolor.dashcamviewer.android.ExistingWifi
import org.nextcolor.dashcamviewer.android.OwnedDownload
import org.nextcolor.dashcamviewer.protocol.*

enum class MainTab(val title: String) {
    Camera("Camera"),
    Recordings("Recordings"),
    Downloads("Downloads"),
    Settings("Settings"),
}

data class WifiCredentialsDraft(
    val ssid: String = "",
    val password: String = "",
    val remember: Boolean = false,
)

data class AppUiState(
    val tab: MainTab = MainTab.Camera,
    val connection: CameraConnectionState = CameraConnectionState(),
    val snapshot: CameraSnapshot? = null,
    val message: String? = null,
    val folder: MediaFolder = MediaFolder.LOOP,
    val pageStart: Int = 0,
    val media: List<RemoteMedia> = emptyList(),
    val selected: RemoteMedia? = null,
    val history: List<OwnedDownload> = emptyList(),
    val playerUri: Uri? = null,
    val playerIsRemote: Boolean = false,
    val wifiChoices: List<ExistingWifi> = emptyList(),
    val chosenWifi: Network? = null,
    val defaultNetwork: String = "Unknown",
    val viewedJpeg: Bitmap? = null,
    val liveActive: Boolean = false,
    val liveVideoReady: Boolean = false,
    val liveIssue: String? = null,
    val loadingMedia: Boolean = false,
    val nearbyWifi: List<org.nextcolor.dashcamviewer.android.NearbyWifi> = emptyList(),
    val scanningWifi: Boolean = false,
    val discoveryMessage: String? = null,
    val wifiSuggestionsOpen: Boolean = false,
    val mediaListError: String? = null,
    val mediaListLoaded: Boolean = false,
    val openingMedia: Boolean = false,
    val playbackLoading: Boolean = false,
    val playbackError: String? = null,
    val commandBusy: Boolean = false,
)

class DashcamViewModel
@JvmOverloads
constructor(
    application: Application,
    private val container: AppContainer = (application as DashcamApplication).container,
) : AndroidViewModel(application) {
    private val _credentials = MutableStateFlow(WifiCredentialsDraft())
    val credentials = _credentials.asStateFlow()
    private var credentialsEdited = false
    var pendingConnectionCredentials: Pair<String, String>? = null
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var listJob: Job? = null
    private var localPhotoJob: Job? = null
    private var discoveryPresentation = 0L
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var liveJob: Job? = null
    private var remotePhotoJob: Job? = null
    private var player: ExoPlayer? = null
    @Volatile private var sampleOutput: java.io.OutputStream? = null

    init {
        viewModelScope.launch {
            runCatching { container.preferences.loadCredentials() }
                .getOrNull()
                ?.let { saved ->
                    if (!credentialsEdited)
                        _credentials.value = WifiCredentialsDraft(saved.first, saved.second, true)
                }
        }
        viewModelScope.launch {
            container.preferences.recoverPendingRows()
            container.preferences.historyUpdates
                .catch {
                    _state.value =
                        _state.value.copy(
                            message =
                                "Saved-file history could not be loaded. Your downloads are still in Movies/DashcamViewer or Pictures/DashcamViewer on this phone."
                        )
                }
                .collect { history ->
                    _state.value = _state.value.copy(history = history)
                }
        }
        viewModelScope.launch {
            container.networks.state.collect { connection ->
                val previous = _state.value
                _state.value =
                    previous.copy(
                        connection = connection,
                        snapshot =
                            previous.snapshot.takeIf {
                                connection.stage == ConnectionStage.Connected
                            },
                        media =
                            previous.media.takeIf { connection.stage == ConnectionStage.Connected }
                                ?: emptyList(),
                        selected =
                            previous.selected.takeIf {
                                connection.stage == ConnectionStage.Connected
                            },
                        mediaListLoaded =
                            previous.mediaListLoaded &&
                                connection.stage == ConnectionStage.Connected,
                        liveVideoReady =
                            previous.liveVideoReady &&
                                connection.stage == ConnectionStage.Connected,
                        liveIssue =
                            previous.liveIssue.takeIf {
                                connection.stage == ConnectionStage.Connected
                            },
                        liveActive =
                            previous.liveActive && connection.stage == ConnectionStage.Connected,
                        message =
                            if (
                                previous.connection.stage != connection.stage &&
                                    connection.stage !in
                                        setOf(ConnectionStage.Unavailable, ConnectionStage.Denied)
                            )
                                null
                            else previous.message,
                    )
                if (connection.stage != ConnectionStage.Connected) {
                    listJob?.cancel()
                    liveJob?.cancel()
                    remotePhotoJob?.cancel()
                    if (_state.value.playerIsRemote) {
                        stopPlayback()
                        if (connection.stage == ConnectionStage.Lost)
                            _state.value =
                                _state.value.copy(
                                    message =
                                        "Camera connection lost. Reconnect to open files from the camera."
                                )
                    }
                }
                if (
                    connection.stage != ConnectionStage.Connected &&
                        connection.stage != ConnectionStage.Identifying
                ) {
                    container.clearCameraSession()
                    listJob?.cancel()
                    _state.value =
                        _state.value.copy(
                            snapshot = null,
                            media = emptyList(),
                            selected = null,
                            mediaListLoaded = false,
                            loadingMedia = false,
                            mediaListError = null,
                        )
                }
            }
        }
        viewModelScope.launch {
            container.networks.wifiChoices.collect { choices ->
                _state.value =
                    _state.value.copy(
                        wifiChoices = choices,
                        chosenWifi =
                            _state.value.chosenWifi?.takeIf { selected ->
                                choices.any { it.network == selected }
                            } ?: choices.singleOrNull()?.network,
                    )
            }
        }
        viewModelScope.launch {
            container.networks.defaultState.collect { network ->
                _state.value =
                    _state.value.copy(
                        defaultNetwork =
                            "${network.transport}; internet=${if (network.internet) "available" else "not advertised"}; validated=${network.validated}"
                    )
            }
        }
    }

    fun editNetworkName(name: String) {
        credentialsEdited = true
        if (name != _credentials.value.ssid) _credentials.value = WifiCredentialsDraft(ssid = name)
    }

    fun editNetworkPassword(password: String) {
        credentialsEdited = true
        _credentials.value = _credentials.value.copy(password = password)
    }

    fun rememberNetworkPassword(remember: Boolean) {
        credentialsEdited = true
        _credentials.value = _credentials.value.copy(remember = remember)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun chooseTab(tab: MainTab) {
        discoveryPresentation++
        _state.value = _state.value.copy(tab = tab, wifiSuggestionsOpen = false)
        if (
            tab == MainTab.Recordings &&
                _state.value.connection.stage == ConnectionStage.Connected &&
                !_state.value.mediaListLoaded &&
                !_state.value.loadingMedia
        )
            list()
    }

    fun observeConnectedWifi() {
        container.networks.refreshWifiNames()
    }

    fun discoveryPermissionDenied() {
        _state.value =
            _state.value.copy(
                discoveryMessage =
                    "Wi-Fi names are hidden without precise location permission. You can enter the name manually or join the camera in Android Wi-Fi settings."
            )
    }

    fun setWifiSuggestionsOpen(open: Boolean) {
        if (!open) discoveryPresentation++
        _state.value = _state.value.copy(wifiSuggestionsOpen = open)
    }

    fun discoverWifi() {
        if (discoveryJob?.isActive == true) return
        container.networks.refreshWifiNames()
        val presentation = ++discoveryPresentation
        discoveryJob = viewModelScope.launch {
            _state.value =
                _state.value.copy(
                    scanningWifi = true,
                    discoveryMessage = "Looking for nearby Wi-Fi…",
                )
            try {
                val result =
                    org.nextcolor.dashcamviewer.android.WifiDiscovery(getApplication()).scan()
                _state.value =
                    _state.value.copy(
                        nearbyWifi = result.networks,
                        discoveryMessage = result.message,
                        wifiSuggestionsOpen =
                            presentation == discoveryPresentation &&
                                _state.value.tab == MainTab.Camera &&
                                result.networks.isNotEmpty(),
                    )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value =
                    _state.value.copy(
                        discoveryMessage =
                            "Could not read Wi-Fi names. Check Android Wi-Fi and location permissions, or enter the name manually."
                    )
            } finally {
                _state.value = _state.value.copy(scanningWifi = false)
            }
        }
    }

    fun selectExisting(network: Network?) {
        _state.value = _state.value.copy(chosenWifi = network)
    }

    fun requestingPermission() {
        container.networks.markRequestingPermission()
    }

    fun permissionDenied() {
        container.networks.markPermissionDenied()
    }

    fun connect(
        ssid: String,
        password: String,
        remember: Boolean,
        acceptedWifiLoss: Boolean = false,
    ) {
        val previous = connectionJob
        previous?.cancel()
        connectionJob = viewModelScope.launch {
            previous?.join()
            _state.value = _state.value.copy(message = "Requesting camera Wi-Fi…")
            try {
                if (!remember) container.preferences.clearCredentials()
                container.networks.requestCamera(ssid, password, acceptedWifiLoss)
                val network =
                    container.networks.currentNetwork() ?: error("Camera Wi-Fi was not acquired")
                val supported = verifyCamera(network)
                if (supported) {
                    if (remember) container.preferences.saveCredentials(ssid, password)
                    else container.preferences.clearCredentials()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(message = safeMessage(t))
            }
        }
    }

    fun borrowSelected() {
        val previous = connectionJob
        previous?.cancel()
        connectionJob = viewModelScope.launch {
            previous?.join()
            val network = _state.value.chosenWifi ?: return@launch
            try {
                container.networks.borrow(network)
                verifyCamera(network)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(message = safeMessage(t))
            }
        }
    }

    private suspend fun verifyCamera(network: Network): Boolean {
        _state.value = _state.value.copy(message = "Identifying A13 camera…")
        try {
            val snapshot = container.identify(network)
            _state.value =
                _state.value.copy(snapshot = snapshot, message = null, wifiChoices = emptyList())
            return true
        } catch (t: Throwable) {
            if (
                t is kotlinx.coroutines.CancellationException &&
                    t !is kotlinx.coroutines.TimeoutCancellationException
            )
                throw t
            val message =
                when (t) {
                    is kotlinx.coroutines.TimeoutCancellationException ->
                        "Camera verification timed out. Wi-Fi is still selected; try Verify camera again."
                    is CameraReadException.WrongCamera ->
                        "This device is not a supported A13 camera."
                    else -> safeMessage(t)
                }
            container.networks.markIdentityRejected(message)
            container.clearCameraSession()
            _state.value =
                _state.value.copy(
                    snapshot = null,
                    message = message,
                )
            return false
        }
    }

    fun verifyCurrentNetwork() {
        val network = container.networks.currentNetwork() ?: return
        val previous = connectionJob
        previous?.cancel()
        connectionJob = viewModelScope.launch {
            previous?.join()
            container.networks.markVerifying()
            verifyCamera(network)
        }
    }

    fun cancelVerification() = viewModelScope.launch {
        connectionJob?.cancel()
        connectionJob?.join()
        container.clearCameraSession()
        container.networks.markSessionCleared()
        _state.value =
            _state.value.copy(message = "Verification cancelled. Wi-Fi remains selected.")
    }

    fun disconnect() = viewModelScope.launch {
        if (container.hasActiveDownload()) container.cancelDownload()
        connectionJob?.cancel()
        connectionJob?.join()
        stopLive().join()
        stopPlayback()
        container.networks.disconnect()
        container.clearCameraSession()
    }

    fun refreshSnapshot(announce: Boolean = true) = viewModelScope.launch {
        val generation = container.networks.state.value.generation
        try {
            val snapshot =
                container.currentClient()?.readSnapshot() ?: error("Connect to the camera first")
            if (
                container.networks.state.value.generation != generation ||
                    container.networks.state.value.stage != ConnectionStage.Connected
            )
                return@launch
            container.setSnapshot(snapshot)
            _state.value =
                _state.value.copy(
                    snapshot = snapshot,
                    message = if (announce) "Camera status refreshed" else _state.value.message,
                )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (container.networks.state.value.generation != generation) return@launch
            _state.value = _state.value.copy(message = safeMessage(t))
            if (
                t is CameraReadException.Timeout ||
                    t is CameraReadException.DisconnectedNetwork ||
                    t is CameraReadException.WrongCamera
            )
                container.networks.markCameraUnresponsive()
        }
    }

    fun list(folder: MediaFolder = _state.value.folder, start: Int = _state.value.pageStart) {
        val previous = listJob
        previous?.cancel()
        listJob = viewModelScope.launch {
            previous?.join()
            val generation = container.networks.state.value.generation
            val samePage = folder == _state.value.folder && start == _state.value.pageStart
            _state.value =
                _state.value.copy(
                    loadingMedia = true,
                    mediaListError = null,
                    folder = folder,
                    pageStart = start,
                    media = if (samePage) _state.value.media else emptyList(),
                    selected = null,
                )
            try {
                val page =
                    container.currentClient()?.listFiles(folder, start, 50)
                        ?: error("Connect to the camera first")
                if (container.networks.state.value.generation != generation) return@launch
                _state.value = _state.value.copy(media = page.items, mediaListLoaded = true)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value =
                    _state.value.copy(mediaListError = safeMessage(t), mediaListLoaded = true)
            } finally {
                _state.value = _state.value.copy(loadingMedia = false)
            }
        }
    }

    fun select(media: RemoteMedia?) {
        _state.value = _state.value.copy(selected = media)
    }

    fun execute(
        command: CameraCommand,
        confirmation: ActionConfirmation = ActionConfirmation(true),
    ) = viewModelScope.launch {
        if (_state.value.commandBusy) return@launch
        _state.value = _state.value.copy(commandBusy = true)
        try {
            stopPlaybackForMutation(command)
            val client = container.currentClient() ?: error("Connect to the camera first")
            val prepared = client.prepare(command)
            val outcome = client.execute(prepared, confirmation)
            _state.value =
                _state.value.copy(
                    message =
                        when (outcome) {
                            CommandOutcome.Confirmed -> "Camera confirmed the change"
                            CommandOutcome.AcceptedUnverified ->
                                "Request accepted; effect has not been verified"
                            CommandOutcome.AlreadyDesired -> "Already set to that value"
                            CommandOutcome.Rejected ->
                                "Camera rejected the action or its safety conditions changed"
                            CommandOutcome.Unconfirmed ->
                                "Request sent, but the result is unconfirmed. Do not repeat automatically."
                        }
                )
            if (
                command == CameraCommand.RestartWifi ||
                    command is CameraCommand.FormatCard ||
                    command is CameraCommand.FactoryReset
            ) {
                _state.value =
                    _state.value.copy(snapshot = null, media = emptyList(), selected = null)
            }
            if (command == CameraCommand.StartLive && outcome == CommandOutcome.Confirmed)
                startLiveFlow()
            if (command == CameraCommand.StopLive && outcome == CommandOutcome.Confirmed) {
                liveJob?.cancel()
                liveJob = null
                container.setLiveActive(false)
                _state.value = _state.value.copy(liveActive = false)
            }
            if (
                command is CameraCommand.StartRecording ||
                    command is CameraCommand.StopRecording ||
                    command is CameraCommand.SetSetting ||
                    command is CameraCommand.SetParking ||
                    command is CameraCommand.SwitchCamera ||
                    command == CameraCommand.LockCurrentRecording
            )
                refreshSnapshot(announce = false).join()
            if (command is CameraCommand.DeleteOneFile && outcome == CommandOutcome.Confirmed)
                list()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value = _state.value.copy(message = safeMessage(t))
        } finally {
            _state.value = _state.value.copy(commandBusy = false)
        }
    }

    private suspend fun stopPlaybackForMutation(command: CameraCommand) {
        if (command == CameraCommand.Snapshot || command == CameraCommand.LockCurrentRecording)
            return
        if (container.currentSession()?.liveActive == true) stopLive().join()
        stopPlayback()
    }

    private suspend fun startLiveFlow() {
        liveJob?.cancel()
        liveJob?.join()
        container.setLiveActive(true)
        _state.value =
            _state.value.copy(liveActive = true, liveVideoReady = false, liveIssue = null)
        liveJob =
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val lastRenderedAt =
                    java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime())
                var renderedCount = 0L
                val monitor = launch {
                    while (true) {
                        kotlinx.coroutines.delay(1_000)
                        if (
                            android.os.SystemClock.elapsedRealtime() - lastRenderedAt.get() > 10_000
                        ) {
                            _state.value =
                                _state.value.copy(
                                    liveIssue =
                                        if (_state.value.liveVideoReady)
                                            "Live video has stalled. Stop live view, check the camera, then try again."
                                        else
                                            "No live video is being displayed. Check that the camera is recording, or stop live view and try again."
                                )
                        }
                    }
                }
                try {
                    container.currentClient()?.liveVideo()?.collect { event ->
                        if (event is VideoEvent.Frame) {
                            val decoder = container.decoder()
                            decoder.feed(event)
                            if (
                                decoder.renderedFrames > 0 &&
                                    decoder.renderedFrames != renderedCount
                            ) {
                                renderedCount = decoder.renderedFrames
                                lastRenderedAt.set(android.os.SystemClock.elapsedRealtime())
                                if (!_state.value.liveVideoReady || _state.value.liveIssue != null)
                                    _state.value =
                                        _state.value.copy(liveVideoReady = true, liveIssue = null)
                            }
                            synchronized(this@DashcamViewModel) {
                                sampleOutput?.write(event.bytes)
                                sampleOutput?.flush()
                            }
                        } else if (event is VideoEvent.Failure)
                            _state.value = _state.value.copy(liveIssue = event.reason)
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    _state.value =
                        _state.value.copy(liveIssue = "Live view stopped. ${safeMessage(t)}")
                } finally {
                    monitor.cancel()
                    stopLiveSample()
                    container.setLiveActive(false)
                    _state.value = _state.value.copy(liveActive = false)
                }
            }
    }

    fun stopLive() = viewModelScope.launch {
        val client = container.currentClient()
        if (client != null && (container.currentSession()?.liveActive == true)) {
            runCatching {
                val action = client.prepare(CameraCommand.StopLive)
                client.execute(action, ActionConfirmation(true))
            }
        }
        val previous = liveJob
        liveJob = null
        previous?.cancel()
        previous?.join()
        container.setLiveActive(false)
        _state.value =
            _state.value.copy(liveActive = false, liveVideoReady = false, liveIssue = null)
    }

    fun startDownload(media: RemoteMedia) {
        viewModelScope.launch {
            stopLive().join()
            stopPlayback()
            if (!container.reserveDownload(media)) {
                _state.value = _state.value.copy(message = "Only one download can be active")
                return@launch
            }
            val intent =
                android.content.Intent(getApplication(), DownloadService::class.java).apply {
                    action = DownloadService.ACTION_START
                    putExtra(DownloadService.EXTRA_MEDIA_PATH, media.exactCameraPath)
                    putExtra(DownloadService.EXTRA_FOLDER, media.folder.name)
                }
            try {
                androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
                _state.value = _state.value.copy(tab = MainTab.Downloads)
            } catch (t: Throwable) {
                container.abandonDownloadReservation("Could not start background download service")
                container.releaseOwnedNetworkIfIdle()
                _state.value = _state.value.copy(message = safeMessage(t))
            }
        }
    }

    fun cancelDownload() {
        container.cancelDownload()
    }

    fun downloadState() = container.downloadState

    fun playRemote(media: RemoteMedia) = viewModelScope.launch {
        if (container.hasActiveDownload()) {
            _state.value =
                _state.value.copy(message = "Remote playback is unavailable during a download")
            return@launch
        }
        stopLive().join()
        stopPlayback()
        val network =
            container.networks.currentNetwork()
                ?: run {
                    _state.value = _state.value.copy(message = "Camera network disconnected")
                    return@launch
                }
        val uri = org.nextcolor.dashcamviewer.android.A13MediaPlayback.uri(media)
        startPlayer(
            uri,
            org.nextcolor.dashcamviewer.android.A13MediaPlayback.dataSourceFactory(network, media),
        )
    }

    fun playLocal(uri: Uri) {
        stopPlayback()
        startPlayer(uri, null)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayer(
        uri: Uri,
        remoteFactory: androidx.media3.datasource.DataSource.Factory?,
    ) {
        val ctx = getApplication<Application>()
        val remote = remoteFactory != null
        val factory =
            if (remoteFactory != null) DefaultMediaSourceFactory(remoteFactory)
            else DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx))
        player =
            ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).build().also {
                it.addListener(
                    object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            _state.value =
                                _state.value.copy(
                                    playbackLoading =
                                        playbackState ==
                                            androidx.media3.common.Player.STATE_BUFFERING
                                )
                        }

                        override fun onPlayerError(
                            error: androidx.media3.common.PlaybackException
                        ) {
                            _state.value =
                                _state.value.copy(
                                    playbackLoading = false,
                                    playbackError =
                                        "This video could not be played. The file may be unavailable or its format may not be supported.",
                                )
                        }
                    }
                )
                it.setMediaItem(MediaItem.fromUri(uri))
                it.prepare()
                it.playWhenReady = true
            }
        container.setRemotePlaybackActive(remote)
        _state.value =
            _state.value.copy(
                playerUri = uri,
                playerIsRemote = remote,
                viewedJpeg = null,
                playbackLoading = true,
                playbackError = null,
            )
    }

    fun player(): ExoPlayer? = player

    fun isLiveActive(): Boolean = container.currentSession()?.liveActive == true

    fun stopPlayback() {
        player?.release()
        player = null
        container.setRemotePlaybackActive(false)
        _state.value =
            _state.value.copy(
                playerUri = null,
                playerIsRemote = false,
                playbackLoading = false,
                playbackError = null,
            )
    }

    fun attachLiveSurface(surface: Surface?) {
        container.decoder().attachSurface(surface)
        if (surface != null && _state.value.liveActive)
            _state.value = _state.value.copy(liveVideoReady = false)
    }

    @Synchronized
    fun beginLiveSample(uri: Uri) {
        try {
            check(sampleOutput == null) {
                "A video sample is already being saved. Stop it before choosing another file."
            }
            sampleOutput =
                getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                    ?: error("Could not open the selected sample file")
            _state.value =
                _state.value.copy(
                    message =
                        if (isLiveActive()) "Saving video-only H.264 sample"
                        else
                            "Sample file ready. Reconnect if needed, then start live view to capture video."
                )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(message = safeMessage(t))
        }
    }

    @Synchronized
    fun stopLiveSample() {
        runCatching {
            sampleOutput?.flush()
            sampleOutput?.close()
        }
        sampleOutput = null
    }

    fun viewRemoteJpeg(media: RemoteMedia) = viewModelScope.launch {
        if (!container.reservePhotoRead()) {
            _state.value = _state.value.copy(message = "Another media transfer is already active")
            return@launch
        }
        remotePhotoJob = coroutineContext[kotlinx.coroutines.Job]
        _state.value =
            _state.value.copy(openingMedia = true, playbackError = null, viewedJpeg = null)
        try {
            stopLive().join()
            stopPlayback()
            require(
                media.exactCameraPath.substringAfterLast('.').equals("jpg", ignoreCase = true)
            ) {
                "Selected camera file is not a JPEG"
            }
            val client = container.currentClient() ?: error("Camera disconnected")
            val bitmap =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val sink = BoundedImageSink(16 * 1024 * 1024)
                    client.download(media, sink).collect {}
                    android.graphics.BitmapFactory.decodeByteArray(sink.bytes(), 0, sink.size())
                        ?: error("Camera returned an unsupported image")
                }
            _state.value = _state.value.copy(viewedJpeg = bitmap)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value =
                _state.value.copy(playbackError = "Could not open this photo. ${safeMessage(t)}")
        } finally {
            container.releasePhotoRead()
            remotePhotoJob = null
            _state.value = _state.value.copy(openingMedia = false)
        }
    }

    fun clearViewedJpeg() {
        _state.value = _state.value.copy(viewedJpeg = null)
    }

    fun closeMedia() {
        remotePhotoJob?.cancel()
        localPhotoJob?.cancel()
        stopPlayback()
        _state.value =
            _state.value.copy(viewedJpeg = null, openingMedia = false, playbackError = null)
    }

    fun openLocalImage(uri: Uri) {
        localPhotoJob?.cancel()
        localPhotoJob = viewModelScope.launch {
            stopPlayback()
            _state.value =
                _state.value.copy(openingMedia = true, viewedJpeg = null, playbackError = null)
            try {
                val bitmap =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        getApplication<Application>()
                            .contentResolver
                            .openInputStream(uri)
                            ?.use(android.graphics.BitmapFactory::decodeStream)
                            ?: error("File unavailable or unsupported")
                    }
                _state.value = _state.value.copy(viewedJpeg = bitmap)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value =
                    _state.value.copy(
                        playbackError =
                            "Could not open this photo. It may have been moved or deleted."
                    )
            } finally {
                _state.value = _state.value.copy(openingMedia = false)
            }
        }
    }

    fun shareDownload(uri: Uri) {
        val send =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type =
                    _state.value.history.firstOrNull { it.uri == uri.toString() }?.mimeType ?: "*/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        getApplication<Application>()
            .startActivity(
                android.content.Intent.createChooser(send, "Share dashcam file")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
    }

    fun refreshHistory() = viewModelScope.launch {
        _state.value = _state.value.copy(history = container.preferences.history())
    }

    fun onAppBackgrounded() = viewModelScope.launch {
        discoveryJob?.cancel()
        localPhotoJob?.cancel()
        val photo = remotePhotoJob
        photo?.cancel()
        photo?.join()
        stopLive().join()
        if (_state.value.playerIsRemote) stopPlayback() else player?.pause()
        container.onBackground()
    }

    fun onAppForegrounded() {
        container.onForeground()
    }

    private fun safeMessage(error: Throwable): String =
        when (error) {
            is CameraReadException.WrongCamera -> "Unsupported device; A13 controls are disabled"
            is CameraReadException.Timeout -> "The camera did not respond. Try again."
            is CameraReadException.DisconnectedNetwork ->
                "Camera session is no longer available. Verify the camera again."
            is CameraReadException.CommunicationFailure ->
                "Could not reach the camera at ${org.nextcolor.dashcamviewer.android.A13CameraAddress.HOST}. Check the selected Wi-Fi connection."
            else -> error.message?.take(180) ?: "Camera operation failed"
        }

    override fun onCleared() {
        stopLiveSample()
        liveJob?.cancel()
        player?.release()
        super.onCleared()
    }
}

private class BoundedImageSink(private val maximumBytes: Int) : TransferSink {
    private val output = ByteArrayOutputStream()

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(output.size() + length <= maximumBytes) {
            "JPEG exceeds the in-memory viewing limit"
        }
        output.write(bytes, offset, length)
    }

    override suspend fun commit() = Unit

    override suspend fun rollback() {
        output.reset()
    }

    fun bytes(): ByteArray = output.toByteArray()

    fun size(): Int = output.size()
}
