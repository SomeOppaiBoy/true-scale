// File: MeasurementViewModel.kt
package com.example.truescale.viewmodel // <-- CORRECTED PACKAGE

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.example.truescale.utils.Vector3 // <-- CORRECTED PACKAGE

class MeasurementViewModel : ViewModel() {

    data class Measurement(
        val startPoint: Vector3,
        val endPoint: Vector3,
        val startAnchor: Anchor? = null,
        val endAnchor: Anchor? = null,
        val confidence: Float = 1.0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _currentMeasurement = MutableLiveData<Measurement?>()
    val currentMeasurement: LiveData<Measurement?> = _currentMeasurement

    private val _measurementHistory = MutableLiveData<List<Measurement>>(emptyList())
    val measurementHistory: LiveData<List<Measurement>> = _measurementHistory

    private val _useMetric = MutableLiveData(true)
    val useMetric: LiveData<Boolean> = _useMetric

    private val _trackingState = MutableLiveData<TrackingState?>(null) // Allow null initially
    val trackingState: LiveData<TrackingState?> = _trackingState

    private val _showTutorial = MutableLiveData(true)
    val showTutorial: LiveData<Boolean> = _showTutorial

    enum class MeasurementMode {
        DISTANCE, HEIGHT, AREA, VOLUME
    }

    private val _measurementMode = MutableLiveData(MeasurementMode.DISTANCE)
    val measurementMode: LiveData<MeasurementMode> = _measurementMode

    private var tempStartPoint: Vector3? = null
    private var tempStartAnchor: Anchor? = null

    fun setStartPoint(point: Vector3, anchor: Anchor? = null) {
        // Detach previous temporary anchor if any
        tempStartAnchor?.detach()

        tempStartPoint = point
        tempStartAnchor = anchor
        _currentMeasurement.value = null // Clear current measurement display when starting new
    }

    fun setEndPoint(point: Vector3, anchor: Anchor? = null) {
        tempStartPoint?.let { start ->
            // Calculate confidence (example, replace with actual logic if needed)
            val calculatedConfidence = (tempStartAnchor?.trackingState?.ordinal ?: 0 + (anchor?.trackingState?.ordinal ?: 0)) / 4.0f // Simple confidence based on tracking state

            val measurement = Measurement(
                startPoint = start,
                endPoint = point,
                startAnchor = tempStartAnchor,
                endAnchor = anchor,
                confidence = calculatedConfidence.coerceIn(0f, 1f)
            )

            _currentMeasurement.value = measurement
            addToHistory(measurement)

            // Reset temporary holders *after* creating the measurement
            tempStartPoint = null
            tempStartAnchor = null // Keep anchors attached until explicitly cleared
        }
    }


    private fun addToHistory(measurement: Measurement) {
        val currentHistory = _measurementHistory.value ?: emptyList()
        // Ensure anchors in history are managed correctly (e.g., limit history size)
        val updatedHistory = (currentHistory + measurement).takeLast(10) // Limit history

        // Detach anchors from measurements removed from history
        (currentHistory - updatedHistory.toSet()).forEach { oldMeasurement ->
            oldMeasurement.startAnchor?.detach()
            oldMeasurement.endAnchor?.detach()
        }

        _measurementHistory.value = updatedHistory
    }

    fun clearMeasurements() {
        // Clear the currently displayed measurement
        _currentMeasurement.value = null

        // Clear any pending start point
        tempStartPoint = null
        tempStartAnchor?.detach()
        tempStartAnchor = null

        // Note: We don't clear history here, only the active measurement attempt
    }


    fun clearHistory() {
        _measurementHistory.value?.forEach { measurement ->
            measurement.startAnchor?.detach()
            measurement.endAnchor?.detach()
        }
        _measurementHistory.value = emptyList()
        // Also clear the current measurement display if history is cleared
        _currentMeasurement.value = null
    }

    fun toggleUnits() {
        _useMetric.value = !(_useMetric.value ?: true)
    }

    fun setUseMetric(useMetric: Boolean) {
        _useMetric.value = useMetric
    }

    fun updateTrackingState(state: TrackingState?) {
        if (_trackingState.value != state) {
            _trackingState.value = state
        }
    }

    fun setMeasurementMode(mode: MeasurementMode) {
        if (_measurementMode.value != mode) {
            _measurementMode.value = mode
            clearMeasurements() // Clear temp points when changing mode
        }
    }

    fun setTutorialShown(shown: Boolean) {
        _showTutorial.value = !shown
    }

    fun getLastMeasurement(): Measurement? {
        return _measurementHistory.value?.lastOrNull()
    }

    fun isMeasuring(): Boolean {
        // Actively measuring if a start point is set but no final measurement is stored
        return tempStartPoint != null
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure all anchors are detached when ViewModel is destroyed
        clearMeasurements()
        clearHistory()
        tempStartAnchor?.detach() // Final check for temp anchor
    }
}
