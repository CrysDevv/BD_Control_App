package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import cl.avelyn.bd_control_app.model.security.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [deviceIds]: sensores con historial. [selectedId]: el que se está mostrando (null si aún no hay ninguno).
 * Se muestra un sensor a la vez porque mezclar lecturas de sensores distintos en un mismo gráfico no tiene sentido.
 */
data class HistoryUiState(
    val deviceIds: List<String> = emptyList(),
    val selectedId: String? = null,
    val logs: List<SensorLog> = emptyList()
)

/** Expone el historial del sensor elegido (solo entre los que el usuario puede consultar) como estado observable. */
class HistoryViewModel(sensorRepository: SensorRepository, session: SessionManager) : ViewModel() {

    private val requestedId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<HistoryUiState> = combine(sensorRepository.deviceIds(), requestedId) { allIds, requested ->
        // El historial guarda lo de todos los sensores; cada usuario solo ve los que tiene permiso de consultar.
        val ids = allIds.filter { session.policy.canView(it) }
        // Si el sensor pedido ya no tiene historial (o no se ha elegido uno), se usa el primero.
        ids to (requested?.takeIf { it in ids } ?: ids.firstOrNull())
    }
        .flatMapLatest { (ids, selected) ->
            if (selected == null) {
                flowOf(HistoryUiState(deviceIds = ids))
            } else {
                sensorRepository.latest(selected).map { HistoryUiState(deviceIds = ids, selectedId = selected, logs = it) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HistoryUiState()
        )

    fun selectDevice(id: String) {
        requestedId.value = id
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5000L
    }
}
