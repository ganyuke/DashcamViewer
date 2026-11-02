package org.nextcolor.dashcamviewer.android

import android.net.Network
import android.net.Uri
import androidx.media3.datasource.DataSource
import org.nextcolor.dashcamviewer.protocol.A13Client
import org.nextcolor.dashcamviewer.protocol.A13ProtocolClient
import org.nextcolor.dashcamviewer.protocol.CameraSessionState
import org.nextcolor.dashcamviewer.protocol.RemoteMedia

/** Fixed network destination for the supported A13 profile. */
object A13CameraAddress {
    const val HOST = "192.168.169.1"
}

/** Android adapter entry point. The concrete transport and socket remain module-private. */
object A13ClientFactory {
    fun create(
        network: Network,
        session: () -> CameraSessionState?,
        onSessionInvalidated: () -> Unit,
    ): A13Client {
        val transport = AndroidCameraTransport(network)
        return A13ProtocolClient(
            transport,
            session = session,
            liveSource = NetworkBoundLiveSource(transport),
            onSessionInvalidated = onSessionInvalidated,
        )
    }
}

/** Creates a range-capable Media3 source for one item issued by this session's file listing. */
object A13MediaPlayback {
    fun uri(media: RemoteMedia): Uri = Uri.Builder()
        .scheme("http")
        .authority(A13CameraAddress.HOST)
        .path(media.exactCameraPath)
        .build()

    fun dataSourceFactory(network: Network, media: RemoteMedia): DataSource.Factory =
        BoundHttpDataSource.Factory(network, uri(media))
}
