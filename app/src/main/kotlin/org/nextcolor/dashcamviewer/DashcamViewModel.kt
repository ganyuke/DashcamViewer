package org.nextcolor.dashcamviewer

import android.app.Application
import android.net.Network
import android.net.Uri
import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import org.nextcolor.dashcamviewer.android.CameraConnectionState
import org.nextcolor.dashcamviewer.android.ConnectionStage
import org.nextcolor.dashcamviewer.android.ExistingWifi
import org.nextcolor.dashcamviewer.android.OwnedDownload
import org.nextcolor.dashcamviewer.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class MainTab(val title: String) { Camera("Camera"), Recordings("Recordings"), Downloads("Downloads"), Settings("Settings") }
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
)

class DashcamViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as DashcamApplication).container
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var liveJob: Job? = null
    private var remotePhotoJob: Job? = null
    private var player: ExoPlayer? = null
    @Volatile private var sampleOutput: java.io.OutputStream? = null

    init {
        viewModelScope.launch {
            container.preferences.recoverPendingRows()
            _state.value = _state.value.copy(history = container.preferences.history())
        }
        viewModelScope.launch {
            container.networks.state.collect { connection ->
                _state.value = _state.value.copy(connection = connection)
                if (connection.stage == ConnectionStage.Lost || connection.stage == ConnectionStage.Disconnected) {
                    container.clearCameraSession()
                    _state.value = _state.value.copy(snapshot = null, media = emptyList())
                }
            }
        }
        viewModelScope.launch {
            container.networks.defaultState.collect { network ->
                _state.value = _state.value.copy(defaultNetwork = "${network.transport}; internet=${if (network.internet) "available" else "not advertised"}; validated=${network.validated}")
            }
        }
    }

    fun chooseTab(tab: MainTab) { _state.value = _state.value.copy(tab = tab) }
    fun connectionOptions() { _state.value = _state.value.copy(wifiChoices = container.networks.existingWifiNetworks()) }
    fun selectExisting(network: Network?) { _state.value = _state.value.copy(chosenWifi = network) }
    fun requestingPermission() { container.networks.markRequestingPermission() }
    fun permissionDenied() { container.networks.markPermissionDenied() }
    fun connect(ssid: String, password: String, remember: Boolean, acceptedWifiLoss: Boolean = false) = viewModelScope.launch {
        _state.value = _state.value.copy(message = "Requesting camera Wi-Fi…")
        try {
            if (!remember) container.preferences.clearCredentials()
            container.networks.requestCamera(ssid, password, acceptedWifiLoss)
            val network = container.networks.currentNetwork() ?: error("Camera Wi-Fi was not acquired")
            val supported = verifyCamera(network)
            if (supported) {
                if (remember) container.preferences.saveCredentials(ssid, password) else container.preferences.clearCredentials()
            }
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
    }
    fun borrowSelected() = viewModelScope.launch {
        val network = _state.value.chosenWifi ?: return@launch
        try { container.networks.borrow(network); verifyCamera(network) }
        catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
    }
    private suspend fun verifyCamera(network: Network): Boolean {
        _state.value = _state.value.copy(message = "Identifying A13 camera…")
        try {
            val snapshot = container.identify(network)
            _state.value = _state.value.copy(snapshot = snapshot, message = "A13 camera connected", wifiChoices = emptyList())
            return true
        } catch (t: Throwable) {
            container.networks.markIdentityRejected()
            container.clearCameraSession()
            _state.value = _state.value.copy(snapshot = null, message = if (t is CameraReadException.WrongCamera) "Unsupported device: expected A13 / HUAXIN / eeasytech" else safeMessage(t))
            return false
        }
    }
    fun disconnect() = viewModelScope.launch { stopLive(); stopPlayback(); container.networks.disconnect(); container.clearCameraSession() }

    fun refreshSnapshot() = viewModelScope.launch {
        try {
            val snapshot = container.currentClient()?.readSnapshot() ?: error("Connect to the camera first")
            container.setSnapshot(snapshot)
            _state.value = _state.value.copy(snapshot = snapshot, message = "Camera status refreshed")
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
    }
    fun list(folder: MediaFolder = _state.value.folder, start: Int = _state.value.pageStart) = viewModelScope.launch {
        try {
            val page = container.currentClient()?.listFiles(folder, start, 50) ?: error("Connect to the camera first")
            _state.value = _state.value.copy(folder = folder, pageStart = start, media = page.items, selected = null, message = "")
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t), media = emptyList()) }
    }
    fun select(media: RemoteMedia?) { _state.value = _state.value.copy(selected = media) }
    fun execute(command: CameraCommand, confirmation: ActionConfirmation = ActionConfirmation(true)) = viewModelScope.launch {
        try {
            stopPlaybackForMutation(command)
            val client = container.currentClient() ?: error("Connect to the camera first")
            val prepared = client.prepare(command)
            val outcome = client.execute(prepared, confirmation)
            _state.value = _state.value.copy(message = when (outcome) {
                CommandOutcome.Confirmed -> "Camera confirmed the change"
                CommandOutcome.AcceptedUnverified -> "Request accepted; effect has not been verified"
                CommandOutcome.AlreadyDesired -> "Already set to that value"
                CommandOutcome.Rejected -> "Camera rejected the action or its safety conditions changed"
                CommandOutcome.Unconfirmed -> "Request sent, but the result is unconfirmed. Do not repeat automatically."
            })
            if (command == CameraCommand.RestartWifi || command is CameraCommand.FormatCard || command is CameraCommand.FactoryReset) {
                _state.value = _state.value.copy(snapshot = null, media = emptyList(), selected = null)
            }
            if (command == CameraCommand.StartLive && outcome == CommandOutcome.Confirmed) startLiveFlow()
            if (command == CameraCommand.StopLive && outcome == CommandOutcome.Confirmed) { liveJob?.cancel(); liveJob = null; container.setLiveActive(false) }
            if (command is CameraCommand.StartRecording || command is CameraCommand.StopRecording || command is CameraCommand.SetSetting || command is CameraCommand.SetParking || command is CameraCommand.SwitchCamera || command == CameraCommand.LockCurrentRecording) refreshSnapshot()
            if (command is CameraCommand.DeleteOneFile && outcome == CommandOutcome.Confirmed) list()
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
    }
    private suspend fun stopPlaybackForMutation(command: CameraCommand) {
        if (command == CameraCommand.Snapshot || command == CameraCommand.LockCurrentRecording) return
        if (container.currentSession()?.liveActive == true) stopLive().join()
        stopPlayback()
    }
    private fun startLiveFlow() {
        container.setLiveActive(true)
        liveJob?.cancel()
        liveJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { container.currentClient()?.liveVideo()?.collect { event ->
                if (event is VideoEvent.Frame) {
                    container.decoder().feed(event)
                    synchronized(this@DashcamViewModel) { sampleOutput?.write(event.bytes); sampleOutput?.flush() }
                } else if (event is VideoEvent.Failure) _state.value = _state.value.copy(message = event.reason)
            } }
            catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
            finally { stopLiveSample(); container.setLiveActive(false) }
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
        liveJob?.cancel(); liveJob = null; container.setLiveActive(false)
    }

    fun startDownload(media: RemoteMedia) {
        viewModelScope.launch {
            stopLive().join(); stopPlayback()
            if (!container.reserveDownload(media)) {
                _state.value = _state.value.copy(message = "Only one download can be active")
                return@launch
            }
            val intent = android.content.Intent(getApplication(), DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
                putExtra(DownloadService.EXTRA_MEDIA_PATH, media.exactCameraPath)
                putExtra(DownloadService.EXTRA_FOLDER, media.folder.name)
            }
            try { androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent) }
            catch (t: Throwable) {
                container.abandonDownloadReservation("Could not start background download service")
                container.releaseOwnedNetworkIfIdle()
                _state.value = _state.value.copy(message = safeMessage(t))
            }
        }
    }
    fun cancelDownload() { container.cancelDownload() }
    fun downloadState() = container.downloadState

    fun playRemote(media: RemoteMedia) = viewModelScope.launch {
        if (container.hasActiveDownload()) { _state.value = _state.value.copy(message = "Remote playback is unavailable during a download"); return@launch }
        stopLive().join(); stopPlayback()
        val network = container.networks.currentNetwork() ?: run { _state.value = _state.value.copy(message = "Camera network disconnected"); return@launch }
        val uri = org.nextcolor.dashcamviewer.android.A13MediaPlayback.uri(media)
        startPlayer(uri, org.nextcolor.dashcamviewer.android.A13MediaPlayback.dataSourceFactory(network, media))
    }
    fun playLocal(uri: Uri) { stopPlayback(); startPlayer(uri, null) }
    private fun startPlayer(uri: Uri, remoteFactory: androidx.media3.datasource.DataSource.Factory?) {
        val ctx = getApplication<Application>()
        val remote = remoteFactory != null
        val factory = if (remoteFactory != null) DefaultMediaSourceFactory(remoteFactory) else DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx))
        player = ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).build().also { it.setMediaItem(MediaItem.fromUri(uri)); it.prepare(); it.playWhenReady = true }
        container.setRemotePlaybackActive(remote)
        _state.value = _state.value.copy(playerUri = uri, playerIsRemote = remote)
    }
    fun player(): ExoPlayer? = player
    fun isLiveActive(): Boolean = container.currentSession()?.liveActive == true
    fun stopPlayback() {
        player?.release(); player = null; container.setRemotePlaybackActive(false)
        _state.value = _state.value.copy(playerUri = null, playerIsRemote = false)
    }
    fun attachLiveSurface(surface: Surface?) { container.decoder().attachSurface(surface) }
    @Synchronized fun beginLiveSample(uri: Uri) {
        check(container.currentSession()?.liveActive == true) { "Start live view before saving a sample." }
        check(sampleOutput == null) { "A live sample is already being saved." }
        sampleOutput = getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: error("Could not open selected H.264 file")
        _state.value = _state.value.copy(message = "Saving raw video-only H.264 sample")
    }
    @Synchronized fun stopLiveSample() { runCatching { sampleOutput?.flush(); sampleOutput?.close() }; sampleOutput = null }
    fun viewRemoteJpeg(media: RemoteMedia) = viewModelScope.launch {
        if (!container.reservePhotoRead()) {
            _state.value = _state.value.copy(message = "Another media transfer is already active")
            return@launch
        }
        remotePhotoJob = coroutineContext[kotlinx.coroutines.Job]
        try {
            stopLive().join()
            stopPlayback()
            require(media.exactCameraPath.substringAfterLast('.').equals("jpg", ignoreCase = true)) { "Selected camera file is not a JPEG" }
            val client = container.currentClient() ?: error("Camera disconnected")
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val sink = BoundedImageSink(16 * 1024 * 1024)
                client.download(media, sink).collect {}
                android.graphics.BitmapFactory.decodeByteArray(sink.bytes(), 0, sink.size())
                    ?: error("Camera returned an unsupported image")
            }
            _state.value = _state.value.copy(viewedJpeg = bitmap)
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
        finally { container.releasePhotoRead(); remotePhotoJob = null }
    }
    fun clearViewedJpeg() { _state.value = _state.value.copy(viewedJpeg = null) }
    fun openLocalImage(uri: Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val bitmap = getApplication<Application>().contentResolver.openInputStream(uri)?.use(android.graphics.BitmapFactory::decodeStream)
                ?: error("Could not read this app-owned photo")
            _state.value = _state.value.copy(viewedJpeg = bitmap)
        } catch (t: Throwable) { _state.value = _state.value.copy(message = safeMessage(t)) }
    }
    fun shareDownload(uri: Uri) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "*/*"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        getApplication<Application>().startActivity(android.content.Intent.createChooser(send, "Share dashcam file").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun refreshHistory() = viewModelScope.launch { _state.value = _state.value.copy(history = container.preferences.history()) }
    fun onAppBackgrounded() = viewModelScope.launch {
        val photo = remotePhotoJob
        photo?.cancel()
        photo?.join()
        stopLive().join()
        stopPlayback()
        container.onBackground()
    }
    fun onAppForegrounded() { container.onForeground() }

    private fun safeMessage(error: Throwable): String = when (error) {
        is CameraReadException.WrongCamera -> "Unsupported device; A13 controls are disabled"
        is CameraReadException.Timeout -> "Camera request timed out; retry a read manually"
        is CameraReadException.DisconnectedNetwork -> "Camera network disconnected"
        else -> error.message?.take(180) ?: "Camera operation failed"
    }
    override fun onCleared() { stopLiveSample(); liveJob?.cancel(); player?.release(); super.onCleared() }
}

private class BoundedImageSink(private val maximumBytes: Int) : TransferSink {
    private val output = ByteArrayOutputStream()
    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(output.size() + length <= maximumBytes) { "JPEG exceeds the in-memory viewing limit" }
        output.write(bytes, offset, length)
    }
    override suspend fun commit() = Unit
    override suspend fun rollback() { output.reset() }
    fun bytes(): ByteArray = output.toByteArray()
    fun size(): Int = output.size()
}
