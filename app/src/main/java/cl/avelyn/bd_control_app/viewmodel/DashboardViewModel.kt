package cl.avelyn.bd_control_app.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.network.FanCommandSender
import cl.avelyn.bd_control_app.model.network.SensorDataSource
import cl.avelyn.bd_control_app.model.network.UdpMsg
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashUiState(
    val tempC: Float? = null,
    val humPct: Float? = null,
    val fanOn: Boolean? = null,
    val isSensorOnline: Boolean = false,
    val isRelayOnline: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null
)

/**
 * Escucha la telemetría en vivo, la guarda en el historial y envía el comando de
 * encendido/apagado al relé. No conoce el transporte real: [sensorDataSource] y
 * [fanCommandSender] pueden ser Wi-Fi/UDP-TCP (con el ESP32 real) o Supabase
 * (simulando esa comunicación entre dos teléfonos mientras no hay hardware; ver
 * [cl.avelyn.bd_control_app.model.network.SupabaseIotBridge]). Un "watchdog" propio
 * marca sensor/relé como fuera de línea si no llega nada en [OFFLINE_THRESHOLD_MS].
 */
class DashboardViewModel(
    private val sensorDataSource: SensorDataSource,
    private val fanCommandSender: FanCommandSender,
    private val sensorRepository: SensorRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(DashUiState())
    val ui: StateFlow<DashUiState> = _ui.asStateFlow()

    private var lastSensorAtMs = 0L
    private var lastRelayAtMs = 0L

    init {
        viewModelScope.launch {
            sensorDataSource.listen().collect { msg ->
                when (msg) {
                    is UdpMsg.Sensor -> {
                        lastSensorAtMs = SystemClock.elapsedRealtime()
                        _ui.update { it.copy(tempC = msg.tempC, humPct = msg.humPct) }
                        sensorRepository.record(msg)
                    }
                    is UdpMsg.Relay -> {
                        lastRelayAtMs = SystemClock.elapsedRealtime()
                        _ui.update { it.copy(fanOn = msg.fanOn) }
                    }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                _ui.update {
                    it.copy(
                        isSensorOnline = lastSensorAtMs != 0L && now - lastSensorAtMs < OFFLINE_THRESHOLD_MS,
                        isRelayOnline = lastRelayAtMs != 0L && now - lastRelayAtMs < OFFLINE_THRESHOLD_MS
                    )
                }
            }
        }
    }

    fun setFan(on: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(isBusy = true, error = null) }
            fanCommandSender.setFan(on)
                .onSuccess { reply -> _ui.update { it.copy(fanOn = reply.fanOn, isBusy = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(isBusy = false, error = e.message ?: "No se pudo enviar el comando al relé") }
                }
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1000L
        private const val OFFLINE_THRESHOLD_MS = 6000L
    }
}
