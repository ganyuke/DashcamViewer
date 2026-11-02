package org.nextcolor.dashcamviewer.android

import android.util.Base64
import org.nextcolor.dashcamviewer.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/** Video-only A13 RTSP receiver. The selected Network is used for both control and RTSP sockets. */
internal class NetworkBoundLiveSource(private val transport: CameraTransport) : LiveVideoSource {
    @Volatile private var socket: CameraSocket? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var baseUrl: String = "rtsp://${AndroidCameraTransport.CAMERA_HOST}:554/"
    @Volatile private var stopped = false
    private var cseq = 0

    override suspend fun collect(emit: suspend (VideoEvent) -> Unit) = withContext(Dispatchers.IO) {
        stopped = false
        val client = transport.openSocket()
        socket = client
        val reader = RtspReader(client)
        try {
            client.connect()
            request(reader, "OPTIONS", baseUrl)
            val (_, describeHeaders, sdpBytes) = request(reader, "DESCRIBE", baseUrl, "Accept: application/sdp\r\n")
            val sdp = sdpBytes.toString(Charsets.UTF_8)
            val videoSection = sdp.split(Regex("(?m)^m=")).firstOrNull { it.startsWith("video ") }
                ?: error("Camera did not advertise video")
            val pt = Regex("(?im)^a=rtpmap:(\\d+)\\s+H264/90000").find(videoSection)?.groupValues?.get(1)?.toIntOrNull()
                ?: error("Camera did not advertise H.264 at 90 kHz")
            val control = Regex("(?im)^a=control:([^\\r\\n]+)").find(videoSection)?.groupValues?.get(1)?.trim()
                ?: error("Video control track was missing")
            val contentBase = describeHeaders["content-base"] ?: baseUrl
            baseUrl = safeTrackUrl(contentBase, control)
            val (setupCode, setupHeaders, _) = request(reader, "SETUP", baseUrl, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
            check(setupCode == 200)
            sessionId = setupHeaders["session"]?.substringBefore(';') ?: error("RTSP session header missing")
            request(reader, "PLAY", baseUrl, "Range: npt=0.000-\r\n")
            val parameterSets = Regex("(?im)sprop-parameter-sets=([^;\\r\\n]+)").find(videoSection)?.groupValues?.get(1)
            parameterSets?.split(',')?.forEach { encoded ->
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val hasStartCode = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1))
                val annexB = if (hasStartCode) bytes else byteArrayOf(0, 0, 0, 1) + bytes
                emit(VideoEvent.Frame(annexB, 0))
            }
            val depacketizer = H264Depacketizer(pt)
            val timestamps = RtpTimestampClock()
            var heartbeatAt = System.nanoTime()
            var awaitingIdr = true
            while (!stopped) {
                val event = reader.nextEvent()
                if (event.channel != null) {
                    // The A13 declares RTP on 0-1 but sends the video interleaving on channel 2.
                    if (event.channel == 2) {
                        val packet = RtpParser.parse(event.bytes)
                        val nal = depacketizer.packet(event.bytes)
                        if (depacketizer.packetLossDetected) awaitingIdr = true
                        if (packet != null && nal != null) {
                            if (packet.payloadType == pt) {
                                if (hasIdr(nal)) awaitingIdr = false
                                if (!awaitingIdr) emit(VideoEvent.Frame(nal, timestamps.toMicroseconds(packet.timestamp)))
                            } else awaitingIdr = true
                        } else awaitingIdr = true
                    }
                } else if (event.code != 200) error("RTSP keepalive failed (${event.code})")
                if (System.nanoTime() - heartbeatAt >= 10_000_000_000L) {
                    request(reader, "OPTIONS", baseUrl)
                    checkRecording()
                    heartbeatAt = System.nanoTime()
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) { stopped = true; throw e }
            if (!stopped) emit(VideoEvent.Failure(e.message?.take(160) ?: "Live stream failed"))
        } finally { closeSocket(bestEffortTeardown = true) }
    }

    override suspend fun stop() {
        stopped = true
        closeSocket(bestEffortTeardown = true)
    }

