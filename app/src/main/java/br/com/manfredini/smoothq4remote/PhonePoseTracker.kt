package br.com.manfredini.smoothq4remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

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
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        val radiansToDegrees = (180.0 / PI).toFloat()
        currentPose = Pose(
            yawDegrees = orientation[0] * radiansToDegrees,
            pitchDegrees = orientation[1] * radiansToDegrees
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
