package com.example.truescale.utils // <-- CORRECTED PACKAGE

import kotlin.math.sqrt

data class Vector3(val x: Float, val y: Float, val z: Float) {

    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    fun magnitude(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vector3 {
        val mag = magnitude()
        // Avoid division by zero
        return if (mag > 1e-6f) this * (1f / mag) else ZERO
    }

    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val ONE = Vector3(1f, 1f, 1f)
        val UP = Vector3(0f, 1f, 0f)
        val DOWN = Vector3(0f, -1f, 0f)
        val FORWARD = Vector3(0f, 0f, -1f) // Standard OpenGL forward
        val BACK = Vector3(0f, 0f, 1f)
        val RIGHT = Vector3(1f, 0f, 0f)
        val LEFT = Vector3(-1f, 0f, 0f)
    }
}
