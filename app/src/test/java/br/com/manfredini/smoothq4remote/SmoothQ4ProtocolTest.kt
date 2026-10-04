package br.com.manfredini.smoothq4remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothQ4ProtocolTest {
    @Test
    fun crc16XmodemMatchesStandardCheckValue() {
        assertEquals(0x31C3, SmoothQ4Protocol.crc16Xmodem("123456789".encodeToByteArray(), 0, 9))
    }

    @Test
    fun movementFrameHasExpectedHeaderAndChecksumForItsPayload() {
        val packet = SmoothQ4Protocol.encodeRawMove(0x02, 0x0010, 8, 1)
        assertEquals(14, packet.size)
        assertTrue(packet.copyOfRange(0, 4).contentEquals(byteArrayOf(0x24, 0x3c, 0x08, 0x00)))
        val checksum = SmoothQ4Protocol.crc16Xmodem(packet, 4, 8)
        assertEquals(checksum and 0xff, packet[12].toInt() and 0xff)
        assertEquals((checksum ushr 8) and 0xff, packet[13].toInt() and 0xff)
    }
}
