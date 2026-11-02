package org.nextcolor.dashcamviewer

import android.content.Context
import android.net.Network
import org.nextcolor.dashcamviewer.android.*
import org.nextcolor.dashcamviewer.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadUiState(val active: Boolean = false, val name: String? = null, val bytes: Long = 0, val expected: Long? = null, val message: String? = null)

/** One application-owned dependency container. */
class AppContainer(
    private val context: Context,
    val networks: CameraNetworkController,
    val preferences: PrivatePreferences,
) {
    @Volatile private var cameraClient: A13Client? = null
    @Volatile private var cameraIdentity: CameraIdentity? = null
    @Volatile private var cameraGeneration = 0L
    @Volatile private var recording = false
    @Volatile private var liveActive = false
    @Volatile private var remotePlaybackActive = false
    @Volatile private var downloadActive = false
    @Volatile private var photoReadActive = false
    @Volatile var appBackgrounded: Boolean = false
    private val transferGuard = AtomicBoolean(false)
    private val _downloadState = MutableStateFlow(DownloadUiState())
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()
    private var cancelTransfer: (() -> Unit)? = null
    @Volatile private var liveDecoder = LiveH264Decoder()
    @Volatile private var selectedDownload: RemoteMedia? = null

    suspend fun identify(network: Network): CameraSnapshot {
        cameraGeneration = networks.state.value.generation
        cameraIdentity = null
        cameraClient = A13ClientFactory.create(network, session = { currentSession() }, onSessionInvalidated = ::invalidateCameraSession)
        val snapshot = cameraClient!!.readSnapshot()
        cameraIdentity = snapshot.identity
        recording = snapshot.recording == true
        networks.markIdentified()
        return snapshot
    }
    fun currentClient(): A13Client? = cameraClient
    fun setSnapshot(snapshot: CameraSnapshot) { cameraIdentity = snapshot.identity; recording = snapshot.recording == true }
    fun currentSession(): CameraSessionState? = cameraIdentity?.let { CameraSessionState(it, cameraGeneration, recording, liveActive, remotePlaybackActive, downloadActive || photoReadActive) }
    fun setLiveActive(active: Boolean) { liveActive = active }
    fun setRemotePlaybackActive(active: Boolean) { remotePlaybackActive = active }
    fun decoder(): LiveH264Decoder = liveDecoder
    fun clearCameraSession() {
        cameraClient = null; cameraIdentity = null; recording = false; liveActive = false; remotePlaybackActive = false
        cameraGeneration = networks.state.value.generation + 1
        liveDecoder.close(); liveDecoder = LiveH264Decoder()
    }
    private fun invalidateCameraSession() {
        cameraClient = null; cameraIdentity = null; recording = false; liveActive = false; remotePlaybackActive = false
        cameraGeneration = networks.state.value.generation + 1
        liveDecoder.close(); liveDecoder = LiveH264Decoder()
        networks.markSessionCleared()
    }

    suspend fun startDownload(media: RemoteMedia) {
        check(transferGuard.get()) { "Download was not reserved." }
        val client = cameraClient ?: run { finishDownload("Connect to the camera first"); error("Connect to the camera first") }
        val cancelJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        cancelTransfer = { cancelJob?.cancel(CancellationException("Download cancelled by user")) }
        val sink = try {
            MediaStoreTransferSink(context, media.exactCameraPath.substringAfterLast('/'), null,
                onPendingUri = { preferences.journalPendingUri(it) },
                onPublished = { preferences.appendHistory(it); preferences.clearJournalUri(it.uri) })
        } catch (t: Throwable) { finishDownload("Could not start download"); throw t }
        downloadActive = true
        _downloadState.value = DownloadUiState(true, media.exactCameraPath.substringAfterLast('/'))
        try {
            client.download(media, sink).collect { event ->
                _downloadState.value = DownloadUiState(true, media.exactCameraPath.substringAfterLast('/'), event.bytesTransferred, event.expectedBytes)
            }
            _downloadState.value = DownloadUiState(message = "Download completed")
        } catch (t: Throwable) {
            _downloadState.value = DownloadUiState(message = if (t is CancellationException) "Download cancelled" else "Download failed: ${t.message ?: "transfer error"}")
            throw t
        } finally {
            finishDownload(null)
            networks.releaseOwnedWhenIdle(downloadServiceOwnsLease = false, appBackgrounded = appBackgrounded)
        }
    }
    fun reserveDownload(media: RemoteMedia): Boolean {
        if (!transferGuard.compareAndSet(false, true)) return false
        selectedDownload = media
        downloadActive = true
        _downloadState.value = DownloadUiState(true, media.exactCameraPath.substringAfterLast('/'))
        return true
    }
    fun reservePhotoRead(): Boolean {
        if (!transferGuard.compareAndSet(false, true)) return false
        photoReadActive = true
        return true
    }
    fun releasePhotoRead() {
        if (photoReadActive) {
            photoReadActive = false
            if (cancelTransfer == null) finishDownload(null)
        }
    }
    fun abandonDownloadReservation(message: String = "Download cancelled") {
        if (transferGuard.get() && cancelTransfer == null) finishDownload(message)
    }
    private fun finishDownload(message: String?) {
        selectedDownload = null
        downloadActive = false
        photoReadActive = false
        transferGuard.set(false)
        cancelTransfer = null
        _downloadState.value = DownloadUiState(message = message)
    }
    fun cancelDownload() {
        val cancel = cancelTransfer
        if (cancel != null) cancel() else abandonDownloadReservation()
    }
    fun takeSelectedDownload(path: String): RemoteMedia? = selectedDownload?.takeIf { it.exactCameraPath == path }.also { selectedDownload = null }
    fun hasActiveDownload() = transferGuard.get()
    suspend fun releaseOwnedNetworkIfIdle() = networks.releaseOwnedWhenIdle(downloadServiceOwnsLease = false, appBackgrounded = appBackgrounded)
    suspend fun onBackground() {
        appBackgrounded = true
        if (!downloadActive) {
            liveActive = false; remotePlaybackActive = false; liveDecoder.close(); liveDecoder = LiveH264Decoder()
            networks.releaseOwnedWhenIdle(false, true)
        }
    }
    fun onForeground() { appBackgrounded = false }
}
