package org.nextcolor.dashcamviewer.protocol

data class RtpPacket(val payloadType: Int, val sequence: Int, val timestamp: Long, val marker: Boolean, val payload: ByteArray)

object RtpParser {
    fun parse(bytes: ByteArray): RtpPacket? {
        if (bytes.size < 12) return null
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        if (b0 ushr 6 != 2) return null
        val csrcCount = b0 and 0x0f
        var offset = 12 + 4 * csrcCount
        if (offset > bytes.size) return null
        if (b0 and 0x10 != 0) {
            if (bytes.size < offset + 4) return null
            val words = u16(bytes, offset + 2)
            offset += 4 + words * 4
            if (offset > bytes.size) return null
        }
        var end = bytes.size
        if (b0 and 0x20 != 0) {
            val padding = bytes.last().toInt() and 0xff
            if (padding == 0 || padding > end - offset) return null
            end -= padding
        }
        if (end <= offset) return null
        return RtpPacket(b1 and 0x7f, u16(bytes, 2), u32(bytes, 4), b1 and 0x80 != 0, bytes.copyOfRange(offset, end))
    }
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    private fun u32(b: ByteArray, i: Int): Long = ((b[i].toLong() and 255) shl 24) or ((b[i + 1].toLong() and 255) shl 16) or ((b[i + 2].toLong() and 255) shl 8) or (b[i + 3].toLong() and 255)
}

/** RFC 6184 single NAL, STAP-A and FU-A reconstruction with loss recovery. */
class H264Depacketizer(private val payloadType: Int) {
    private var lastSequence: Int? = null
    private var fragment: ByteArray? = null
    var packetLossDetected: Boolean = false
        private set
    fun packet(rawRtp: ByteArray): ByteArray? {
        packetLossDetected = false
        val rtp = RtpParser.parse(rawRtp) ?: run { fragment = null; return null }
        if (rtp.payloadType != payloadType) { fragment = null; return null }
        if (lastSequence != null && rtp.sequence != ((lastSequence!! + 1) and 0xffff)) { fragment = null; packetLossDetected = true }
        lastSequence = rtp.sequence
        val payload = rtp.payload
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 31) {
            in 1..23 -> START_CODE + payload
            24 -> stapA(payload)
            28 -> fuA(payload)
            else -> { fragment = null; null }
        }
    }
    private fun stapA(payload: ByteArray): ByteArray? {
        val out = ArrayList<Byte>()
        var p = 1
        while (p < payload.size) {
            if (p + 2 > payload.size) return null
            val size = ((payload[p].toInt() and 255) shl 8) or (payload[p + 1].toInt() and 255)
            p += 2
            if (size == 0 || p + size > payload.size) return null
            out.addAll(START_CODE.toList())
            out.addAll(payload.copyOfRange(p, p + size).toList())
            p += size
        }
        return out.toByteArray().takeIf { p == payload.size && out.isNotEmpty() }
    }
    private fun fuA(payload: ByteArray): ByteArray? {
        if (payload.size < 2) { fragment = null; return null }
        val header = payload[1].toInt() and 255
        if (header and 0x20 != 0) { fragment = null; return null }
        if (header and 0x80 != 0) {
            fragment = START_CODE + byteArrayOf(((payload[0].toInt() and 0xe0) or (header and 31)).toByte()) + payload.copyOfRange(2, payload.size)
        } else if (fragment != null) {
            fragment = fragment!! + payload.copyOfRange(2, payload.size)
            if (fragment!!.size > MAX_NAL_BYTES) { fragment = null; return null }
        } else return null
        if (header and 0x40 != 0) return fragment.also { fragment = null }
        return null
    }
    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val MAX_NAL_BYTES = 16 * 1024 * 1024
    }
}

class RtpTimestampClock {
    private var previous: Long? = null
    private var wraps = 0L
    fun toMicroseconds(timestamp: Long): Long {
        val value = timestamp and 0xffff_ffffL
        val prev = previous
        if (prev != null && prev - value > 0x8000_0000L) wraps++
        else if (prev != null && value - prev > 0x8000_0000L && wraps > 0) wraps--
        previous = value
        return ((wraps shl 32) + value) * 1_000_000L / 90_000L
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray = ByteArray(size + other.size).also { System.arraycopy(this, 0, it, 0, size); System.arraycopy(other, 0, it, size, other.size) }
