package com.example.truescale.utils

import com.example.truescale.ar.ArLightingManager
import org.junit.Assert.assertTrue
import org.junit.Test

class ArLightingManagerTest {

    @Test
    fun brightnessClampTest() {
        val lighting = ArLightingManager()
        // simulate update with a synthetic pixelIntensity: we can't directly call update without Frame,
        // but we can test default getter behavior
        val b = lighting.getBrightnessFactor()
        assertTrue(b >= 0.1f && b <= 2.0f)
    }
}
