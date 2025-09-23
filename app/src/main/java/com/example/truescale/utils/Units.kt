// File: Units.kt
package com.truescale.app.utils

import java.util.Locale

/**
 * Units - Handle unit conversions and formatting
 * 
 * Converts between metric and imperial units
 * and formats measurements for display.
 */
object Units {
    
    // Conversion constants
    const val METERS_TO_FEET = 3.28084f
    const val METERS_TO_INCHES = 39.3701f
    const val METERS_TO_CM = 100f
    
    /**
     * Format distance in metric units (auto-select cm or m)
     * 
     * @param meters Distance in meters
     * @return Formatted string with appropriate unit
     */
    fun formatMetric(meters: Float): String {
        return when {
            meters < 0.01f -> {
                // Very small distances in mm
                val mm = meters * 1000
                String.format(Locale.US, "%.1f mm", mm)
            }
            meters < 1.0f -> {
                // Use centimeters for distances less than 1 meter
                val cm = meters * METERS_TO_CM
                String.format(Locale.US, "%.1f cm", cm)
            }
            else -> {
                // Use meters for larger distances
                String.format(Locale.US, "%.2f m", meters)
            }
        }
    }
    
    /**
     * Format distance in imperial units (auto-select inches or feet)
     * 
     * @param meters Distance in meters
     * @return Formatted string with appropriate unit
     */
    fun formatImperial(meters: Float): String {
        val totalInches = meters * METERS_TO_INCHES
        
        return when {
            totalInches < 12f -> {
                // Use inches for small distances
                String.format(Locale.US, "%.1f\"", totalInches)
            }
            totalInches < 36f -> {
                // Use feet and inches for medium distances
                val feet = (totalInches / 12).toInt()
                val inches = totalInches % 12
                String.format(Locale.US, "%d' %.1f\"", feet, inches)
            }
            else -> {
                // Use just feet for larger distances
                val feet = totalInches / 12
                String.format(Locale.US, "%.1f'", feet)
            }
        }
    }
    
    /**
     * Convert meters to centimeters
     */
    fun metersToCentimeters(meters: Float): Float = meters * METERS_TO_CM
    
    /**
     * Convert meters to feet
     */
    fun metersToFeet(meters: Float): Float = meters * METERS_TO_FEET
    
    /**
     * Convert meters to inches
     */
    fun metersToInches(meters: Float): Float = meters * METERS_TO_INCHES
    
    /**
     * Convert centimeters to meters
     */
    fun centimetersToMeters(cm: Float): Float = cm / METERS_TO_CM
    
    /**
     * Convert feet to meters
     */
    fun feetToMeters(feet: Float): Float = feet / METERS_TO_FEET
    
    /**
     * Convert inches to meters
     */
    fun inchesToMeters(inches: Float): Float = inches / METERS_TO_INCHES
    
    /**
     * Format area in appropriate units
     * 
     * @param squareMeters Area in square meters
     * @param useMetric Use metric (true) or imperial (false)
     * @return Formatted area string
     */
    fun formatArea(squareMeters: Float, useMetric: Boolean): String {
        return if (useMetric) {
            when {
                squareMeters < 0.01f -> {
                    val cm2 = squareMeters * 10000
                    String.format(Locale.US, "%.1f cm²", cm2)
                }
                else -> {
                    String.format(Locale.US, "%.2f m²", squareMeters)
                }
            }
        } else {
            val squareFeet = squareMeters * 10.764f
            String.format(Locale.US, "%.1f ft²", squareFeet)
        }
    }
    
    /**
     * Format volume in appropriate units
     * 
     * @param cubicMeters Volume in cubic meters
     * @param useMetric Use metric (true) or imperial (false)
     * @return Formatted volume string
     */
    fun formatVolume(cubicMeters: Float, useMetric: Boolean): String {
        return if (useMetric) {
            when {
                cubicMeters < 0.001f -> {
                    val cm3 = cubicMeters * 1000000
                    String.format(Locale.US, "%.1f cm³", cm3)
                }
                cubicMeters < 1f -> {
                    val liters = cubicMeters * 1000
                    String.format(Locale.US, "%.1f L", liters)
                }
                else -> {
                    String.format(Locale.US, "%.2f m³", cubicMeters)
                }
            }
        } else {
            val cubicFeet = cubicMeters * 35.315f
            String.format(Locale.US, "%.1f ft³", cubicFeet)
        }
    }
}