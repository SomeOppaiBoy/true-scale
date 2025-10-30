// File: MeasurementUtils.kt
package com.example.truescale.utils // <-- CORRECTED PACKAGE

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.sqrt

object MeasurementUtils {

    private const val SMOOTHING_FACTOR = 0.8f
    private const val MIN_CONFIDENCE_THRESHOLD = 0.7f
    private val measurementHistory = mutableListOf<Float>()
    private const val MAX_HISTORY_SIZE = 5

    fun calculateDistance(start: Vector3, end: Vector3): Float {
        return start.distanceTo(end)
    }
    
    /**
     * Enhanced distance calculation with accuracy improvements
     */
    fun calculateEnhancedDistance(start: Vector3, end: Vector3, confidence: Float = 1.0f): Float {
        val baseDistance = start.distanceTo(end)
        
        // Apply confidence-based accuracy adjustment
        val accuracyFactor = when {
            confidence > 0.8f -> 1.0f
            confidence > 0.6f -> 0.95f
            confidence > 0.4f -> 0.9f
            else -> 0.85f
        }
        
        return baseDistance * accuracyFactor
    }
    
    /**
     * Calculate measurement error based on tracking quality
     */
    fun estimateMeasurementError(distance: Float, confidence: Float): Float {
        val baseError = when {
            distance < 0.5f -> 0.01f // 1cm for short distances
            distance < 2.0f -> 0.02f // 2cm for medium distances
            else -> 0.05f // 5cm for long distances
        }
        
        val confidenceFactor = 2.0f - confidence // Lower confidence = higher error
        return baseError * confidenceFactor
    }

    fun calculateSmoothedDistance(
        start: Vector3,
        end: Vector3,
        useSmoothing: Boolean = true
    ): Float {
        val rawDistance = calculateDistance(start, end)
        if (!useSmoothing) return rawDistance

        measurementHistory.add(rawDistance)
        if (measurementHistory.size > MAX_HISTORY_SIZE) {
            measurementHistory.removeAt(0)
        }

        return if (measurementHistory.size > 1) {
            measurementHistory.average().toFloat() // Simple average might be better
            // Or keep EMA:
            // measurementHistory.reduce { acc, distance ->
            //     acc * SMOOTHING_FACTOR + distance * (1 - SMOOTHING_FACTOR)
            // }
        } else {
            rawDistance
        }
    }

    fun calculateHeight(bottom: Vector3, top: Vector3): Float {
        return abs(top.y - bottom.y)
    }

    fun calculateHorizontalDistance(start: Vector3, end: Vector3): Float {
        val dx = end.x - start.x
        val dz = end.z - start.z
        return sqrt(dx * dx + dz * dz)
    }

    fun calculateRectangleArea(corner1: Vector3, corner2: Vector3): Float {
        // Assuming corners define a rectangle aligned with XZ plane
        val width = abs(corner2.x - corner1.x)
        val depth = abs(corner2.z - corner1.z) // Use depth for Z-axis difference
        return width * depth
    }

    fun calculateBoxVolume(corner1: Vector3, corner2: Vector3): Float {
        val width = abs(corner2.x - corner1.x)
        val height = abs(corner2.y - corner1.y)
        val depth = abs(corner2.z - corner1.z)
        return width * height * depth
    }

    fun poseToVector3(pose: Pose): Vector3 {
        return Vector3(pose.tx(), pose.ty(), pose.tz())
    }

    fun calculateAngle(v1: Vector3, v2: Vector3): Float {
        val dot = v1.normalized().dot(v2.normalized())
        val angleRad = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }

    fun isConfidenceAcceptable(confidence: Float): Boolean {
        return confidence >= MIN_CONFIDENCE_THRESHOLD
    }

    fun clearHistory() {
        measurementHistory.clear()
    }

    fun estimateAccuracy(
        distance: Float,
        confidence: Float,
        hasDepth: Boolean
    ): Float {
        val baseError = distance * 0.02f
        val confidenceFactor = (1.5f - confidence).coerceAtLeast(1.0f) // Less penalty for lower confidence initially
        val depthFactor = if (hasDepth) 0.5f else 1.0f
        return (baseError * confidenceFactor * depthFactor).coerceAtLeast(0.01f) // Minimum 1cm error
    }
}
