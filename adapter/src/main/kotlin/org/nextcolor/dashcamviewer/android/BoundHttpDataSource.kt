package org.nextcolor.dashcamviewer.android

import android.net.Network
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Media3 HTTP source that routes every request and byte range through the selected camera Network. */
internal class BoundHttpDataSource(private val network: Network, private val allowedUri: Uri) : BaseDataSource(true), HttpDataSource {
    private val client = OkHttpClient.Builder().socketFactory(network.socketFactory).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var response: okhttp3.Response? = null
    private var source: okio.BufferedSource? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders
    override fun getResponseCode(): Int = response?.code ?: -1

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val target = dataSpec.uri
        require(target == allowedUri && target.host == A13CameraAddress.HOST && target.scheme == "http" && target.path?.startsWith("/mnt/card/") == true) { "Remote media must be the selected A13 listing item." }
        val builder = Request.Builder().url(target.toString()).get()
        if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else "${dataSpec.position + dataSpec.length - 1}"
            builder.header("Range", "bytes=${dataSpec.position}-$end")
        }
        val requestedRange = dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()
        response = client.newCall(builder.build()).execute()
        val r = response!!
        if (r.code !in setOf(200, 206)) throw HttpDataSource.InvalidResponseCodeException(r.code, r.message, null, r.headers.toMultimap(), dataSpec, ByteArray(0))
        if (requestedRange && r.code != 206) throw HttpDataSource.InvalidResponseCodeException(r.code, "Camera did not honor media range", null, r.headers.toMultimap(), dataSpec, ByteArray(0))
        source = r.body?.source() ?: throw HttpDataSource.HttpDataSourceException("Camera returned no media body", dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        uri = target
        responseHeaders = r.headers.toMultimap()
        remaining = r.body?.contentLength() ?: C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = source?.read(buffer, offset, length) ?: -1
        if (n < 0) { remaining = 0; return C.RESULT_END_OF_INPUT }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }
    override fun close() {
        uri = null
        source = null
        response?.close(); response = null
        if (opened) { opened = false; transferEnded() }
    }
    override fun setRequestProperty(name: String, value: String) { throw UnsupportedOperationException("Arbitrary remote headers are disabled") }
    override fun clearRequestProperty(name: String) = Unit
    override fun clearAllRequestProperties() = Unit

    internal class Factory(private val network: Network, private val allowedUri: Uri) : DataSource.Factory {
        override fun createDataSource(): DataSource = BoundHttpDataSource(network, allowedUri)
    }
}
