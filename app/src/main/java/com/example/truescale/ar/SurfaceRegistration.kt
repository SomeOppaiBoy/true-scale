package com.example.truescale.ar

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Point
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState

object SurfaceRegistration {

    private const val TAG = "SurfaceRegistration"

    data class RegistrationResult(
        val hit: HitResult?,
        val message: String?,
        val success: Boolean
    )

    fun tryRegister(frame: Frame, screenX: Float, screenY: Float): RegistrationResult {
        try {
            val hits = frame.hitTest(screenX, screenY)
            val valid = hits.firstOrNull { isHitUsable(it) }
            if (valid != null) return RegistrationResult(valid, null, true)

            val offsets = listOf( -30f to 0f, 30f to 0f, 0f to -30f, 0f to 30f, -20f to -20f, 20f to 20f)
            for ((dx, dy) in offsets) {
                val h = frame.hitTest(screenX + dx, screenY + dy)
                val v = h.firstOrNull { isHitUsable(it) }
                if (v != null) return RegistrationResult(v, "Registered with jitter dx=$dx dy=$dy", true)
            }

            return RegistrationResult(null, "No usable hit; try moving device slowly over surface or enable more texture", false)
        } catch (ex: Exception) {
            Log.w(TAG, "Surface registration failed: ${ex.message}")
            return RegistrationResult(null, "Exception: ${ex.message}", false)
        }
    }

    private fun isHitUsable(hit: HitResult): Boolean {
        val trackable = hit.trackable
        if (trackable is com.google.ar.core.Plane) {
            return trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
        }
        if (trackable is Point) {
            return trackable.trackingState == TrackingState.TRACKING &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }
        return trackable.trackingState == TrackingState.TRACKING
    }
}
