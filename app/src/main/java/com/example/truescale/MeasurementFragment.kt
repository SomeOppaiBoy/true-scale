// File: MeasurementFragment.kt
package com.truescale.app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.truescale.app.ar.ArMeasurementManager
import com.truescale.app.ar.MeasurementRenderer
import com.truescale.app.utils.MeasurementUtils
import com.truescale.app.utils.Units
import com.truescale.app.utils.Vector3
import com.truescale.app.viewmodel.MeasurementViewModel

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

    // Touch handling
    private var lastTouchTime: Long = 0
    private val touchThrottleMs = 300L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
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
        // Find views by ID
        arSurfaceView = view.findViewById(R.id.arSurfaceView)
        measurementText = view.findViewById(R.id.measurementText)
        instructionText = view.findViewById(R.id.instructionText)
        unitToggleButton = view.findViewById(R.id.unitToggleButton)
        clearButton = view.findViewById(R.id.clearButton)
        modeButton = view.findViewById(R.id.modeButton)

        // Set up touch listener for AR surface
        arSurfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        // Set up button listeners
        unitToggleButton.setOnClickListener {
            viewModel.toggleUnits()
        }

        clearButton.setOnClickListener {
            viewModel.clearMeasurements()
            renderer?.clearMeasurements()
            showSnackbar("Measurements cleared")
        }

        modeButton.setOnClickListener {
            cycleMode()
        }
    }

    private fun setupObservers() {
        // Observe measurement updates
        viewModel.currentMeasurement.observe(viewLifecycleOwner, Observer { measurement ->
            measurement?.let {
                val distance = MeasurementUtils.calculateDistance(it.startPoint, it.endPoint)
                val useMetric = viewModel.useMetric.value ?: true
                val formattedDistance = if (useMetric) {
                    Units.formatMetric(distance)
                } else {
                    Units.formatImperial(distance)
                }
                measurementText.text = formattedDistance

                // Update renderer
                renderer?.addMeasurementLine(it.startPoint, it.endPoint, it.confidence)
            } ?: run {
                measurementText.text = "Tap to measure"
            }
        })

        // Observe unit changes
        viewModel.useMetric.observe(viewLifecycleOwner, Observer { useMetric ->
            unitToggleButton.text = if (useMetric) "Metric" else "Imperial"

            // Update current measurement display
            viewModel.currentMeasurement.value?.let { measurement ->
                val distance = MeasurementUtils.calculateDistance(measurement.startPoint, measurement.endPoint)
                val formattedDistance = if (useMetric) {
                    Units.formatMetric(distance)
                } else {
                    Units.formatImperial(distance)
                }
                measurementText.text = formattedDistance
            }
        })

        // Observe measurement mode changes
        viewModel.measurementMode.observe(viewLifecycleOwner, Observer { mode ->
            modeButton.text = mode.name.lowercase().replaceFirstChar { it.uppercase() }
            updateInstructions(mode)
        })

        // Observe tracking state
        viewModel.trackingState.observe(viewLifecycleOwner, Observer { state ->
            when (state) {
                TrackingState.TRACKING -> {
                    instructionText.text = if (viewModel.isMeasuring()) {
                        "Tap to place second point"
                    } else {
                        "Tap to place first point"
                    }
                    instructionText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
                }
                TrackingState.PAUSED -> {
                    instructionText.text = "Move device slowly to improve tracking"
                    instructionText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
                }
                TrackingState.STOPPED -> {
                    instructionText.text = "AR tracking stopped - point at surfaces"
                    instructionText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light))
                }
            }
        })
    }

    private fun updateInstructions(mode: MeasurementViewModel.MeasurementMode) {
        val instruction = when (mode) {
            MeasurementViewModel.MeasurementMode.DISTANCE -> "Tap two points to measure distance"
            MeasurementViewModel.MeasurementMode.HEIGHT -> "Tap bottom then top to measure height"
            MeasurementViewModel.MeasurementMode.AREA -> "Tap opposite corners to measure area"
            MeasurementViewModel.MeasurementMode.VOLUME -> "Tap opposite corners to measure volume"
        }
        if (viewModel.trackingState.value == TrackingState.TRACKING) {
            instructionText.text = instruction
        }
    }

    private fun cycleMode() {
        val currentMode = viewModel.measurementMode.value ?: MeasurementViewModel.MeasurementMode.DISTANCE
        val modes = MeasurementViewModel.MeasurementMode.values()
        val currentIndex = modes.indexOf(currentMode)
        val nextIndex = (currentIndex + 1) % modes.size
        viewModel.setMeasurementMode(modes[nextIndex])
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTouchTime < touchThrottleMs) {
                return true
            }
            lastTouchTime = currentTime

            performHitTest(event.x, event.y)
        }
        return true
    }

    private fun performHitTest(x: Float, y: Float) {
        val session = arSession ?: return
        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AR frame", e)
            return
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            showSnackbar("Wait for AR tracking to stabilize")
            return
        }

        // Update tracking state in ViewModel
        viewModel.updateTrackingState(frame.camera.trackingState)

        try {
            val hits = frame.hitTest(x, y)
            val hit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()

            hit?.let {
                val confidence = arManager?.calculateMeasurementConfidence(frame, it) ?: 0.7f

                if (confidence < 0.5f) {
                    showSnackbar("Low tracking quality - try again")
                    return
                }

                val pose = it.hitPose
                val point = Vector3(pose.tx(), pose.ty(), pose.tz())

                if (viewModel.isMeasuring()) {
                    // Complete measurement
                    viewModel.setEndPoint(point, it.createAnchor())
                    MeasurementUtils.clearHistory()
                    showSnackbar("Measurement completed!")
                } else {
                    // Start measurement
                    viewModel.setStartPoint(point, it.createAnchor())
                    showSnackbar("First point placed - tap for second point")
                }
            } ?: run {
                showSnackbar("No surface detected - point at floor or table")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hit test failed", e)
            showSnackbar("Measurement failed - try again")
        }
    }

    private fun initializeAR() {
        try {
            arSession = Session(requireContext())

            val config = Config(arSession).apply {
                depthMode = if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = Config.FocusMode.AUTO
            }

            arSession!!.configure(config)
            arManager = ArMeasurementManager(arSession!!)
            renderer = MeasurementRenderer(requireContext(), arManager!!)

            arSurfaceView.apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            Log.d(TAG, "AR initialized successfully")
            showSnackbar("AR ready - scan surfaces to begin")

        } catch (e: Exception) {
            Log.e(TAG, "AR initialization failed", e)
            handleArInitializationError(e)
        }
    }

    private fun handleArInitializationError(e: Exception) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> "ARCore not installed"
            is UnavailableApkTooOldException -> "ARCore needs update"
            is UnavailableSdkTooOldException -> "Android version too old"
            is UnavailableDeviceNotCompatibleException -> "Device not compatible"
            else -> "AR initialization failed: ${e.message}"
        }
        showError(message)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeAR()
            } else {
                showError("Camera permission required for AR")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            arSurfaceView.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AR session", e)
        }
    }

    override fun onPause() {
        super.onPause()
        arSurfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arManager?.dispose()
        arSession?.close()
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
}