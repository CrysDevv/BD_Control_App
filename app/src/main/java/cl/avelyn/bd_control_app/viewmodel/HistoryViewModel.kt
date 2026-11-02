package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryUiState(val logs: List<SensorLog> = emptyList())

/** Expone el historial de temperatura y humedad como estado observable. */
class HistoryViewModel(sensorRepository: SensorRepository) : ViewModel() {

    val ui: StateFlow<HistoryUiState> = sensorRepository.latest()
        .map { HistoryUiState(logs = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HistoryUiState()
        )

    companion object {
        private const val STOP_TIMEOUT_MS = 5000L
    }
}
