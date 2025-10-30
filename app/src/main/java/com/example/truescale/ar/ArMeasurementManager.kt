package com.example.truescale.ar

import com.google.ar.core.*
import android.util.Log
import com.example.truescale.utils.Vector3
import kotlin.math.pow
import kotlin.math.sqrt

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

    // --- NEW: Property to hold the last valid frame ---
    var currentFrame: Frame? = null
        private set
    // -------------------------------------------------

    val isDepthAvailable: Boolean
        get() = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    data class MeasurementPoint(
        val position: Vector3,
        val anchor: Anchor,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** ✅ Encapsulated method */
    fun onSurfaceChanged(rotation: Int, width: Int, height: Int) {
        // This function now receives the correct rotation from MeasurementFragment
        session.setDisplayGeometry(rotation, width, height)
    }

    // --- MODIFIED: update() now caches the frame ---
    fun update(): Frame? {
        return try {
            val frame = session.update()
            currentTrackingState = frame.camera.trackingState
            currentFrame = frame // <-- Cache the frame
            frame
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AR session", e)
            currentFrame = null // <-- Clear cache on error
            null
        }
    }
    // -----------------------------------------------

    fun createMeasurementAnchor(hit: HitResult, confidence: Float = 1.0f): MeasurementPoint? {
        return try {
            // Enhanced validation for better accuracy
            if (anchors.size >= MAX_ANCHORS) {
                Log.w(TAG, "Maximum anchor limit reached, removing oldest")
                removeOldestAnchor()
            }
            
            // Check if hit is on a valid trackable
            val trackable = hit.trackable
            if (trackable.trackingState != TrackingState.TRACKING) {
                Log.w(TAG, "Trackable not in tracking state: ${trackable.trackingState}")
                return null
            }
            
            // Enhanced confidence calculation
            val enhancedConfidence = calculateEnhancedConfidence(hit)
            if (enhancedConfidence < 0.3f) {
                Log.w(TAG, "Confidence too low for measurement: $enhancedConfidence")
                return null
            }
            
            val anchor = hit.createAnchor()
            anchors.add(anchor)

            val pose = hit.hitPose
            val position = Vector3(pose.tx(), pose.ty(), pose.tz())
            val point = MeasurementPoint(position, anchor, enhancedConfidence)
            measurementPoints.add(point)

            Log.d(TAG, "Created measurement anchor at $position with enhanced confidence $enhancedConfidence")
            point
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create measurement anchor", e)
            null
        }
    }
    
    private fun calculateEnhancedConfidence(hit: HitResult): Float {
        var confidence = 0.5f
        
        // Base confidence from tracking state
        if (currentTrackingState == TrackingState.TRACKING) confidence += 0.3f
        
        // Higher confidence for planes vs points
        when (val trackable = hit.trackable) {
            is Plane -> {
                confidence += 0.4f
                // Additional confidence for horizontal planes (floors, tables)
                if (trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                    confidence += 0.2f
                }
            }
            is Point -> {
                confidence += 0.2f
            }
        }
        
        // Depth availability bonus
        if (isDepthAvailable) confidence += 0.1f
        
        // Distance from camera (closer = more accurate)
        val cameraPose = currentFrame?.camera?.pose
        if (cameraPose != null) {
            val hitPose = hit.hitPose
            val dx = hitPose.tx() - cameraPose.tx()
            val dy = hitPose.ty() - cameraPose.ty()
            val dz = hitPose.tz() - cameraPose.tz()
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            
            // Closer distances get higher confidence (up to 2 meters)
            if (distance < 2.0f) {
                confidence += (2.0f - distance) * 0.1f
            }
        }
        
        return confidence.coerceIn(0f, 1f)
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

    fun getDetectedPlanes(): Collection<Plane> {
        val allPlanes = session.getAllTrackables(Plane::class.java)
        
        // Filter and prioritize planes for better quality
        return allPlanes.filter { plane ->
            plane.trackingState == TrackingState.TRACKING &&
            plane.subsumedBy == null && // Only top-level planes
            plane.extentX > 0.1f && // Minimum size threshold
            plane.extentZ > 0.1f
        }.sortedByDescending { plane ->
            // Prioritize by size and type
            val sizeScore = plane.extentX * plane.extentZ
            val typeScore = when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> 3.0f
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> 2.0f
                Plane.Type.VERTICAL -> 1.0f
                else -> 0.5f
            }
            sizeScore * typeScore
        }
    }

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
