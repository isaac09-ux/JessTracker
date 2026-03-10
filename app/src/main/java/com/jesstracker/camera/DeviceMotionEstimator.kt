package com.jesstracker.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * DeviceMotionEstimator — lee sensores del telefono para cuantificar
 * el movimiento real del dispositivo (mano, vibracion, paneos bruscos).
 */
class DeviceMotionEstimator(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    private var smoothedAngularSpeed: Float = 0f

    fun start() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothedAngularSpeed = 0f
    }

    /**
     * Retorna velocidad angular suavizada (rad/s).
     */
    fun angularSpeed(): Float = smoothedAngularSpeed

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val x = event.values.getOrNull(0) ?: 0f
        val y = event.values.getOrNull(1) ?: 0f
        val z = event.values.getOrNull(2) ?: 0f

        val instantaneousSpeed = sqrt(x * x + y * y + z * z)
        smoothedAngularSpeed = smoothedAngularSpeed * 0.82f + instantaneousSpeed * 0.18f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
