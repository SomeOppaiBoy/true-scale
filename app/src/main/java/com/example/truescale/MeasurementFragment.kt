// File: MeasurementFragment.kt
package com.example.truescale // Correct package declaration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
// --- Ensure ONLY correct package imports are used ---
import com.example.truescale.ar.ArMeasurementManager
import com.example.truescale.ar.MeasurementRenderer
import com.example.truescale.utils.MeasurementUtils
import com.example.truescale.utils.Units
import com.example.truescale.utils.Vector3
import com.example.truescale.viewmodel.MeasurementViewModel
// --- Import the R class for resources ---
import com.example.truescale.R // <--- THIS IS CRITICAL

// Dummy Calculations class needed by pasted code - REPLACE if you have real one
object Calculations {
    fun calculateDistanceToPlane(pose1: Pose, pose2: Pose): Float { return 1.0f }
}


class MeasurementFragment : Fragment() {

    companion object {
        private const val TAG = "MeasurementFragment"
        private const val CAMERA_PERMISSION_CODE = 1001
    }

    // AR Components
    private var arSession: Session? = null
    private var arManager: ArMeasurementManager? = null
    private var renderer: MeasurementRenderer? = null

    // UI Components
    private lateinit var arSurfaceView: GLSurfaceView
    private lateinit var measurementText: TextView
    private lateinit var instructionText: TextView
    private lateinit var unitToggleButton: Button
    private lateinit var clearButton: FloatingActionButton
    private lateinit var modeButton: Button

    // ViewModel
    private val viewModel: MeasurementViewModel by viewModels()

