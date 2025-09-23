package com.truescale.app.utils

import kotlin.math.sqrt

/**
 * Vector3 - Represents a 3D point or vector in space
 * 
 * Used for all spatial calculations in AR measurements.
 * Provides basic vector operations needed for distance calculations.
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    /**
     * Calculate distance to another point in 3D space
     */
    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Add two vectors
     */
    operator fun plus(other: Vector3): Vector3 {
        return Vector3(x + other.x, y + other.y, z + other.z)
    }
    
    /**
     * Subtract vectors
     */
    operator fun minus(other: Vector3): Vector3 {
        return Vector3(x - other.x, y - other.y, z - other.z)
    }
    
    /**
     * Scale vector by a scalar value
     */
    operator fun times(scalar: Float): Vector3 {
        return Vector3(x * scalar, y * scalar, z * scalar)
    }
    
    /**
     * Get vector magnitude (length)
     */
    fun magnitude(): Float {
        return sqrt(x * x + y * y + z * z)
    }
    
    /**
     * Normalize vector (make unit length)
     */
    fun normalized(): Vector3 {
        val mag = magnitude()
        return if (mag > 0) {
            Vector3(x / mag, y / mag, z / mag)
        } else {
            Vector3(0f, 0f, 0f)
        }
    }
    
    /**
     * Dot product with another vector
     */
    fun dot(other: Vector3): Float {
        return x * other.x + y * other.y + z * other.z
    }
    
    /**
     * Cross product with another vector
     */
    fun cross(other: Vector3): Vector3 {
        return Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )
    }
    
    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val ONE = Vector3(1f, 1f, 1f)
        val UP = Vector3(0f, 1f, 0f)
        val FORWARD = Vector3(0f, 0f, -1f)
        val RIGHT = Vector3(1f, 0f, 0f)
    }
}