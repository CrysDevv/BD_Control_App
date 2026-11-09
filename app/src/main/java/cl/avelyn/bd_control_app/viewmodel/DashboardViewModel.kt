package cl.avelyn.bd_control_app.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.AlwaysForeground
import cl.avelyn.bd_control_app.model.AppVisibility
import cl.avelyn.bd_control_app.model.alerts.Alert
import cl.avelyn.bd_control_app.model.alerts.AlertInputs
import cl.avelyn.bd_control_app.model.alerts.RelayAlertInfo
import cl.avelyn.bd_control_app.model.alerts.SensorAlertInfo
import cl.avelyn.bd_control_app.model.alerts.buildAlerts
import cl.avelyn.bd_control_app.model.automation.AutomationConfig
import cl.avelyn.bd_control_app.model.automation.AutomationController
import cl.avelyn.bd_control_app.model.automation.AutomationStatus
import cl.avelyn.bd_control_app.model.automation.ControlAction
import cl.avelyn.bd_control_app.model.automation.ControlSettings
import cl.avelyn.bd_control_app.model.automation.ControlSnapshot
import cl.avelyn.bd_control_app.model.automation.InMemoryControlSettings
import cl.avelyn.bd_control_app.model.automation.RelayView
import cl.avelyn.bd_control_app.model.automation.SensorView
import cl.avelyn.bd_control_app.model.energy.AdaptiveConfig
import cl.avelyn.bd_control_app.model.energy.AdaptiveSampler
import cl.avelyn.bd_control_app.model.energy.EnergyReport
import cl.avelyn.bd_control_app.model.energy.HumidityPoint
import cl.avelyn.bd_control_app.model.network.DeviceType
import cl.avelyn.bd_control_app.model.network.FanCommandSender
import cl.avelyn.bd_control_app.model.network.LinkStats
import cl.avelyn.bd_control_app.model.network.SensorDataSource
import cl.avelyn.bd_control_app.model.network.SerialLinkState
import cl.avelyn.bd_control_app.model.network.SerialSensorPort
import cl.avelyn.bd_control_app.model.network.UdpMsg
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import cl.avelyn.bd_control_app.model.security.SessionManager
import cl.avelyn.bd_control_app.model.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Estado de un ESP32 (sensor o relé) descubierto en la red. */
data class DeviceUi(
    val id: String,
    val type: DeviceType,
    val tempC: Float? = null,
    val humPct: Float? = null,
    val fanOn: Boolean? = null,
    /** Si el usuario tiene permiso para controlar este dispositivo (si no, sus controles quedan en solo lectura). */
    val canControl: Boolean = false,
    /** El sensor está conectado por el cable USB-C (serial); solo esos admiten configuración remota. */
    val viaSerial: Boolean = false,
    /** Cada cuánto mide el sensor (ms); solo se conoce en los sensores por cable. */
    val sampleIntervalMs: Int? = null,
    /** Relé: el ventilador lo gobierna la humedad (modo automático). */
    val automatic: Boolean = false,
    val automationStatus: AutomationStatus = AutomationStatus.OFF,
    /** Relé: está en modo seguro (dejó de oír a la app y ventila por ciclos por su cuenta). */
    val failSafe: Boolean = false,
    val isOnline: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null
)

data class DashUiState(
    val devices: List<DeviceUi> = emptyList(),
    /** Avisos para el operador (parte visible del plan de contingencia). */
    val alerts: List<Alert> = emptyList()
)