    // --- Display listener for orientation ---
    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            updateArSessionDisplayGeometry()
        }
    }
    // -------------------------------------------

    // Touch handling
    private var lastTouchTime: Long = 0
    private val touchThrottleMs = 300L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Use the imported R class
        return inflater.inflate(R.layout.fragment_measurement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupObservers()
        if (hasCameraPermission()) {
            initializeAR()
        } else {
            requestCameraPermission()
        }
    }

    private fun initializeViews(view: View) {
        // Use the imported R class for IDs
        arSurfaceView = view.findViewById(R.id.arSurfaceView)
        measurementText = view.findViewById(R.id.measurementText)
        instructionText = view.findViewById(R.id.instructionText)
        unitToggleButton = view.findViewById(R.id.unitToggleButton)
        clearButton = view.findViewById(R.id.clearButton)
        modeButton = view.findViewById(R.id.modeButton)

        arSurfaceView.setOnTouchListener { _, event -> handleTouch(event) }

        unitToggleButton.setOnClickListener { viewModel.toggleUnits() }

        clearButton.setOnClickListener {
            viewModel.clearMeasurements()
            renderer?.clearMeasurements()
            // Use string resource via R class
            showSnackbar(getString(R.string.snackbar_measurements_cleared))
        }

        modeButton.setOnClickListener { cycleMode() }
    }

    private fun setupObservers() {
        viewModel.currentMeasurement.observe(viewLifecycleOwner, Observer { measurement ->
            measurement?.let {
                val distance = MeasurementUtils.calculateDistance(it.startPoint, it.endPoint)
                val useMetric = viewModel.useMetric.value ?: true
                val formattedDistance = if (useMetric) Units.formatMetric(distance) else Units.formatImperial(distance)
                measurementText.text = formattedDistance
                renderer?.addMeasurementLine(it.startPoint, it.endPoint, it.confidence)
            } ?: run {
                measurementText.text = getString(R.string.tap_to_measure) // Use string resource
            }
        })

        viewModel.useMetric.observe(viewLifecycleOwner, Observer { useMetric ->
            unitToggleButton.text = if (useMetric) getString(R.string.metric) else getString(R.string.imperial) // Use string resources
            viewModel.currentMeasurement.value?.let { measurement ->
                val distance = MeasurementUtils.calculateDistance(measurement.startPoint, measurement.endPoint)
                val formattedDistance = if (useMetric) Units.formatMetric(distance) else Units.formatImperial(distance)
                measurementText.text = formattedDistance
            }
        })

        viewModel.measurementMode.observe(viewLifecycleOwner, Observer { mode ->
            modeButton.text = mode.name.lowercase().replaceFirstChar { it.uppercase() }
            updateInstructions(mode)
        })

        viewModel.trackingState.observe(viewLifecycleOwner, Observer { state ->
            // Use local context safely
            val safeContext = context ?: return@Observer // Exit if context is null
            val (textResId, colorResId) = when (state) {
                TrackingState.TRACKING -> {
                    if (viewModel.isMeasuring()) R.string.instruction_tap_second_point to android.R.color.holo_green_light
                    else R.string.instruction_tap_first_point to android.R.color.holo_green_light
                }
                TrackingState.PAUSED -> R.string.instruction_move_slowly to android.R.color.holo_orange_light
                TrackingState.STOPPED -> R.string.instruction_tracking_stopped to android.R.color.holo_red_light
                null -> R.string.instruction_initializing to android.R.color.white
            }
            instructionText.text = getString(textResId)
            instructionText.setTextColor(ContextCompat.getColor(safeContext, colorResId))
        })
    }

    private fun updateInstructions(mode: MeasurementViewModel.MeasurementMode) {
        val instructionResId = when (mode) {
            MeasurementViewModel.MeasurementMode.DISTANCE -> R.string.instruction_distance
            MeasurementViewModel.MeasurementMode.HEIGHT -> R.string.instruction_height
            MeasurementViewModel.MeasurementMode.AREA -> R.string.instruction_area
            MeasurementViewModel.MeasurementMode.VOLUME -> R.string.instruction_volume
        }
        val arManager = this.arManager
        val frame = arManager?.currentFrame
        val trackingState = frame?.camera?.trackingState
        
        val instruction = when {
            trackingState == TrackingState.PAUSED -> "Move device slowly to improve tracking"
            trackingState == TrackingState.STOPPED -> "Point at surfaces to detect planes"
            trackingState == TrackingState.TRACKING -> getString(instructionResId)
            else -> "Initializing AR..."
        }
        instructionText.text = instruction
        
        // Update instruction color based on tracking quality
        val color = when (trackingState) {
            TrackingState.TRACKING -> android.graphics.Color.WHITE
            TrackingState.PAUSED -> android.graphics.Color.YELLOW
            TrackingState.STOPPED -> android.graphics.Color.RED
            else -> android.graphics.Color.GRAY
        }
        instructionText.setTextColor(color)
    }

    private fun cycleMode() {
        val currentMode = viewModel.measurementMode.value ?: MeasurementViewModel.MeasurementMode.DISTANCE
        val modes = MeasurementViewModel.MeasurementMode.values()
        val nextIndex = (modes.indexOf(currentMode) + 1) % modes.size
        viewModel.setMeasurementMode(modes[nextIndex])
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTouchTime < touchThrottleMs) return true
            lastTouchTime = currentTime
            
            // Add haptic feedback for better user experience
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            
            performHitTest(event.x, event.y)
        }
        return true
    }

    private fun performHitTest(x: Float, y: Float) {
        val manager = arManager ?: return
        val frame = manager.currentFrame ?: return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            showSnackbar(getString(R.string.snackbar_wait_tracking)) // Use string resource
            return
        }

        viewModel.updateTrackingState(frame.camera.trackingState)

        try {
            val hits = frame.hitTest(x, y)
            
            // Enhanced hit test logic with better filtering
            val validHits = hits.filter { hit ->
                val trackable = hit.trackable
                when (trackable) {
                    is Plane -> {
                        trackable.trackingState == TrackingState.TRACKING &&
                        trackable.isPoseInPolygon(hit.hitPose) &&
                        trackable.subsumedBy == null && // Only top-level planes
                        trackable.extentX > 0.1f && trackable.extentZ > 0.1f // Minimum size
                    }
                    is Point -> {
                        trackable.trackingState == TrackingState.TRACKING &&
                        trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    }
                    else -> false
                }
            }
            
            // Sort by confidence and distance for best hit selection
            val bestHit = validHits.maxByOrNull { hit ->
                val confidence = manager.calculateMeasurementConfidence(frame, hit)
                val distance = calculateDistanceToCamera(frame, hit.hitPose)
                confidence * (1.0f / (1.0f + distance)) // Closer hits get higher score
            }

            bestHit?.let { validHit ->
                val confidence = manager.calculateMeasurementConfidence(frame, validHit)
                if (confidence < 0.3f) { // Lowered threshold for better usability
                    showSnackbar("Low tracking quality - try moving closer or improving lighting")
                    return
                }

                val anchor = validHit.createAnchor() // Create anchor from the valid hit
                val pose = validHit.hitPose
                val point = Vector3(pose.tx(), pose.ty(), pose.tz())

                if (viewModel.isMeasuring()) {
                    viewModel.setEndPoint(point, anchor) // Pass anchor
                    MeasurementUtils.clearHistory()
                    showSnackbar(getString(R.string.snackbar_measurement_complete)) // Use string resource
                } else {
                    viewModel.setStartPoint(point, anchor) // Pass anchor
                    showSnackbar(getString(R.string.snackbar_first_point)) // Use string resource
                }
            } ?: run {
                showSnackbar(getString(R.string.snackbar_no_surface)) // Use string resource
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hit test failed", e)
            showSnackbar(getString(R.string.snackbar_measurement_failed)) // Use string resource
        }
    }


    private fun initializeAR() {
        if (arSession != null) return

        // Use safe context access
        val safeContext = context ?: run {
            Log.e(TAG, "Cannot initialize AR: Context is null")
            return
        }

        try {
            arSession = Session(safeContext).also { session ->
                val config = Config(session).apply {
                    // Enhanced depth mode for better accuracy
                    depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                    
                    // Enhanced plane detection for all surfaces
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    
                    // Better focus mode for accuracy
                    focusMode = Config.FocusMode.AUTO
                    
                    // Enable instant placement for better tracking
                    instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    
                    // Enable cloud anchors for better tracking persistence
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    
                    // Enable light estimation for better lighting tolerance
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
                    // Enable semantic understanding for better surface detection
                    semanticMode = Config.SemanticMode.ENABLED
                }
                session.configure(config)
                arManager = ArMeasurementManager(session)
            }

            renderer = MeasurementRenderer(safeContext, arManager!!)

            arSurfaceView.apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            // Reset retry count and set up camera texture for AR session
            retryCount = 0
            setupCameraTexture()

            Log.d(TAG, "AR initialized successfully. Depth Mode: ${arSession?.config?.depthMode}")
            showSnackbar(getString(R.string.snackbar_ar_ready))

        } catch (e: Exception) {
            Log.e(TAG, "AR initialization failed", e)
            handleArInitializationError(e)
        }
    }

    private fun handleArInitializationError(e: Exception) {
        // Use safe context access
        val safeContext = context ?: return
        val messageResId = when (e) {
            is UnavailableArcoreNotInstalledException -> R.string.error_arcore_not_installed
            is UnavailableApkTooOldException -> R.string.error_arcore_update_needed
            is UnavailableSdkTooOldException -> R.string.error_sdk_too_old
            is UnavailableDeviceNotCompatibleException -> R.string.error_device_not_compatible
            else -> R.string.error_ar_init_failed
        }
        val message = getString(messageResId) + if (messageResId == R.string.error_ar_init_failed) ": ${e.message}" else ""
        showError(message)
        activity?.finish()
    }


    private fun hasCameraPermission(): Boolean {
        // Use safe context access
        val safeContext = context ?: return false
        return ContextCompat.checkSelfPermission(safeContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        // Use safe activity access
        activity?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults) // Important to call super
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeAR()
            } else {
                showError(getString(R.string.error_camera_permission_required))
                activity?.finish()
            }
        }
    }


    private fun updateArSessionDisplayGeometry() {
        // Use safe activity and view access
        val currentActivity = activity ?: return
        if (!::arSurfaceView.isInitialized || arSurfaceView.width == 0 || arSurfaceView.height == 0) return

        try {
            val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                currentActivity.display
            } else {
                @Suppress("DEPRECATION")
                (currentActivity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }

            display?.let {
                val rotation = it.rotation
                val width = arSurfaceView.width
                val height = arSurfaceView.height
                arManager?.onSurfaceChanged(rotation, width, height)
                Log.d(TAG, "Updated AR display geometry: rot=$rotation, w=$width, h=$height")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating display geometry", e)
        }
    }

    private fun setupCameraTexture() {
        try {
            // Wait for the renderer to be initialized
            renderer?.let { renderer ->
                // Get the background renderer's texture ID
                val backgroundRenderer = renderer.getBackgroundRenderer()
                val textureId = backgroundRenderer?.textureId
                
                if (textureId != null && textureId != -1) {
                    // Set the camera texture for the AR session
                    arSession?.setCameraTextureNames(intArrayOf(textureId))
                    Log.d(TAG, "Camera texture set up successfully with ID: $textureId")
                } else {
                    Log.w(TAG, "Background renderer texture not ready yet, retrying...")
                    // Retry after a short delay, but limit retries
                    if (retryCount < 10) {
                        retryCount++
                        arSurfaceView.postDelayed({
                            setupCameraTexture()
                        }, 200)
                    } else {
                        Log.e(TAG, "Failed to set up camera texture after multiple retries")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up camera texture", e)
        }
    }

    private var retryCount = 0
    
    private fun calculateDistanceToCamera(frame: Frame, pose: Pose): Float {
        val cameraPose = frame.camera.pose
        val dx = pose.tx() - cameraPose.tx()
        val dy = pose.ty() - cameraPose.ty()
        val dz = pose.tz() - cameraPose.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }


    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            Log.w(TAG, "Camera permission not granted onResume, requesting again or exiting.")
            // Maybe request again, or show rationale, or finish
            // requestCameraPermission() // Option: Request again
            // activity?.finish() // Option: Exit
            return // Do not proceed without permission
        }

        // Use safe context and view access
        val safeContext = context ?: return
        if (!::arSurfaceView.isInitialized) return

        try {
            if (arSession == null) {
                initializeAR() // Initialize if needed
            }
            // Resume session only if initialized successfully
            if (arSession != null) {
                // Ensure camera texture is set before resuming the session
                setupCameraTexture()
                arSession?.resume()
                arSurfaceView.onResume()

                // Register display listener
                displayManager = safeContext.getSystemService(DisplayManager::class.java)
                displayManager.registerDisplayListener(displayListener, null)
                updateArSessionDisplayGeometry() // Update geometry on resume
                
                // Set up camera texture after resume
                setupCameraTexture()
                
                Log.d(TAG, "AR Resumed")
            } else {
                Log.e(TAG, "AR Session was null onResume, initialization might have failed.")
                // Maybe attempt re-initialization or show error
                // initializeAR() // Option: Try again
                // showError("Failed to resume AR.") // Option: Show error
            }

        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available on resume", e)
            showError(getString(R.string.error_camera_not_available))
            activity?.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AR session", e)
            showError("Error resuming AR: ${e.message}")
            // Consider finishing if resume fails critically
            // activity?.finish()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "AR Pausing...")
        // Use safe context and view access
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
        // Check if view is initialized before accessing
        if (::arSurfaceView.isInitialized) {
            arSurfaceView.onPause()
        }
        arSession?.pause()
        Log.d(TAG, "AR Paused")
    }

    // Changed onDestroy to onDestroyView for Fragments
    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "AR Destroying View...")
        
        // Clean up renderer resources
        renderer?.cleanup()
        
        // Close session safely
        arSession?.close()
        arSession = null
        arManager?.dispose()
        arManager = null
        renderer = null // Allow GC
        Log.d(TAG, "AR View Destroyed")
    }


    private fun showError(message: String) {
        // Use safe context access
        context?.let {
            Toast.makeText(it, message, Toast.LENGTH_LONG).show()
        }
        Log.e(TAG, message)
    }

    private fun showSnackbar(message: String) {
        // Check if view is available
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
}

