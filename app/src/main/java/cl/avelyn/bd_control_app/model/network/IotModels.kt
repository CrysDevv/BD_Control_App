package cl.avelyn.bd_control_app.model.network

/** Tipo de dispositivo ESP32 que la app sabe manejar. */
enum class DeviceType { SENSOR, RELAY }

/**
 * Ids de respaldo para firmware antiguo que todavía no envía `"id"`, y para el simulador
 * de Supabase (que representa un único sensor y un único relé).
 */
object DeviceIds {
    const val DEFAULT_SENSOR = "sensor"
    const val DEFAULT_RELAY = "rele"
}

/**
 * Identidad única y estable de una lectura de un sensor por cable: el [bootId] que el sensor elige al
 * arrancar y el contador [n] que sube de 1 en 1. Sirve para no duplicar nunca una lectura (aunque
 * llegue dos veces por serial o se suba dos veces a Supabase) y para confirmarle al sensor cuáles
 * ya se guardaron.
 */
data class SampleRef(val bootId: Long, val n: Long) {
    val key: String get() = "$bootId-$n"
}

/**
 * Un mensaje de telemetría o de estado, sin importar si llegó por Supabase (simulador) o por
 * Wi-Fi/UDP de un ESP32 real: es el tipo común que entiende [SensorDataSource].
 * Cada mensaje lleva el [deviceId] del ESP32 que lo originó, para poder manejar varios a la vez.
 */
sealed interface UdpMsg {
    val deviceId: String

    /**
     * [viaSerial]: la lectura llegó por el cable USB en vez de por Wi-Fi (esos sensores admiten configuración remota).
     * [sample]: identidad de la lectura (solo sensores por cable). [capturedAtMs]: hora real en que se midió, si
     * no es "ahora" (lecturas recuperadas del buffer del sensor mientras la app no escuchaba).
     */
    data class Sensor(
        override val deviceId: String,
        val tempC: Float,
        val humPct: Float,
        val viaSerial: Boolean = false,
        val sample: SampleRef? = null,
        val capturedAtMs: Long? = null
    ) : UdpMsg
    /** [failSafe]: el relé está en modo seguro (dejó de oír a la app y ventila por su cuenta; ver relay_failsafe.h). */
    data class Relay(override val deviceId: String, val fanOn: Boolean, val failSafe: Boolean = false) : UdpMsg
}

/** Respuesta a un comando de encendido/apagado enviado por [FanCommandSender]. */
data class RelayReply(val ok: Boolean, val fanOn: Boolean, val failSafe: Boolean = false)
