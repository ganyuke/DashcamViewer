package org.nextcolor.dashcamviewer.protocol

import org.junit.Assert.*
import org.junit.Test

class RtpH264Test {
    private fun rtp(seq: Int, ts: Long, payload: ByteArray, pt: Int = 97, extension: Boolean = false, padding: Int = 0): ByteArray {
        val headerSize = if (extension) 16 else 12
        val out = ByteArray(headerSize + payload.size + padding)
        out[0] = ((2 shl 6) or (if (extension) 0x10 else 0) or (if (padding > 0) 0x20 else 0)).toByte()
        out[1] = pt.toByte()
        out[2] = (seq ushr 8).toByte(); out[3] = seq.toByte()
        out[4] = (ts ushr 24).toByte(); out[5] = (ts ushr 16).toByte(); out[6] = (ts ushr 8).toByte(); out[7] = ts.toByte()
        if (extension) { out[12] = 0xbe.toByte(); out[13] = 0xde.toByte(); out[14] = 0; out[15] = 0 }
        payload.copyInto(out, headerSize)
        if (padding > 0) out[out.lastIndex] = padding.toByte()
        return out
    }
    private val prefix = byteArrayOf(0, 0, 0, 1)

    @Test fun singleNalAndWrongPayloadType() {
        val decoder = H264Depacketizer(97)
        assertArrayEquals(prefix + byteArrayOf(0x65, 1, 2), decoder.packet(rtp(1, 20, byteArrayOf(0x65, 1, 2))))
        assertNull(decoder.packet(rtp(2, 20, byteArrayOf(0x65), pt = 96)))
    }

    @Test fun stapAUnpacksCompleteUnitsAndRejectsMalformedLengths() {
        val decoder = H264Depacketizer(97)
        val stap = byteArrayOf(24, 0, 2, 0x67, 1, 0, 2, 0x68, 2)
        assertArrayEquals(prefix + byteArrayOf(0x67, 1) + prefix + byteArrayOf(0x68, 2), decoder.packet(rtp(1, 20, stap)))
        assertNull(decoder.packet(rtp(2, 20, byteArrayOf(24, 0, 5, 0x67))))
    }

    @Test fun fuAReassemblesAndSequenceLossDropsBrokenNal() {
        val decoder = H264Depacketizer(97)
        assertNull(decoder.packet(rtp(10, 30, byteArrayOf(0x7c, 0x85.toByte(), 1))))
        assertArrayEquals(prefix + byteArrayOf(0x65, 1, 2), decoder.packet(rtp(11, 30, byteArrayOf(0x7c, 0x45, 2))))
        assertNull(decoder.packet(rtp(12, 40, byteArrayOf(0x7c, 0x85.toByte(), 1))))
        assertNull(decoder.packet(rtp(14, 40, byteArrayOf(0x7c, 0x45, 2))))
        assertTrue(decoder.packetLossDetected)
    }

    @Test fun sequenceTimestampWrapExtensionsPaddingAndMalformedRtp() {
        val decoder = H264Depacketizer(97)
        assertNotNull(decoder.packet(rtp(65535, 0xfffffff0L, byteArrayOf(0x65, 1), extension = true, padding = 4)))
        assertNotNull(decoder.packet(rtp(0, 0x10, byteArrayOf(0x65, 2))))
        assertFalse(decoder.packetLossDetected)
        val timestamps = RtpTimestampClock()
        val a = timestamps.toMicroseconds(0xfffffff0L)
        val b = timestamps.toMicroseconds(0x10)
        assertTrue(b > a)
        assertNull(RtpParser.parse(byteArrayOf(0, 1, 2)))
        assertNull(RtpParser.parse(rtp(1, 1, byteArrayOf(0x65)).copyOfRange(0, 11)))
        assertNull(RtpParser.parse(rtp(2, 1, byteArrayOf(0x65), padding = 0).also { it[0] = (it[0].toInt() or 0x20).toByte() }))
        val oversizedExtension = rtp(3, 1, byteArrayOf(0x65), extension = true).also { it[14] = 0x7f; it[15] = 0xff.toByte() }
        assertNull(RtpParser.parse(oversizedExtension))
        val oversizedPadding = rtp(4, 1, byteArrayOf(0x65), padding = 2).also { it[it.lastIndex] = 20 }
        assertNull(RtpParser.parse(oversizedPadding))
        val wrongVersion = rtp(5, 1, byteArrayOf(0x65)).also { it[0] = 0x40 }
        assertNull(RtpParser.parse(wrongVersion))
    }
}
