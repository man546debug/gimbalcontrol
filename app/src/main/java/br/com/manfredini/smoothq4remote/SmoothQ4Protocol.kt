package br.com.manfredini.smoothq4remote

import kotlin.math.roundToInt

/**
 * Experimental Zhiyun motion frame encoder.
 *
 * The UUIDs were found in the supplied ZY Play APK. The frame layout matches
 * captured Smooth 4 joystick frames: command, 0x10 mode byte,
 * 16-bit centered axis value, then CRC-XMODEM. Axis IDs follow the observed
 * Smooth 4 behavior on the user's gimbal.
 */
object SmoothQ4Protocol {
    val writeCharacteristic = java.util.UUID.fromString("d44bc439-abfd-45a2-b575-925416129600")
    val notifyCharacteristic = java.util.UUID.fromString("d44bc439-abfd-45a2-b575-925416129601")

    // Confirmed by the user's Smooth 4 test: pitch=0x01, roll=0x02, yaw/pan=0x03.
    const val PAN = 0x03
    const val ROLL = 0x02
    const val TILT = 0x01
    const val CENTER = 2048
    private const val AXIS_RANGE = 1748
    private const val AXIS_MODE = 0x10

    fun encodeMove(command: Int, normalized: Float, sequence: Int): ByteArray {
        require(command in 0x01..0x03)
        val bounded = normalized.coerceIn(-1f, 1f)
        val value = (CENTER + bounded * AXIS_RANGE).roundToInt().coerceIn(0, 4095)
        return encodeRawMove(command, value, sequence)
    }

    /**
     * Send all three axis frames in command order: pitch, roll, yaw.
     * The tested Smooth 4 uses yaw (0x03) for horizontal pan.
     */
    fun encodeAxes(
        pan: Float,
        tilt: Float,
        firstSequence: Int
    ): List<ByteArray> {
        return listOf(
            encodeMove(TILT, tilt, firstSequence),
            encodeMove(ROLL, 0f, firstSequence + 1),
            encodeMove(PAN, pan, firstSequence + 2)
        )
    }

    internal fun encodeRawMove(command: Int, value: Int, sequence: Int): ByteArray {
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
        packet[9] = AXIS_MODE.toByte()
        packet[10] = (value and 0xff).toByte()
        packet[11] = ((value ushr 8) and 0xff).toByte()
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
