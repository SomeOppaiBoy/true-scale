// File: MeasurementUtils.kt
package com.truescale.app.utils

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MeasurementUtils - Core measurement calculation utilities
 * 
 * Provides high-accuracy distance and dimension calculations
 * using ARCore data and vector mathematics.
 */
object MeasurementUtils {
    
    // Smoothing parameters for measurement stability
    private const val SMOOTHING_FACTOR = 0.8f
    private const val MIN_CONFIDENCE_THRESHOLD = 0.7f
    
    // Measurement history for smoothing
    private val measurementHistory = mutableListOf<Float>()
    private const val MAX_HISTORY_SIZE = 5
    
    /**
     * Calculate distance between two 3D points
     * 
     * @param start Starting point in 3D space
     * @param end Ending point in 3D space
     * @return Distance in meters
     */
    fun calculateDistance(start: Vector3, end: Vector3): Float {
        return start.distanceTo(end)
    }
    
    /**
     * Calculate distance with smoothing for more stable readings
     * 
     * @param start Starting point
     * @param end Ending point
     * @param useSmoothing Enable temporal smoothing
     * @return Smoothed distance in meters
     */
    fun calculateSmoothedDistance(
        start: Vector3, 
        end: Vector3, 
        useSmoothing: Boolean = true
    ): Float {
        val rawDistance = calculateDistance(start, end)
        
        if (!useSmoothing) {
            return rawDistance
        }
        
        // Add to history
        measurementHistory.add(rawDistance)
        if (measurementHistory.size > MAX_HISTORY_SIZE) {
            measurementHistory.removeAt(0)
        }
        
        // Apply exponential moving average
        return if (measurementHistory.size > 1) {
            val smoothed = measurementHistory.reduce { acc, distance ->
                acc * SMOOTHING_FACTOR + distance * (1 - SMOOTHING_FACTOR)
            }
            smoothed
        } else {
            rawDistance
        }
    }
    
    /**
     * Calculate height between two points (vertical distance)
     * 
     * @param bottom Lower point
     * @param top Upper point
     * @return Height in meters
     */
    fun calculateHeight(bottom: Vector3, top: Vector3): Float {
        return abs(top.y - bottom.y)
    }
    
    /**
     * Calculate horizontal distance (ignoring Y axis)
     * 
     * @param start Starting point
     * @param end Ending point
     * @return Horizontal distance in meters
     */
    fun calculateHorizontalDistance(start: Vector3, end: Vector3): Float {
        val dx = end.x - start.x
        val dz = end.z - start.z
        return sqrt(dx * dx + dz * dz)
    }
    
    /**
     * Calculate area of rectangle defined by two corners
     * 
     * @param corner1 First corner
     * @param corner2 Opposite corner
     * @return Area in square meters
     */
    fun calculateRectangleArea(corner1: Vector3, corner2: Vector3): Float {
        val width = abs(corner2.x - corner1.x)
        val height = abs(corner2.z - corner1.z)
        return width * height
    }
    
    /**
     * Calculate volume of box defined by two corners
     * 
     * @param corner1 First corner
     * @param corner2 Opposite corner
     * @return Volume in cubic meters
     */
    fun calculateBoxVolume(corner1: Vector3, corner2: Vector3): Float {
        val width = abs(corner2.x - corner1.x)
        val height = abs(corner2.y - corner1.y)
        val depth = abs(corner2.z - corner1.z)
        return width * height * depth
    }
    
    /**
     * Convert Pose to Vector3
     * 
     * @param pose ARCore Pose object
     * @return Vector3 position
     */
    fun poseToVector3(pose: Pose): Vector3 {
        return Vector3(pose.tx(), pose.ty(), pose.tz())
    }
    
    /**
     * Calculate angle between two vectors (in degrees)
     * 
     * @param v1 First vector
     * @param v2 Second vector
     * @return Angle in degrees
     */
    fun calculateAngle(v1: Vector3, v2: Vector3): Float {
        val dot = v1.normalized().dot(v2.normalized())
        val angleRad = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }
    
    /**
     * Check if measurement confidence is acceptable
     * 
     * @param confidence Confidence value from ARCore (0-1)
     * @return True if confidence meets threshold
     */
    fun isConfidenceAcceptable(confidence: Float): Boolean {
        return confidence >= MIN_CONFIDENCE_THRESHOLD
    }
    
    /**
     * Clear measurement history (call when starting new measurement)
     */
    fun clearHistory() {
        measurementHistory.clear()
    }
    
    /**
     * Estimate measurement accuracy based on various factors
     * 
     * @param distance Measured distance
     * @param confidence ARCore tracking confidence
     * @param hasDepth Whether depth API is available
     * @return Estimated error margin in meters
     */
    fun estimateAccuracy(
        distance: Float,
        confidence: Float,
        hasDepth: Boolean
    ): Float {
        // Base accuracy depends on distance (farther = less accurate)
        val baseError = distance * 0.02f // 2% base error
        
        // Adjust based on confidence
        val confidenceFactor = 2.0f - confidence // 1.0 to 2.0
        
        // Depth API significantly improves accuracy
        val depthFactor = if (hasDepth) 0.5f else 1.0f
        
        return baseError * confidenceFactor * depthFactor
    }
}