    private suspend fun checkRecording() {
        val response = transport.get("/app/getparamvalue", listOf("param" to "rec"))
        if (response.status != 200) error("Recording status check failed")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
        val info = root["info"]?.jsonObject ?: error("Recording status unavailable")
        if (info["value"]?.jsonPrimitive?.intOrNull != 1) error("Camera recording stopped; live view ended")
    }

    private suspend fun request(reader: RtspReader, method: String, target: String, extra: String = ""): Triple<Int, Map<String, String>, ByteArray> {
        val c = ++cseq
        val session = sessionId?.let { "Session: $it\r\n" }.orEmpty()
        socket!!.write("$method $target RTSP/1.0\r\nCSeq: $c\r\nUser-Agent: DashcamViewer\r\n$session$extra\r\n".toByteArray())
        while (true) {
            val event = reader.nextEvent()
            if (event.channel != null) continue
            if (event.cseq == c) return Triple(event.code, event.headers, event.bytes)
        }
    }

    private suspend fun closeSocket(bestEffortTeardown: Boolean) {
        val current = socket ?: return
        socket = null
        try {
            if (bestEffortTeardown && sessionId != null) {
                val c = ++cseq
                current.write("TEARDOWN $baseUrl RTSP/1.0\r\nCSeq: $c\r\nSession: ${sessionId}\r\n\r\n".toByteArray())
            }
        } catch (_: Throwable) { }
        sessionId = null
        runCatching { current.close() }
    }

    private fun safeTrackUrl(contentBase: String, control: String): String {
        val base = URI(contentBase)
        val resolved = base.resolve(control)
        require(resolved.scheme == "rtsp" && resolved.host == AndroidCameraTransport.CAMERA_HOST && (resolved.port == -1 || resolved.port == 554)) { "Camera returned an unexpected RTSP control host" }
        return resolved.toString()
    }

    private fun hasIdr(bytes: ByteArray): Boolean {
        var i = 0
        while (i + 4 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                if ((bytes[i + 4].toInt() and 31) == 5) return true
                i += 5
            } else i++
        }
        return false
    }

    private data class RtspEvent(val channel: Int?, val bytes: ByteArray, val code: Int = 0, val cseq: Int = -1, val headers: Map<String, String> = emptyMap())
    private class RtspReader(private val socket: CameraSocket) {
        private val buffer = ByteArrayOutputStream()
        suspend fun nextEvent(): RtspEvent {
            val first = readExact(1)[0].toInt() and 255
            if (first == '$'.code) {
                val header = readExact(3)
                val channel = header[0].toInt() and 255
                val length = ((header[1].toInt() and 255) shl 8) or (header[2].toInt() and 255)
                require(length <= 65535)
                return RtspEvent(channel, readExact(length))
            }
            val statusLine = (byteArrayOf(first.toByte()) + readLine()).toString(Charsets.US_ASCII)
            require(statusLine.startsWith("RTSP/1.0 ")) { "Invalid RTSP response" }
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid RTSP response status")
            val headers = linkedMapOf<String, String>()
            var headerCount = 0
            while (headerCount++ < 100) {
                val line = readLine()
                if (line.isEmpty()) break
                val text = line.toString(Charsets.US_ASCII)
                val colon = text.indexOf(':')
                require(colon > 0) { "Malformed RTSP header" }
                headers[text.substring(0, colon).lowercase()] = text.substring(colon + 1).trim()
            }
            require(headerCount <= 101) { "Too many RTSP headers" }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..1_048_576) { "Oversized RTSP response" }
            return RtspEvent(null, readExact(length), code, headers["cseq"]?.toIntOrNull() ?: -1, headers)
        }
        private suspend fun readLine(): ByteArray {
            val out = ByteArrayOutputStream()
            while (out.size() < 8192) {
                val b = readExact(1)[0]
                if (b == '\n'.code.toByte()) return out.toByteArray().let { if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it }
                out.write(b.toInt())
            }
            error("RTSP line too long")
        }
        private suspend fun readExact(size: Int): ByteArray {
            while (buffer.size() < size) buffer.write(socket.readSome(5_000))
            val all = buffer.toByteArray()
            val result = all.copyOfRange(0, size)
            buffer.reset(); if (all.size > size) buffer.write(all, size, all.size - size)
            return result
        }
    }
}
