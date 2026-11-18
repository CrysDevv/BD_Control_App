package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.network.SupabaseIotBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SimulatorUiState(
    val tempC: Float = 24f,
    val humPct: Float = 55f,
    val isAutoSending: Boolean = false,
    val fanOn: Boolean = false,
    val error: String? = null
)

/**
 * Pantalla de visualización/simulación.
 * La app ya no escribe telemetría ni fan_state en Supabase (es propiedad exclusiva del ESP32).
 */
class SimulatorViewModel(private val bridge: SupabaseIotBridge) : ViewModel() {

    private val _ui = MutableStateFlow(SimulatorUiState())
    val ui: StateFlow<SimulatorUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            bridge.observeStatus().collect { status ->
                _ui.update {
                    it.copy(
                        tempC = status.temp_c?.toFloat() ?: it.tempC,
                        humPct = status.hum_pct?.toFloat() ?: it.humPct,
                        fanOn = status.fan_state ?: false
                    )
                }
            }
        }
    }

    fun setTemp(value: Float) = _ui.update { it.copy(tempC = value) }

    fun setHum(value: Float) = _ui.update { it.copy(humPct = value) }

    fun sendNow() {
        // No-op: La telemetría la envía el ESP32
    }

    fun setAutoSending(enabled: Boolean) {
        _ui.update { it.copy(isAutoSending = enabled) }
    }
}
