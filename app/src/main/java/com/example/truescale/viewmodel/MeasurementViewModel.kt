// File: MeasurementViewModel.kt (Complete)
package com.truescale.app.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.truescale.app.utils.Vector3

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

    private val _trackingState = MutableLiveData(TrackingState.STOPPED)
    val trackingState: LiveData<TrackingState> = _trackingState

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
        tempStartPoint = point
        tempStartAnchor = anchor
        _currentMeasurement.value = null
    }

    fun setEndPoint(point: Vector3, anchor: Anchor? = null) {
        tempStartPoint?.let { start ->
            val measurement = Measurement(
                startPoint = start,
                endPoint = point,
                startAnchor = tempStartAnchor,
                endAnchor = anchor
            )

            _currentMeasurement.value = measurement
            addToHistory(measurement)

            tempStartPoint = null
            tempStartAnchor = null
        }
    }

    private fun addToHistory(measurement: Measurement) {
        val currentHistory = _measurementHistory.value ?: emptyList()
        val updatedHistory = currentHistory + measurement

        _measurementHistory.value = if (updatedHistory.size > 10) {
            updatedHistory.takeLast(10)
        } else {
            updatedHistory
        }
    }

    fun clearMeasurements() {
        _currentMeasurement.value = null
        tempStartPoint = null
        tempStartAnchor?.detach()
        tempStartAnchor = null

        _currentMeasurement.value?.let { measurement ->
            measurement.startAnchor?.detach()
            measurement.endAnchor?.detach()
        }
    }

    fun clearHistory() {
        _measurementHistory.value?.forEach { measurement ->
            measurement.startAnchor?.detach()
            measurement.endAnchor?.detach()
        }
        _measurementHistory.value = emptyList()
    }

    fun toggleUnits() {
        _useMetric.value = !(_useMetric.value ?: true)
    }

    fun setUseMetric(useMetric: Boolean) {
        _useMetric.value = useMetric
    }

    fun updateTrackingState(state: TrackingState) {
        _trackingState.value = state
    }

    fun setMeasurementMode(mode: MeasurementMode) {
        _measurementMode.value = mode
        clearMeasurements()
    }

    fun setTutorialShown(shown: Boolean) {
        _showTutorial.value = !shown
    }

    fun getLastMeasurement(): Measurement? {
        return _measurementHistory.value?.lastOrNull()
    }

    fun isMeasuring(): Boolean {
        return tempStartPoint != null && _currentMeasurement.value == null
    }

    override fun onCleared() {
        super.onCleared()
        clearMeasurements()
        clearHistory()
    }
}