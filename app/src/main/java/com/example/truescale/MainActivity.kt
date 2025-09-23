// File: MainActivity.kt
package com.truescale.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException

/**
 * MainActivity - The main entry point for True Scale AR measurement app
 *
 * This activity manages:
 * - Camera permissions
 * - ARCore availability checks
 * - Fragment hosting for MeasurementFragment
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrueScale"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private var measurementFragment: MeasurementFragment? = null

    // Permission launcher for camera access
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkArCoreAndLoadFragment()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create simple container layout
        val container = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)

        // Set up action bar
        supportActionBar?.apply {
            title = "True Scale"
            subtitle = "AR Measurement Tool"
        }

        // Check camera permission first
        checkCameraPermission()
    }

    /**
     * Check camera permission and request if needed
     */
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, CAMERA_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission granted, check ARCore and load fragment
                checkArCoreAndLoadFragment()
            }
            shouldShowRequestPermissionRationale(CAMERA_PERMISSION) -> {
                // Show rationale before requesting
                showPermissionRationale()
            }
            else -> {
                // Request permission directly
                permissionLauncher.launch(CAMERA_PERMISSION)
            }
        }
    }

    /**
     * Check ARCore availability and load the measurement fragment
     */
    private fun checkArCoreAndLoadFragment() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // ARCore is available, load fragment
                    loadMeasurementFragment()
                    Log.d(TAG, "ARCore available, fragment loaded")
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    // ARCore installation requested
                    Log.d(TAG, "ARCore installation requested")
                    // Fragment will be loaded after installation completes
                }
            }
        } catch (e: UnavailableException) {
            handleArCoreException(e)
        }
    }

    /**
     * Load the measurement fragment into the container
     */
    private fun loadMeasurementFragment() {
        if (measurementFragment == null) {
            measurementFragment = MeasurementFragment()

            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(android.R.id.content, measurementFragment!!)
            }
        }
    }

    /**
     * Handle different ARCore exceptions
     */
    private fun handleArCoreException(e: UnavailableException) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> {
                "ARCore is required but not installed. Please install ARCore from Google Play Store."
            }
            is UnavailableApkTooOldException -> {
                "ARCore version is too old. Please update ARCore from Google Play Store."
            }
            is UnavailableSdkTooOldException -> {
                "Android version is too old for ARCore. Android 7.0 or newer is required."
            }
            is UnavailableDeviceNotCompatibleException -> {
                "This device is not compatible with ARCore."
            }
            else -> "ARCore is unavailable: ${e.message}"
        }

        Log.e(TAG, "ARCore exception: $message", e)
        showArCoreError(message)
    }

    // Dialog methods
    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Camera Permission Required")
            .setMessage("True Scale needs camera access to measure distances using Augmented Reality. This allows the app to overlay measurement information on your camera view.")
            .setPositiveButton("Grant Permission") { _, _ ->
                permissionLauncher.launch(CAMERA_PERMISSION)
            }
            .setNegativeButton("Exit App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Camera Permission Required")
            .setMessage("True Scale cannot function without camera access. Please enable camera permission in your device settings.")
            .setPositiveButton("Exit App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showArCoreError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("AR Not Available")
            .setMessage(message)
            .setPositiveButton("Exit App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()

        // If we don't have a fragment yet and we should have one, check again
        if (measurementFragment == null &&
            ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION)
            == PackageManager.PERMISSION_GRANTED) {
            checkArCoreAndLoadFragment()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Forward permission results to fragment if it exists
        measurementFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // Handle back press
    override fun onBackPressed() {
        // Check if fragment wants to handle back press first
        val fragment = measurementFragment
        if (fragment?.isVisible == true) {
            // You could add custom back handling in fragment if needed
            // For now, use default behavior
            super.onBackPressed()
        } else {
            super.onBackPressed()
        }
    }
}