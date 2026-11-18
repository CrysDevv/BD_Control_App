package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.AlwaysForeground
import cl.avelyn.bd_control_app.model.AppVisibility
import cl.avelyn.bd_control_app.model.automation.ControlSettings
import cl.avelyn.bd_control_app.model.automation.InMemoryControlSettings
import cl.avelyn.bd_control_app.model.energy.EnergyReport
import cl.avelyn.bd_control_app.model.network.BodegaStatusDto
import cl.avelyn.bd_control_app.model.network.FanCommandSender
import cl.avelyn.bd_control_app.model.network.LinkStats
import cl.avelyn.bd_control_app.model.network.SensorDataSource
import cl.avelyn.bd_control_app.model.network.SerialLinkState
import cl.avelyn.bd_control_app.model.network.SerialSensorPort
import cl.avelyn.bd_control_app.model.network.SupabaseIotBridge
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import cl.avelyn.bd_control_app.model.security.SessionManager
import cl.avelyn.bd_control_app.model.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

data class BodegaStatusUiState(
    val status: BodegaStatusDto? = null,
    val online: Boolean = false,
    val lastSeenFormatted: String? = null,
    val tempC: Double? = null,
    val humPct: Double? = null,
    val fanOn: Boolean = false,
    val isAuto: Boolean = true,
    val pending: Boolean = false,
    val pendingTimeout: Boolean = false,
    val humOnInput: String = "",
    val humOffInput: String = "",
    val isBusy: Boolean = false,
    val userError: String? = null,
    val canControl: Boolean = true
)

