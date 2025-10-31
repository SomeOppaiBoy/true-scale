package com.example.truescale.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.atan2
import kotlin.math.roundToInt

class CompassManager(context: Context, private val smoothingAlpha: Float = 0.05f) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelReadings = FloatArray(3)
    private val magnetReadings = FloatArray(3)

    private var smoothedHeading = 0f
    private var hasSmoothed = false

    private val _headingFlow = MutableSharedFlow<Float>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val headingFlow = _headingFlow.asSharedFlow()

    fun start() {
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnet?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelReadings, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetReadings, 0, 3)
            }
            else -> return
        }

        val rotationMatrix = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelReadings, magnetReadings)
        if (!success) return

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthRad = orientation[0]
        var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        if (azimuthDeg < 0) azimuthDeg += 360f

        val heading = azimuthDeg
        applySmoothing(heading)
    }

    private fun applySmoothing(newHeading: Float) {
        if (!hasSmoothed) {
            smoothedHeading = newHeading
            hasSmoothed = true
        } else {
            var diff = newHeading - smoothedHeading
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            smoothedHeading = (smoothedHeading + smoothingAlpha * diff + 360f) % 360f
        }
        val rounded = (smoothedHeading * 10).roundToInt() / 10f
        _headingFlow.tryEmit(rounded)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    companion object {
        fun headingToCardinal(deg: Float): String {
            return when (((deg / 22.5) + 0.5).toInt()) {
                0 -> "N"
                1 -> "NNE"
                2 -> "NE"
                3 -> "ENE"
                4 -> "E"
                5 -> "ESE"
                6 -> "SE"
                7 -> "SSE"
                8 -> "S"
                9 -> "SSW"
                10 -> "SW"
                11 -> "WSW"
                12 -> "W"
                13 -> "WNW"
                14 -> "NW"
                15 -> "NNW"
                else -> "N"
            }
        }
    }
}
