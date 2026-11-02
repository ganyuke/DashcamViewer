package org.nextcolor.dashcamviewer.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.nextcolor.dashcamviewer.protocol.VideoEvent

/** Minimal video-only AVC decoder targeting the verified A13 720p live feed. */
class LiveH264Decoder : AutoCloseable {
    @Volatile private var surface: Surface? = null
    @Volatile
    var renderedFrames: Long = 0
        private set

    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun attachSurface(value: Surface?) {
        if (surface === value) return
        surface = value
        resetCodec()
        tryStart()
    }

    @Synchronized
    fun feed(event: VideoEvent.Frame) {
        if (closed.get()) return
        splitNals(event.bytes).forEach { nal ->
            when (nal.firstOrNull()?.toInt()?.and(31)) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        tryStart()
        val decoder = codec ?: return
        val input = decoder.dequeueInputBuffer(5_000)
        if (input >= 0) {
            val buffer = decoder.getInputBuffer(input) ?: return
            buffer.clear()
            if (event.bytes.size > buffer.remaining()) return
            buffer.put(event.bytes)
            val keyframe = splitNals(event.bytes).any { (it.firstOrNull()?.toInt()?.and(31)) == 5 }
            decoder.queueInputBuffer(
                input,
                0,
                event.bytes.size,
                event.presentationTimeUs,
                if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }
        val info = MediaCodec.BufferInfo()
        var output = decoder.dequeueOutputBuffer(info, 0)
        while (output >= 0) {
            decoder.releaseOutputBuffer(output, true)
            renderedFrames++
            output = decoder.dequeueOutputBuffer(info, 0)
        }
    }

    @Synchronized
    private fun tryStart() {
        if (codec != null || surface == null || sps == null || pps == null) return
        val decoder = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps!!))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps!!))
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
    }

    @Synchronized
    private fun resetCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        renderedFrames = 0
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) resetCodec()
    }

    private fun splitNals(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < bytes.size) {
            if (
                bytes[i] == 0.toByte() &&
                    bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 0.toByte() &&
                    bytes[i + 3] == 1.toByte()
            ) {
                starts += i
                i += 4
            } else if (
                i + 2 < bytes.size &&
                    bytes[i] == 0.toByte() &&
                    bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 1.toByte()
            ) {
                starts += i
                i += 3
            } else i++
        }
        if (starts.isEmpty()) return listOf(bytes)
        return starts.indices.mapNotNull { index ->
            val begin = starts[index] + if (bytes[starts[index] + 2] == 0.toByte()) 4 else 3
            val end = starts.getOrNull(index + 1) ?: bytes.size
            if (end > begin) bytes.copyOfRange(begin, end) else null
        }
    }
}
