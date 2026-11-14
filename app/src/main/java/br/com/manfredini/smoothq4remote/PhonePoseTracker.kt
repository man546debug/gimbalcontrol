package br.com.manfredini.smoothq4remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Experimental pose estimate from the phone IMU.
 * Yaw uses the Android rotation vector (magnetic heading); pitch is gravity referenced.
 */
class PhonePoseTracker(context: Context) : SensorEventListener {
    data class Pose(val yawDegrees: Float, val pitchDegrees: Float)

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    var currentPose: Pose? = null
        private set

    val isAvailable: Boolean
        get() = rotationVector != null

    fun start() {
        rotationVector?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // The rear camera looks along the phone's -Z axis. Convert that optical
        // direction into Android's east/north/up world frame so the estimate
        // remains meaningful while the phone is mounted upright on the gimbal.
        val forwardEast = -matrix[2]
        val forwardNorth = -matrix[5]
        val forwardUp = -matrix[8]
        val horizontalMagnitude = hypot(forwardEast, forwardNorth)
        val radiansToDegrees = (180.0 / PI).toFloat()
        currentPose = Pose(
            yawDegrees = atan2(forwardEast, forwardNorth) * radiansToDegrees,
            pitchDegrees = atan2(forwardUp, horizontalMagnitude) * radiansToDegrees
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
