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
    fun movementFrameMatchesCapturedZhiyunJoystickLayout() {
        val packet = SmoothQ4Protocol.encodeRawMove(0x01, 0x0ED4, 0x32)
        assertEquals(14, packet.size)
        assertTrue(packet.contentEquals(
            byteArrayOf(
                0x24, 0x3c, 0x08, 0x00, 0x18, 0x12, 0x32, 0x01,
                0x01, 0x10, 0xD4.toByte(), 0x0E, 0x91.toByte(), 0x77
            )
        ))
        val checksum = SmoothQ4Protocol.crc16Xmodem(packet, 4, 8)
        assertEquals(checksum and 0xff, packet[12].toInt() and 0xff)
        assertEquals((checksum ushr 8) and 0xff, packet[13].toInt() and 0xff)
    }

    @Test
    fun joystickCenterIsNeutralAndBothSignsStayOnOppositeSidesOfCenter() {
        val center = SmoothQ4Protocol.encodeMove(SmoothQ4Protocol.TILT, 0f, 0)
        val negative = SmoothQ4Protocol.encodeMove(SmoothQ4Protocol.TILT, -1f, 1)
        val positive = SmoothQ4Protocol.encodeMove(SmoothQ4Protocol.TILT, 1f, 2)
        assertEquals(SmoothQ4Protocol.CENTER, unsignedShort(center, 10))
        assertTrue(unsignedShort(negative, 10) < SmoothQ4Protocol.CENTER)
        assertTrue(unsignedShort(positive, 10) > SmoothQ4Protocol.CENTER)
    }

    @Test
    fun axisCommandsMatchObservedSmooth4Behavior() {
        assertEquals(0x02, SmoothQ4Protocol.PAN)
        assertEquals(0x01, SmoothQ4Protocol.TILT)
    }

    @Test
    fun axisFramesAreSentInFirmwareCommandOrder() {
        val packets = SmoothQ4Protocol.encodeAxes(pan = 0.5f, tilt = -0.5f, firstSequence = 10)
        assertEquals(3, packets.size)
        assertEquals(0x01, packets[0][8].toInt() and 0xff)
        assertEquals(0x02, packets[1][8].toInt() and 0xff)
        assertEquals(0x03, packets[2][8].toInt() and 0xff)
        assertTrue(unsignedShort(packets[0], 10) < SmoothQ4Protocol.CENTER)
        assertTrue(unsignedShort(packets[1], 10) > SmoothQ4Protocol.CENTER)
        assertEquals(SmoothQ4Protocol.CENTER, unsignedShort(packets[2], 10))
    }

    @Test
    fun horizontalCommandCanBeSwitchedBetweenCurrentAndYawCandidate() {
        val current = SmoothQ4Protocol.encodeAxes(0.5f, 0f, 10, SmoothQ4Protocol.PAN)
        val yawCandidate = SmoothQ4Protocol.encodeAxes(0.5f, 0f, 10, SmoothQ4Protocol.PAN_YAW_CANDIDATE)
        assertEquals(0x02, current[1][8].toInt() and 0xff)
        assertEquals(0x02, yawCandidate[1][8].toInt() and 0xff)
        assertEquals(0x03, yawCandidate[2][8].toInt() and 0xff)
        assertTrue(unsignedShort(current[1], 10) > SmoothQ4Protocol.CENTER)
        assertEquals(SmoothQ4Protocol.CENTER, unsignedShort(current[2], 10))
        assertEquals(SmoothQ4Protocol.CENTER, unsignedShort(yawCandidate[1], 10))
        assertTrue(unsignedShort(yawCandidate[2], 10) > SmoothQ4Protocol.CENTER)
    }

    private fun unsignedShort(packet: ByteArray, offset: Int): Int =
        (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
}
