package com.example.truescale.ar

import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * ArSurfaceManager
 * - Utilities to configure ARCore session for robust plane detection and depth usage.
 * - Call configureSession(session) when creating or reconfiguring the ARCore session.
 */
class ArSurfaceManager {

    /**
     * Configure ARCore session for robust surface compatibility.
     * - Enables horizontal + vertical plane detection where possible.
     * - Enables automatic depth mode if supported.
     */
    fun configureSession(session: Session) {
        val config = Config(session)

        // Enable both horizontal and vertical plane detection
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

        // Prefer automatic depth if supported
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        } else {
            config.depthMode = Config.DepthMode.DISABLED
        }

        // Enable ambient light estimation
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY

        session.configure(config)
        Log.d(
                "ArSurfaceManager",
                "Session configured: planeMode=${config.planeFindingMode}, depthMode=${config.depthMode}"
        )
    }
}
