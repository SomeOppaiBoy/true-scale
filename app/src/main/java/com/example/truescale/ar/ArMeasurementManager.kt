package com.truescale.app.ar

import com.google.ar.core.*
import com.truescale.app.utils.Vector3
import android.util.Log

/**
 * ArMeasurementManager - Manages AR session and measurement anchors
 *
 * Encapsulates ARCore session so external classes don’t access it directly.
 */
class ArMeasurementManager(private val session: Session) {

    companion object {
        private const val TAG = "ArMeasurementManager"
        private const val MAX_ANCHORS = 10
    }

    private val anchors = mutableListOf<Anchor>()
    private val measurementPoints = mutableListOf<MeasurementPoint>()

    var currentTrackingState: TrackingState = TrackingState.STOPPED
        private set

    val isDepthAvailable: Boolean
        get() = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    data class MeasurementPoint(
        val position: Vector3,
        val anchor: Anchor,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** ✅ New encapsulated method */
    fun onSurfaceChanged(rotation: Int, width: Int, height: Int) {
        session.setDisplayGeometry(rotation, width, height)
    }

    fun update(): Frame? {
        return try {
            val frame = session.update()
            currentTrackingState = frame.camera.trackingState
            frame
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AR session", e)
            null
        }
    }

    fun createMeasurementAnchor(hit: HitResult, confidence: Float = 1.0f): MeasurementPoint? {
        return try {
            if (anchors.size >= MAX_ANCHORS) {
                Log.w(TAG, "Maximum anchor limit reached, removing oldest")
                removeOldestAnchor()
            }
            val anchor = hit.createAnchor()
            anchors.add(anchor)

            val pose = hit.hitPose
            val position = Vector3(pose.tx(), pose.ty(), pose.tz())
            val point = MeasurementPoint(position, anchor, confidence)
            measurementPoints.add(point)

            Log.d(TAG, "Created measurement anchor at $position with confidence $confidence")
            point
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create measurement anchor", e)
            null
        }
    }

    fun createAnchorFromPose(pose: Pose): Anchor? {
        return try {
            val anchor = session.createAnchor(pose)
            anchors.add(anchor)
            anchor
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor from pose", e)
            null
        }
    }

    fun getDepthAtPosition(frame: Frame, x: Float, y: Float): Float? {
        if (!isDepthAvailable) return null
        try {
            frame.acquireDepthImage16Bits()?.use { depthImage ->
                val cameraImage = frame.acquireCameraImage()
                val imageWidth = cameraImage.width
                val imageHeight = cameraImage.height
                cameraImage.close()

                val width = depthImage.width
                val height = depthImage.height

                val normalizedX = (x / imageWidth * width).toInt()
                val normalizedY = (y / imageHeight * height).toInt()

                if (normalizedX in 0 until width && normalizedY in 0 until height) {
                    val depthBuffer = depthImage.planes[0].buffer
                    val depthIndex = normalizedY * width + normalizedX
                    val depthMillimeters = depthBuffer.getShort(depthIndex * 2).toInt()
                    return if (depthMillimeters > 0) depthMillimeters / 1000.0f else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get depth at position", e)
        }
        return null
    }

    fun getDetectedPlanes(): Collection<Plane> = session.getAllTrackables(Plane::class.java)

    fun isPointOnPlane(position: Vector3, maxDistance: Float = 0.05f): Boolean {
        val planes = getDetectedPlanes()
        for (plane in planes) {
            if (plane.trackingState == TrackingState.TRACKING) {
                val pose = plane.centerPose
                val planePosition = Vector3(pose.tx(), pose.ty(), pose.tz())
                val distance = position.distanceTo(planePosition)
                if (distance < maxDistance) return true
            }
        }
        return false
    }

    fun clearAnchors() {
        anchors.forEach { it.detach() }
        anchors.clear()
        measurementPoints.clear()
        Log.d(TAG, "Cleared all anchors")
    }

    private fun removeOldestAnchor() {
        if (anchors.isNotEmpty()) {
            val oldestAnchor = anchors.removeAt(0)
            oldestAnchor.detach()
            measurementPoints.removeAll { it.anchor == oldestAnchor }
        }
    }

    fun calculateMeasurementConfidence(frame: Frame, hit: HitResult): Float {
        var confidence = 0.5f
        if (frame.camera.trackingState == TrackingState.TRACKING) confidence += 0.2f
        if (hit.trackable is Plane) confidence += 0.2f
        if (isDepthAvailable) confidence += 0.1f
        return confidence.coerceIn(0f, 1f)
    }

    fun getMeasurementPoints(): List<MeasurementPoint> = measurementPoints.toList()

    fun dispose() {
        clearAnchors()
    }
}
