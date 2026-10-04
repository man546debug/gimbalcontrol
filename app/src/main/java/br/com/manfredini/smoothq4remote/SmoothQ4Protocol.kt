package br.com.manfredini.smoothq4remote

import kotlin.math.roundToInt

/**
 * Experimental BTE motion frame encoder.
 *
 * The UUIDs were found in the supplied ZY Play APK. The frame layout below is
 * documented by an independent reverse-engineering project for another Zhiyun
 * gimbal. Smooth Q4 compatibility still needs confirmation on hardware.
 */
object SmoothQ4Protocol {
    val writeCharacteristic = java.util.UUID.fromString("d44bc439-abfd-45a2-b575-925416129600")
    val notifyCharacteristic = java.util.UUID.fromString("d44bc439-abfd-45a2-b575-925416129601")

    const val PAN = 0x01
    const val TILT = 0x02
    const val CENTER = 2048

    fun encodeMove(command: Int, normalized: Float, speed: Int, sequence: Int): ByteArray {
        require(command in 0x01..0x03)
        val bounded = normalized.coerceIn(-1f, 1f)
        val value = (CENTER + bounded * 1500f).roundToInt().coerceIn(0, 4095)
        return encodeRawMove(command, value, speed, sequence)
    }

    internal fun encodeRawMove(command: Int, value: Int, speed: Int, sequence: Int): ByteArray {
        val payload = byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            speed.coerceIn(1, 255).toByte()
        )
        val packet = ByteArray(14)
        packet[0] = 0x24
        packet[1] = 0x3c
        packet[2] = 0x08
        packet[3] = 0x00
        packet[4] = 0x18
        packet[5] = 0x12
        packet[6] = sequence.toByte()
        packet[7] = 0x01
        packet[8] = command.toByte()
        payload.copyInto(packet, destinationOffset = 9, endIndex = 3)
        val crc = crc16Xmodem(packet, 4, 8)
        packet[12] = (crc and 0xff).toByte()
        packet[13] = ((crc ushr 8) and 0xff).toByte()
        return packet
    }

    internal fun crc16Xmodem(bytes: ByteArray, start: Int, length: Int): Int {
        var crc = 0
        for (index in start until start + length) {
            crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }
}
