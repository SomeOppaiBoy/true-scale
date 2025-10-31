package com.example.truescale.ar

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import kotlin.math.max
import kotlin.math.min

class ArLightingManager {

    private var brightnessFactor: Float = 1.0f

    private var colorCorrection: FloatArray? = null

    fun update(frame: Frame) {
        try {
            val lightEstimate: LightEstimate = frame.lightEstimate
            if (lightEstimate.state != LightEstimate.State.VALID) {
                brightnessFactor = 1.0f
                colorCorrection = null
                return
            }
            val pixelIntensity = lightEstimate.pixelIntensity

            val normalized = (pixelIntensity / 1000f).coerceIn(0.1f, 2.0f)
            brightnessFactor = normalized

            val color = FloatArray(4)
            lightEstimate.getColorCorrection(color, 0)
            colorCorrection = color
        } catch (e: Exception) {
            Log.w("ArLightingManager", "Lighting update failed: ${e.message}")
            brightnessFactor = 1.0f
            colorCorrection = null
        }
    }

    fun getBrightnessFactor(): Float = brightnessFactor

    fun getColorCorrection(): FloatArray? = colorCorrection
}
