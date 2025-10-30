// File: Units.kt
package com.example.truescale.utils // <-- CORRECTED PACKAGE

import java.util.Locale

object Units {

    const val METERS_TO_FEET = 3.28084f
    const val METERS_TO_INCHES = 39.3701f
    const val METERS_TO_CM = 100f

    fun formatMetric(meters: Float): String {
        return when {
            meters < 0.01f -> String.format(Locale.US, "%.1f mm", meters * 1000)
            meters < 1.0f -> String.format(Locale.US, "%.1f cm", meters * METERS_TO_CM)
            else -> String.format(Locale.US, "%.2f m", meters)
        }
    }

    fun formatImperial(meters: Float): String {
        val totalInches = meters * METERS_TO_INCHES
        return when {
            totalInches < 0.1f -> "0\"" // Handle very small values
            totalInches < 12f -> String.format(Locale.US, "%.1f\"", totalInches)
            totalInches < 36f -> { // Up to 3 feet
                val feet = (totalInches / 12).toInt()
                val inches = totalInches % 12
                if (inches < 0.1f) String.format(Locale.US, "%d'", feet) // Whole feet
                else String.format(Locale.US, "%d' %.1f\"", feet, inches)
            }
            else -> { // More than 3 feet
                val feet = totalInches / 12f
                String.format(Locale.US, "%.1f'", feet) // Just feet with decimal
            }
        }
    }

    fun metersToCentimeters(meters: Float): Float = meters * METERS_TO_CM
    fun metersToFeet(meters: Float): Float = meters * METERS_TO_FEET
    fun metersToInches(meters: Float): Float = meters * METERS_TO_INCHES
    fun centimetersToMeters(cm: Float): Float = cm / METERS_TO_CM
    fun feetToMeters(feet: Float): Float = feet / METERS_TO_FEET
    fun inchesToMeters(inches: Float): Float = inches / METERS_TO_INCHES

    fun formatArea(squareMeters: Float, useMetric: Boolean): String {
        return if (useMetric) {
            if (squareMeters < 1.0f) String.format(Locale.US, "%.1f cm²", squareMeters * 10000)
            else String.format(Locale.US, "%.2f m²", squareMeters)
        } else {
            val squareFeet = squareMeters * 10.764f
            String.format(Locale.US, "%.1f ft²", squareFeet)
        }
    }

    fun formatVolume(cubicMeters: Float, useMetric: Boolean): String {
        return if (useMetric) {
            when {
                cubicMeters < 0.000001f -> String.format(Locale.US, "%.1f mm³", cubicMeters * 1e9) // mm³
                cubicMeters < 0.001f -> String.format(Locale.US, "%.1f cm³", cubicMeters * 1e6) // cm³
                cubicMeters < 1f -> String.format(Locale.US, "%.1f L", cubicMeters * 1000) // Liters
                else -> String.format(Locale.US, "%.2f m³", cubicMeters) // m³
            }
        } else {
            val cubicFeet = cubicMeters * 35.315f
            String.format(Locale.US, "%.1f ft³", cubicFeet)
        }
    }
}
