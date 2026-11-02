package org.nextcolor.dashcamviewer.android

import android.net.Network
import org.nextcolor.dashcamviewer.protocol.CameraSocket
import org.nextcolor.dashcamviewer.protocol.CameraTransport
import org.nextcolor.dashcamviewer.protocol.HttpReply
import org.nextcolor.dashcamviewer.protocol.TransferSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Every call is scoped to one selected Android Network and fixed A13 host. */
internal class AndroidCameraTransport(private val network: Network) : CameraTransport {
    private val base = HttpUrl.Builder().scheme("http").host(CAMERA_HOST).port(80).build()
    private fun client(readTimeout: Long) = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(readTimeout, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun get(path: String, query: List<Pair<String, String>>): HttpReply = withContext(Dispatchers.IO) {
        require(path.startsWith("/app/") && path.removePrefix("/app/").substringBefore('/') in API_PATHS) { "Unsupported camera path." }
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply { query.forEach { addQueryParameter(it.first, it.second) } }.build()
        val request = Request.Builder().url(url).get().build()
        client(15).newCall(request).execute().use { response ->
            val body = response.body
            if (body != null && body.contentLength() > 1_048_576) throw IllegalStateException("Camera control response exceeded 1 MiB")
            val bytes = body?.source()?.let { source ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (out.size() + n > 1_048_576) throw IllegalStateException("Camera control response exceeded 1 MiB")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            HttpReply(response.code, response.headers.toMultimap().mapValues { it.value.joinToString(",") }, bytes)
        }
    }

    override suspend fun stream(path: String, sink: TransferSink, onBytes: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        require(path.startsWith("/mnt/card/") && path.none { it.code < 0x20 || it == '\\' } && '?' !in path && '#' !in path)
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).build()
        val request = Request.Builder().url(url).get().build()
        client(30).newCall(request).execute().use { response ->
            if (response.code != 200) throw IllegalStateException("Camera download failed (${response.code}).")
            val expected = response.header("Content-Length")?.toLongOrNull()
            val body = response.body ?: throw IllegalStateException("Camera returned no file body.")
            val source = body.source()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                sink.write(buffer, 0, count)
                total += count
                onBytes(total, expected)
            }
            if (expected != null && expected != total) throw IllegalStateException("Downloaded byte count did not match Content-Length.")
        }
    }

    override fun openSocket(): CameraSocket {
        val host = CAMERA_HOST
        val port = 554
        val socket = network.socketFactory.createSocket()
        network.bindSocket(socket)
        return AndroidBoundSocket(socket, host, port)
    }

    private class AndroidBoundSocket(private val socket: Socket, private val host: String, private val port: Int) : CameraSocket {
        override suspend fun connect() = withContext(Dispatchers.IO) {
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 5_000
        }
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { socket.getOutputStream().write(bytes); socket.getOutputStream().flush() }
        override suspend fun readSome(timeoutMillis: Int): ByteArray = withContext(Dispatchers.IO) {
            socket.soTimeout = minOf(timeoutMillis, 5_000)
            val buffer = ByteArray(64 * 1024)
            val n = socket.getInputStream().read(buffer)
            if (n < 0) throw IllegalStateException("Camera closed live stream")
            buffer.copyOf(n)
        }
        override suspend fun close() = withContext(Dispatchers.IO) { socket.close() }
    }

    companion object {
        const val CAMERA_HOST = A13CameraAddress.HOST
        private val API_PATHS = setOf("getdeviceattr", "getproductinfo", "getsdinfo", "getbatteryinfo", "getrecduration", "getlockvideostatus", "getparamitems", "getparamvalue", "capability", "getfilelist", "setparamvalue", "snapshot", "lockvideo", "enterrecorder", "getmediainfo", "exitrecorder", "setsystime", "setwifi", "wifireboot", "deletefile", "sdformat", "reset")
    }
}
