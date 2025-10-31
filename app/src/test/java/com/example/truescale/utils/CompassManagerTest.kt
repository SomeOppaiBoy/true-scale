package com.example.truescale.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassManagerTest {

    @Test
    fun testHeadingToCardinal() {
        assertEquals("N", CompassManager.headingToCardinal(359.9f))
        assertEquals("E", CompassManager.headingToCardinal(90f))
        assertEquals("S", CompassManager.headingToCardinal(180f))
        assertEquals("W", CompassManager.headingToCardinal(270f))
    }
}
