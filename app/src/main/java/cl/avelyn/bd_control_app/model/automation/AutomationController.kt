package cl.avelyn.bd_control_app.model.automation

/**
 * Control automático del ventilador por humedad + política de latidos al relé. Lógica pura (sin Android ni red),
 * para probarla a fondo: el ViewModel solo le entrega una foto del estado y ejecuta lo que devuelve.
 *
 * REGLAS (por relé que la app puede controlar y que está en línea):
 * - **Manual:** manda el usuario. Solo se envían latidos cada [AutomationConfig.heartbeatEveryMs] ("sigo aquí").
 * - **Automático:** se mira la humedad MÁS ALTA entre los sensores en línea con datos recientes (el peor caso).
 *   Enciende al llegar a [AutomationConfig.humidityOnPct] y apaga al bajar a [AutomationConfig.humidityOffPct]
 *   (histéresis, para no encender y apagar sin parar), respetando un tiempo mínimo encendido y apagado.
 * - **Continuidad:** si en modo automático NO hay ningún sensor con datos recientes, el lazo de control no puede
 *   decidir. Entonces NO se envían latidos ni órdenes: el relé deja de oír a la app, entra solo en modo seguro
 *   (ventila por ciclos) y la bodega sigue protegida. Un latido en ese estado ocultaría la falla.
 */
data class AutomationConfig(
    val humidityOnPct: Float = 70f,
    val humidityOffPct: Float = 60f,
    val minOnMs: Long = 2 * 60_000,
    val minOffMs: Long = 2 * 60_000,
    /** Un dato de un sensor más viejo que esto ya no sirve para decidir. */
    val staleSensorMs: Long = 5 * 60_000,
    val heartbeatEveryMs: Long = 30_000
) {
    init {
        require(humidityOnPct > humidityOffPct) { "El umbral de encendido debe ser mayor que el de apagado (histéresis)" }
    }
}

data class SensorView(val id: String, val humPct: Float?, val isOnline: Boolean, val ageMs: Long)

data class RelayView(
    val id: String,
    val fanOn: Boolean?,
    val isOnline: Boolean,
    val canControl: Boolean,
    val automatic: Boolean,
    /** El relé informó que está en modo seguro. */
    val failSafe: Boolean = false
)

data class ControlSnapshot(val nowMs: Long, val sensors: List<SensorView>, val relays: List<RelayView>)

sealed interface ControlAction {
    data class SetFan(val relayId: String, val on: Boolean) : ControlAction
    data class Heartbeat(val relayId: String) : ControlAction
}

/** Estado del automático de un relé, para mostrarlo. */
enum class AutomationStatus {
    /** No está en automático. */
    OFF,

    /** Decidiendo con datos recientes. */
    ACTIVE,

    /** Sin datos recientes de sensores: sin latidos; el relé entrará (o ya está) en modo seguro. */
    PAUSED_NO_DATA
}

data class ControlResult(val actions: List<ControlAction>, val statuses: Map<String, AutomationStatus>)

class AutomationController(private val config: AutomationConfig = AutomationConfig()) {

    private val lastContactAt = mutableMapOf<String, Long>()
    private val lastFanChangeAt = mutableMapOf<String, Long>()

    fun tick(snapshot: ControlSnapshot): ControlResult {
        val now = snapshot.nowMs
        val fresh = snapshot.sensors.filter { it.isOnline && it.humPct != null && it.ageMs <= config.staleSensorMs }
        val worst = fresh.maxOfOrNull { it.humPct!! }

        val actions = mutableListOf<ControlAction>()
        val statuses = mutableMapOf<String, AutomationStatus>()

        for (relay in snapshot.relays) {
            if (!relay.automatic) statuses[relay.id] = AutomationStatus.OFF
            if (!relay.canControl || !relay.isOnline) {
                if (relay.automatic) statuses[relay.id] = if (worst == null) AutomationStatus.PAUSED_NO_DATA else AutomationStatus.ACTIVE
                continue
            }

            if (!relay.automatic) {
                if (heartbeatDue(relay.id, now)) actions += heartbeat(relay.id, now)
                continue
            }

            if (worst == null) {
                statuses[relay.id] = AutomationStatus.PAUSED_NO_DATA   // sin datos: ni órdenes ni latidos (ver arriba)
                continue
            }
            statuses[relay.id] = AutomationStatus.ACTIVE

            val fanOn = relay.fanOn
            val since = lastFanChangeAt[relay.id]
            val wantOn = when {
                fanOn != true && worst >= config.humidityOnPct -> true
                fanOn == true && worst <= config.humidityOffPct -> false
                else -> null
            }
            val allowed = wantOn == null || since == null || now - since >= (if (wantOn) config.minOffMs else config.minOnMs)
            if (wantOn != null && allowed) {
                actions += ControlAction.SetFan(relay.id, wantOn)
                lastContactAt[relay.id] = now   // una orden también cuenta como contacto
            } else if (heartbeatDue(relay.id, now)) {
                actions += heartbeat(relay.id, now)
            }
        }
        return ControlResult(actions, statuses)
    }

    /** La orden de encender/apagar se ejecutó bien: desde ahora corre el tiempo mínimo en ese estado. */
    fun onFanChanged(relayId: String, nowMs: Long) {
        lastFanChangeAt[relayId] = nowMs
    }

    private fun heartbeatDue(relayId: String, now: Long): Boolean {
        val last = lastContactAt[relayId] ?: return true
        return now - last >= config.heartbeatEveryMs
    }

    private fun heartbeat(relayId: String, now: Long): ControlAction {
        lastContactAt[relayId] = now
        return ControlAction.Heartbeat(relayId)
    }
}
