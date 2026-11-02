package org.nextcolor.dashcamviewer.protocol

import kotlinx.serialization.Serializable

@Serializable data class CameraIdentity(
    val uuid: String,
    val manufacturer: String,
    val soc: String,
    val firmware: String,
    val hardware: String,
)

@Serializable data class SettingDescriptor(
    val key: String,
    val optionLabels: List<String>,
    val optionValues: List<Int>,
    val rawCurrentValue: Int?,
) {
    init { require(optionLabels.size == optionValues.size) }
    fun labelFor(value: Int): String? = optionValues.indexOf(value).takeIf { it >= 0 }?.let(optionLabels::get)
}

@Serializable data class CameraSnapshot(
    val identity: CameraIdentity,
    val rawCapability: String?,
    val advertisedSettings: List<SettingDescriptor>,
    val currentSettingValues: Map<String, Int>,
    val recording: Boolean?,
    val sdStatus: Map<String, String>,
    val batteryValue: String?,
    val currentLockStatus: Boolean?,
)

@Serializable enum class MediaFolder { LOOP, EVENT, EMR, PARK, RACE }
class RemoteMedia internal constructor(
    val cameraIdentity: CameraIdentity,
    val exactCameraPath: String,
    val folder: MediaFolder,
    val rawMetadata: Map<String, String>,
    val reportedSizeKiB: Long?,
)
data class MediaPage(val items: List<RemoteMedia>, val start: Int, val limit: Int)

sealed interface FeatureSupport {
    data class Advertised(val evidence: EvidenceLevel) : FeatureSupport
    data class Unavailable(val reason: String) : FeatureSupport
    data object Unknown : FeatureSupport
}
enum class EvidenceLevel { VerifiedOnA13, AdvertisedOnly, Experimental }
enum class CommandOutcome { Confirmed, AcceptedUnverified, AlreadyDesired, Rejected, Unconfirmed }

sealed class CameraReadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HttpFailure(val status: Int) : CameraReadException("Camera HTTP request failed ($status)")
    data object UnsupportedResponse : CameraReadException("This camera does not support the requested response")
    class MalformedResponse(cause: Throwable? = null) : CameraReadException("Camera returned a malformed response", cause)
    data object Timeout : CameraReadException("Camera request timed out")
    data object DisconnectedNetwork : CameraReadException("Camera network is disconnected")
    data object WrongCamera : CameraReadException("Connected device does not match the A13 profile")
}

data class TransferEvent(val bytesTransferred: Long, val expectedBytes: Long?, val complete: Boolean = false)
sealed interface VideoEvent {
    data class Frame(val bytes: ByteArray, val presentationTimeUs: Long) : VideoEvent
    data class Failure(val reason: String) : VideoEvent
}
interface TransferSink {
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    suspend fun commit()
    suspend fun rollback()
}

sealed interface CameraCommand {
    data class SetSetting(val key: String, val value: Int) : CameraCommand
    data object Snapshot : CameraCommand
    data object StartRecording : CameraCommand
    data object StopRecording : CameraCommand
    data object LockCurrentRecording : CameraCommand
    data object StartLive : CameraCommand
    data object StopLive : CameraCommand
    data class SwitchCamera(val value: Int) : CameraCommand
    data class SetParking(val open: Boolean) : CameraCommand
    data class SetClock(val date: String, val zoneId: String) : CameraCommand
    data class SetWifiName(val ssid: String) : CameraCommand
    data class SetWifiPassword(val password: String, val confirmation: String) : CameraCommand
    data object RestartWifi : CameraCommand
    data class DeleteOneFile(val media: RemoteMedia, val typedFilename: String) : CameraCommand
    data class FormatCard(val typedPhrase: String) : CameraCommand
    data class FactoryReset(val typedPhrase: String) : CameraCommand
}

enum class ConfirmationKind {
    TAP, STOP_RECORDING, LOCK_CURRENT, SWITCH_CAMERA, PARKING, EXPERIMENTAL,
    EXPERIMENTAL_TYPED_FILENAME, ERASE_ALL, RESET_CAMERA
}
data class ActionConfirmation(
    val acknowledged: Boolean,
    val phrase: String? = null,
    val exactDisplayedValue: String? = null,
)
class PreparedAction internal constructor(
    val cameraUuid: String,
    val connectionGeneration: Long,
    val command: CameraCommand,
    val relevantCurrentValue: String?,
    val requiredConfirmation: ConfirmationKind,
    val expiresAtEpochMillis: Long,
    internal val nonce: String,
)

data class CameraSessionState(
    val identity: CameraIdentity,
    val generation: Long,
    val recording: Boolean,
    val liveActive: Boolean,
    val remotePlaybackActive: Boolean,
    val downloadActive: Boolean,
)
