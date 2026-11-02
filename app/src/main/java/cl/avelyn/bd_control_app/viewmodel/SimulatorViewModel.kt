package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.network.SupabaseIotBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SimulatorUiState(
    val tempC: Float = 24f,
    val humPct: Float = 55f,
    val isAutoSending: Boolean = false,
    val fanOn: Boolean = false,
    val error: String? = null
)

/**
 * Pantalla de desarrollador que hace de "dispositivo que envía los datos": simula, en un
 * segundo teléfono, lo que harían los dos ESP32 combinados. Publica temperatura y humedad
 * en Supabase (como el sensor) y aplica el comando del ventilador que llegue desde la app
 * de monitoreo (como el relé), confirmando el nuevo estado.
 *
 * Es temporal: cuando llegue el hardware real, esta pantalla y este ViewModel se eliminan
 * y la telemetría pasa a venir directamente del ESP32 por Wi-Fi (ver [DashboardViewModel]).
 */
class SimulatorViewModel(private val bridge: SupabaseIotBridge) : ViewModel() {

    private val _ui = MutableStateFlow(SimulatorUiState())
    val ui: StateFlow<SimulatorUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            bridge.listenForCommands().collect { fan ->
                _ui.update { it.copy(fanOn = fan) }
                // confirma, como lo haría el relé tras mover el físico
                runCatching { bridge.reportState(fan) }
                    .onFailure { e -> _ui.update { it.copy(error = e.message) } }
            }
        }
    }

    fun setTemp(value: Float) = _ui.update { it.copy(tempC = value) }

    fun setHum(value: Float) = _ui.update { it.copy(humPct = value) }

    fun sendNow() {
        viewModelScope.launch {
            val s = _ui.value
            runCatching { bridge.publishTelemetry(s.tempC, s.humPct) }
                .onSuccess { _ui.update { it.copy(error = null) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    /** Al activarlo, reenvía la lectura actual cada 2 s, igual que lo haría el DHT11 real. */
    fun setAutoSending(enabled: Boolean) {
        _ui.update { it.copy(isAutoSending = enabled) }
        if (enabled) startAutoLoop()
    }

    private fun startAutoLoop() {
        viewModelScope.launch {
            while (isActive && _ui.value.isAutoSending) {
                sendNow()
                delay(AUTO_SEND_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val AUTO_SEND_INTERVAL_MS = 2000L
    }
}