class DashboardViewModel(
    val supabaseIotBridge: SupabaseIotBridge,
    private val sensorDataSource: SensorDataSource,
    private val fanCommandSender: FanCommandSender,
    private val sensorRepository: SensorRepository,
    private val session: SessionManager,
    private val serialPort: SerialSensorPort,
    val syncStatus: StateFlow<SyncStatus>,
    private val settings: ControlSettings = InMemoryControlSettings(),
    private val visibility: AppVisibility = AlwaysForeground,
    val energy: StateFlow<EnergyReport> = MutableStateFlow(EnergyReport())
) : ViewModel() {

    val serialState: StateFlow<SerialLinkState> = serialPort.state
    val serialStats: StateFlow<LinkStats> = serialPort.stats

    private val _bodegaState = MutableStateFlow(BodegaStatusUiState())
    val bodegaState: StateFlow<BodegaStatusUiState> = _bodegaState.asStateFlow()

    private var pendingStartMs: Long? = null
    private var isUserEditingThresholds = false

    init {
        // Escucha cambios en tiempo real / polling de bodega_status
        viewModelScope.launch {
            supabaseIotBridge.observeStatus().collect { status ->
                onStatusUpdated(status)
            }
        }

        // Vigilante: recalcula 'online' y 'pending' cada 5 s (last_seen envejece)
        viewModelScope.launch {
            while (isActive) {
                delay(5000L)
                recalculateDerivedState()
            }
        }
    }

    private fun onStatusUpdated(status: BodegaStatusDto) {
        val nowMs = System.currentTimeMillis()
        val lastSeenMs = parseLastSeen(status.last_seen)
        val isOnline = lastSeenMs != null && (nowMs - lastSeenMs) < ONLINE_THRESHOLD_MS
        val isAuto = status.isAuto
        val fanOn = status.fan_state == true

        val (pending, pendingTimeout) = checkPending(status, isOnline, nowMs)
        val canControl = session.policy.canControl("esp32") || session.policy.canControl("bodega_status")

        _bodegaState.update { current ->
            current.copy(
                status = status,
                online = isOnline,
                lastSeenFormatted = if (!isOnline) formatLastSeenLocal(status.last_seen) else null,
                tempC = status.temp_c,
                humPct = status.hum_pct,
                fanOn = fanOn,
                isAuto = isAuto,
                pending = pending,
                pendingTimeout = pendingTimeout,
                humOnInput = if (!isUserEditingThresholds) status.hum_on?.let { "%.1f".format(Locale.US, it) } ?: current.humOnInput else current.humOnInput,
                humOffInput = if (!isUserEditingThresholds) status.hum_off?.let { "%.1f".format(Locale.US, it) } ?: current.humOffInput else current.humOffInput,
                canControl = canControl
            )
        }
    }

    private fun recalculateDerivedState() {
        val current = _bodegaState.value
        val status = current.status ?: return
        val nowMs = System.currentTimeMillis()
        val lastSeenMs = parseLastSeen(status.last_seen)
        val isOnline = lastSeenMs != null && (nowMs - lastSeenMs) < ONLINE_THRESHOLD_MS

        val (pending, pendingTimeout) = checkPending(status, isOnline, nowMs)

        _bodegaState.update {
            it.copy(
                online = isOnline,
                lastSeenFormatted = if (!isOnline) formatLastSeenLocal(status.last_seen) else null,
                pending = pending,
                pendingTimeout = pendingTimeout
            )
        }
    }

    private fun checkPending(status: BodegaStatusDto, online: Boolean, nowMs: Long): Pair<Boolean, Boolean> {
        if (!online) {
            pendingStartMs = null
            return Pair(false, false)
        }
        val isManual = !status.isAuto
        val fanCommand = status.fan_command
        val fanState = status.fan_state

        val isPending = isManual && fanCommand != null && fanState != null && fanCommand != fanState
        if (isPending) {
            if (pendingStartMs == null) {
                pendingStartMs = nowMs
            }
            val duration = nowMs - (pendingStartMs ?: nowMs)
            val isTimeout = duration > PENDING_TIMEOUT_MS
            return Pair(true, isTimeout)
        } else {
            pendingStartMs = null
            return Pair(false, false)
        }
    }

    fun updateHumOnInput(input: String) {
        isUserEditingThresholds = true
        _bodegaState.update { it.copy(humOnInput = input) }
    }

    fun updateHumOffInput(input: String) {
        isUserEditingThresholds = true
        _bodegaState.update { it.copy(humOffInput = input) }
    }

    fun setFanManual(on: Boolean) {
        if (_bodegaState.value.isBusy || !_bodegaState.value.online || !_bodegaState.value.canControl) return

        viewModelScope.launch {
            _bodegaState.update { it.copy(isBusy = true, userError = null) }
            supabaseIotBridge.setFanManual(on)
                .onSuccess {
                    _bodegaState.update { it.copy(isBusy = false) }
                }
                .onFailure { e ->
                    _bodegaState.update { it.copy(isBusy = false, userError = e.message ?: "No se pudo enviar la orden") }
                }
        }
    }

    fun setAuto() {
        if (_bodegaState.value.isBusy || !_bodegaState.value.online || !_bodegaState.value.canControl) return

        viewModelScope.launch {
            _bodegaState.update { it.copy(isBusy = true, userError = null) }
            supabaseIotBridge.setAuto()
                .onSuccess {
                    _bodegaState.update { it.copy(isBusy = false) }
                }
                .onFailure { e ->
                    _bodegaState.update { it.copy(isBusy = false, userError = e.message ?: "No se pudo activar modo automático") }
                }
        }
    }

    fun setManualMode() {
        val currentFanState = _bodegaState.value.fanOn
        setFanManual(currentFanState)
    }

    fun saveThresholds() {
        val s = _bodegaState.value
        if (s.isBusy || !s.online || !s.canControl) return

        val onVal = s.humOnInput.toFloatOrNull()
        val offVal = s.humOffInput.toFloatOrNull()

        if (onVal == null || offVal == null || onVal !in 0f..100f || offVal !in 0f..100f) {
            _bodegaState.update { it.copy(userError = "Ingresa valores numéricos válidos (0 - 100)") }
            return
        }

        if (onVal <= offVal) {
            _bodegaState.update { it.copy(userError = "La humedad de encendido (hum_on) debe ser mayor que la de apagado (hum_off)") }
            return
        }

        viewModelScope.launch {
            _bodegaState.update { it.copy(isBusy = true, userError = null) }
            supabaseIotBridge.setThresholds(onVal, offVal)
                .onSuccess {
                    isUserEditingThresholds = false
                    _bodegaState.update { it.copy(isBusy = false) }
                }
                .onFailure { e ->
                    _bodegaState.update { it.copy(isBusy = false, userError = e.message ?: "No se pudieron guardar los umbrales") }
                }
        }
    }

    fun clearUserError() {
        _bodegaState.update { it.copy(userError = null) }
    }

    fun logout() = session.close()

    companion object {
        private const val ONLINE_THRESHOLD_MS = 20_000L
        private const val PENDING_TIMEOUT_MS = 15_000L

        private fun parseLastSeen(isoStr: String?): Long? {
            if (isoStr.isNullOrBlank()) return null
            return try {
                Instant.parse(isoStr).toEpochMilli()
            } catch (_: Exception) {
                try {
                    OffsetDateTime.parse(isoStr).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun formatLastSeenLocal(isoStr: String?): String? {
            val epochMs = parseLastSeen(isoStr) ?: return null
            val date = Date(epochMs)
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            return formatter.format(date)
        }
    }
}