/**
 * Escucha la telemetría en vivo de TODOS los dispositivos, guarda las lecturas de los sensores en el historial,
 * gobierna los relés (a mano o en automático) y avisa de lo que falla.
 *
 * **Solo trabaja con la app a la vista.** En segundo plano se cierran el puerto USB y el socket y se detienen los
 * temporizadores (ahorra batería). No se pierde nada: el sensor guarda lecturas en su buffer y se recuperan al volver; y
 * el relé, al no oír latidos, entra en modo seguro si pasa mucho rato. Ver `docs/plan_contingencia.md`.
 *
 * Con la app a la vista corren en paralelo:
 * - **Ingesta:** recibe mensajes, los guarda y, recién entonces, se lo confirma al sensor.
 * - **Vigilante (1 s):** marca cada dispositivo en línea o sin conexión y actualiza las alertas.
 * - **Lazo de control (5 s):** [AutomationController] decide ventilador y latidos; aquí se ejecutan.
 * - **Muestreo adaptativo (30 s):** con el modo ahorro, ajusta cada cuánto mide el sensor según la humedad.
 *
 * Solo recibe dispositivos que el usuario puede consultar y solo comanda los que puede controlar (lo garantizan los
 * decoradores de acceso). No conoce el transporte real.
 */
@OptIn(FlowPreview::class)
class DashboardViewModel(
    private val sensorDataSource: SensorDataSource,
    private val fanCommandSender: FanCommandSender,
    private val sensorRepository: SensorRepository,
    private val session: SessionManager,
    private val serialPort: SerialSensorPort,
    /** Estado de la sincronización con la nube (lecturas pendientes, sin internet, errores). */
    val syncStatus: StateFlow<SyncStatus>,
    private val settings: ControlSettings = InMemoryControlSettings(),
    private val visibility: AppVisibility = AlwaysForeground,
    /** Batería del teléfono (ver EnergyMonitor). */
    val energy: StateFlow<EnergyReport> = MutableStateFlow(EnergyReport()),
    private val timing: Timing = Timing(),
    automationConfig: AutomationConfig = AutomationConfig(),
    adaptiveConfig: AdaptiveConfig = AdaptiveConfig(),
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) : ViewModel() {

    /** Periodos de los ciclos internos; en pruebas se acortan. */
    data class Timing(
        val watchdogMs: Long = 1_000,
        val controlTickMs: Long = 5_000,
        val adaptiveTickMs: Long = 30_000,
        /** Tras pasar a segundo plano, cuánto esperar antes de dejar de escuchar (evita cortes por interrupciones breves). */
        val backgroundGraceMs: Long = 3_000,
        /** Tras elegir el usuario un intervalo a mano, cuánto no tocarlo el ahorro automático. */
        val manualHoldMs: Long = 10 * 60_000,
        val failSafeSilenceMinutes: Int = 10
    )

    /** Estado del cable USB y calidad del enlace (tramas dañadas, perdidas, reconexiones). */
    val serialState: StateFlow<SerialLinkState> = serialPort.state
    val serialStats: StateFlow<LinkStats> = serialPort.stats
    val powerSaving: StateFlow<Boolean> = settings.powerSaving

    private val _ui = MutableStateFlow(DashUiState())
    val ui: StateFlow<DashUiState> = _ui.asStateFlow()

    /** Último mensaje recibido de cada dispositivo. Solo se toca desde viewModelScope (hilo principal). */
    private val lastSeenMs = mutableMapOf<String, Long>()
    private val humidityHistory = mutableMapOf<String, ArrayDeque<HumidityPoint>>()
    private val manualHoldUntil = mutableMapOf<String, Long>()
    private val pausedSince = mutableMapOf<String, Long>()

    private val controller = AutomationController(automationConfig)
    private val sampler = AdaptiveSampler(adaptiveConfig, automationConfig.humidityOnPct, automationConfig.humidityOffPct)

    init {
        viewModelScope.launch {
            // El intervalo de medición lo reporta el sensor al conectar y se actualiza al cambiarlo.
            serialPort.state.collect { st ->
                if (st is SerialLinkState.Connected && st.deviceId != null) {
                    updateDevice(st.deviceId) { it.copy(sampleIntervalMs = st.intervalMs) }
                }
            }
        }
        viewModelScope.launch {
            settings.automaticRelays.collect { auto -> _ui.update { s -> s.copy(devices = s.devices.map { it.copy(automatic = it.id in auto) }) } }
        }
        viewModelScope.launch {
            // Primer plano: sube de inmediato; segundo plano: solo tras un rato (no cortar el enlace por una interrupción breve).
            visibility.isForeground.debounce { if (it) 0L else timing.backgroundGraceMs }.collectLatest { foreground ->
                if (!foreground) return@collectLatest
                coroutineScope {
                    launch { ingest() }
                    launch { watchdog() }
                    launch { controlLoop() }
                    launch { adaptiveLoop() }
                }
            }
        }
    }

    // ------------------------------------------------------------------ ingesta

    private suspend fun ingest() {
        sensorDataSource.listen().collect { msg ->
            lastSeenMs[msg.deviceId] = clockMs()
            applyMessage(msg)
            if (msg is UdpMsg.Sensor) persist(msg)
        }
    }

    /**
     * Guarda la lectura y, SOLO si quedó guardada (o se descartó a propósito), se lo confirma al sensor para que la
     * libere de su buffer. Si guardar falla, el sensor la conserva y se la reenvía: no se pierde. Un error de base de
     * datos no debe tumbar la escucha de los demás dispositivos.
     */
    private suspend fun persist(msg: UdpMsg.Sensor) {
        try {
            sensorRepository.record(msg)
            msg.sample?.let { serialPort.markStored(msg.deviceId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DashboardViewModel", "No se pudo guardar la lectura de ${msg.deviceId}: ${e.message}")
        }
    }

    private fun applyMessage(msg: UdpMsg) {
        if (msg is UdpMsg.Sensor && msg.capturedAtMs == null) recordHumidity(msg.deviceId, msg.humPct)
        _ui.update { state ->
            val current = state.devices.firstOrNull { it.id == msg.deviceId }
            val updated = when (msg) {
                is UdpMsg.Sensor -> (current ?: DeviceUi(msg.deviceId, DeviceType.SENSOR)).copy(
                    // una lectura recuperada del buffer es VIEJA: no debe mostrarse como el valor actual
                    tempC = if (msg.capturedAtMs == null) msg.tempC else current?.tempC,
                    humPct = if (msg.capturedAtMs == null) msg.humPct else current?.humPct,
                    isOnline = true,
                    canControl = session.policy.canControl(msg.deviceId),
                    viaSerial = msg.viaSerial,
                    sampleIntervalMs = if (msg.viaSerial) intervalReportedBy(msg.deviceId) else null
                )
                is UdpMsg.Relay -> (current ?: DeviceUi(msg.deviceId, DeviceType.RELAY, automatic = msg.deviceId in settings.automaticRelays.value))
                    .copy(
                        fanOn = msg.fanOn,
                        failSafe = msg.failSafe,
                        isOnline = true,
                        canControl = session.policy.canControl(msg.deviceId)
                    )
            }
            state.copy(devices = sorted(state.devices.filterNot { it.id == msg.deviceId } + updated))
        }
    }

    private fun recordHumidity(sensorId: String, hum: Float) {
        val now = clockMs()
        val h = humidityHistory.getOrPut(sensorId) { ArrayDeque() }
        h.addLast(HumidityPoint(now, hum))
        while (h.isNotEmpty() && now - h.first().timeMs > HISTORY_KEEP_MS) h.removeFirst()
    }

    // ------------------------------------------------------------------ vigilante

    private suspend fun watchdog() {
        while (currentCoroutineContext().isActive) {
            delay(timing.watchdogMs)
            refreshOnlineFlags()
            refreshAlerts()
        }
    }

    private fun refreshOnlineFlags() {
        val now = clockMs()
        val usbConnected = serialPort.state.value is SerialLinkState.Connected
        _ui.update { state ->
            state.copy(devices = state.devices.map { device ->
                val threshold = when {
                    device.type == DeviceType.RELAY -> OFFLINE_THRESHOLD_RELAY_MS
                    // con intervalos largos (hasta 10 min) no se puede exigir un dato cada 6 s
                    device.viaSerial -> maxOf(OFFLINE_THRESHOLD_SENSOR_MS, 3L * (device.sampleIntervalMs ?: 0))
                    else -> OFFLINE_THRESHOLD_SENSOR_MS
                }
                val seen = lastSeenMs[device.id] ?: 0L
                // cable desenchufado => sin conexión al instante, sin esperar el tiempo de gracia
                val linkOk = !device.viaSerial || usbConnected
                device.copy(isOnline = linkOk && seen != 0L && now - seen < threshold)
            })
        }
    }

    private fun refreshAlerts() {
        val now = clockMs()
        val devices = _ui.value.devices
        val state = serialPort.state.value
        val stats = serialPort.stats.value
        val sync = syncStatus.value
        val relays = devices.filter { it.type == DeviceType.RELAY }.map { r ->
            val since = pausedSince[r.id]
            val remaining = if (since == null) timing.failSafeSilenceMinutes
            else maxOf(0, timing.failSafeSilenceMinutes - ((now - since) / 60_000).toInt())
            RelayAlertInfo(r.id, r.isOnline, r.failSafe, r.automationStatus, remaining)
        }
        val alerts = buildAlerts(
            AlertInputs(
                sensors = devices.filter { it.type == DeviceType.SENSOR }.map { SensorAlertInfo(it.id, it.isOnline) },
                relays = relays,
                usbExpected = state !is SerialLinkState.Unavailable &&
                    (devices.any { it.viaSerial } || state is SerialLinkState.PermissionRequired || state is SerialLinkState.Failed),
                usbConnected = state is SerialLinkState.Connected,
                usbPermissionPending = state is SerialLinkState.PermissionRequired,
                sensorBufferDropped = stats.sensorDropped,
                syncPending = sync.pending,
                syncOnline = sync.isOnline,
                syncError = sync.lastError,
                energy = energy.value
            )
        )
        if (alerts != _ui.value.alerts) _ui.update { it.copy(alerts = alerts) }
    }

    // ------------------------------------------------------------------ lazo de control

    private suspend fun controlLoop() {
        while (currentCoroutineContext().isActive) {
            delay(timing.controlTickMs)
            controlTick()
        }
    }

    private suspend fun controlTick() {
        val now = clockMs()
        val devices = _ui.value.devices
        val auto = settings.automaticRelays.value
        val snapshot = ControlSnapshot(
            nowMs = now,
            sensors = devices.filter { it.type == DeviceType.SENSOR }.map {
                SensorView(it.id, it.humPct, it.isOnline, now - (lastSeenMs[it.id] ?: 0L))
            },
            relays = devices.filter { it.type == DeviceType.RELAY }.map {
                RelayView(it.id, it.fanOn, it.isOnline, it.canControl, automatic = it.id in auto, failSafe = it.failSafe)
            }
        )
        val result = controller.tick(snapshot)

        for ((id, status) in result.statuses) {
            if (status == AutomationStatus.PAUSED_NO_DATA) pausedSince.putIfAbsent(id, now) else pausedSince.remove(id)
        }
        _ui.update { s -> s.copy(devices = s.devices.map { d -> result.statuses[d.id]?.let { d.copy(automationStatus = it) } ?: d }) }

        for (action in result.actions) {
            when (action) {
                is ControlAction.SetFan -> fanCommandSender.setFan(action.relayId, action.on)
                    .onSuccess { reply ->
                        controller.onFanChanged(action.relayId, clockMs())
                        updateDevice(action.relayId) { it.copy(fanOn = reply.fanOn, failSafe = reply.failSafe, error = null) }
                    }
                    .onFailure { e -> updateDevice(action.relayId) { it.copy(error = "Automático: ${e.message ?: "no se pudo enviar la orden"}") } }
                is ControlAction.Heartbeat -> fanCommandSender.heartbeat(action.relayId)
                    .onSuccess { reply -> updateDevice(action.relayId) { it.copy(fanOn = reply.fanOn, failSafe = reply.failSafe) } }
                    .onFailure { e -> Log.w("DashboardViewModel", "Latido a ${action.relayId} falló: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ muestreo adaptativo (ahorro de energía)

    private suspend fun adaptiveLoop() {
        while (currentCoroutineContext().isActive) {
            delay(timing.adaptiveTickMs)
            if (settings.powerSaving.value) adaptiveTick()
        }
    }

    private suspend fun adaptiveTick() {
        val now = clockMs()
        for (device in _ui.value.devices) {
            if (device.type != DeviceType.SENSOR || !device.viaSerial || !device.isOnline || !device.canControl) continue
            if (now < (manualHoldUntil[device.id] ?: 0L)) continue          // el usuario eligió un intervalo a mano: se respeta
            val history = humidityHistory[device.id]?.toList() ?: continue
            val target = sampler.recommend(device.id, history, device.sampleIntervalMs, now) ?: continue
            serialPort.setSampleInterval(device.id, target)
                .onSuccess {
                    sampler.applied(device.id, clockMs())
                    updateDevice(device.id) { it.copy(sampleIntervalMs = target) }
                }
                .onFailure { Log.w("DashboardViewModel", "Ahorro: no se pudo ajustar ${device.id}: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ acciones del usuario

    private fun intervalReportedBy(deviceId: String): Int? =
        (serialPort.state.value as? SerialLinkState.Connected)?.takeIf { it.deviceId == deviceId }?.intervalMs

    /** Cambia cada cuánto mide el sensor, enviando la instrucción por el cable USB y esperando su confirmación. */
    fun setSampleInterval(deviceId: String, intervalMs: Int) {
        viewModelScope.launch {
            updateDevice(deviceId) { it.copy(isBusy = true, error = null) }
            serialPort.setSampleInterval(deviceId, intervalMs)
                .onSuccess {
                    manualHoldUntil[deviceId] = clockMs() + timing.manualHoldMs   // el ahorro automático no lo pisa enseguida
                    updateDevice(deviceId) { it.copy(sampleIntervalMs = intervalMs, isBusy = false) }
                }
                .onFailure { e -> updateDevice(deviceId) { it.copy(isBusy = false, error = e.message ?: "No se pudo configurar el sensor") } }
        }
    }

    /** Activa o desactiva el modo automático (por humedad) de un relé. Exige poder controlarlo. */
    fun setAutomatic(relayId: String, enabled: Boolean) {
        if (enabled && !session.policy.canControl(relayId)) {
            updateDevice(relayId) { it.copy(error = "No tienes permiso para controlar \"$relayId\"") }
            return
        }
        settings.setAutomatic(relayId, enabled)
    }

    fun setPowerSaving(enabled: Boolean) = settings.setPowerSaving(enabled)

    /** Cierra la sesión: borra el usuario y sus permisos. Lo llama la pantalla antes de volver al login. */
    fun logout() = session.close()

    /** Encendido/apagado manual. Tomar el control a mano saca al relé del modo automático (si no, el automático lo revertiría). */
    fun setFan(deviceId: String, on: Boolean) {
        settings.setAutomatic(deviceId, false)
        viewModelScope.launch {
            updateDevice(deviceId) { it.copy(isBusy = true, error = null) }
            fanCommandSender.setFan(deviceId, on)
                .onSuccess { reply -> updateDevice(deviceId) { it.copy(fanOn = reply.fanOn, failSafe = reply.failSafe, isBusy = false) } }
                .onFailure { e ->
                    updateDevice(deviceId) {
                        it.copy(isBusy = false, error = e.message ?: "No se pudo enviar el comando al relé")
                    }
                }
        }
    }

    private fun updateDevice(id: String, transform: (DeviceUi) -> DeviceUi) = _ui.update { state ->
        state.copy(devices = state.devices.map { if (it.id == id) transform(it) else it })
    }

    /** Sensores primero, luego relés; dentro de cada grupo por id, para que la lista no salte. */
    private fun sorted(devices: List<DeviceUi>) = devices.sortedWith(compareBy({ it.type }, { it.id }))

    companion object {
        // El sensor emite cada 2 s y el relé anuncia su estado cada 5 s: se tolera la pérdida de ~2 paquetes.
        private const val OFFLINE_THRESHOLD_SENSOR_MS = 6000L
        private const val OFFLINE_THRESHOLD_RELAY_MS = 12000L
        private const val HISTORY_KEEP_MS = 10 * 60_000L
    }
}
