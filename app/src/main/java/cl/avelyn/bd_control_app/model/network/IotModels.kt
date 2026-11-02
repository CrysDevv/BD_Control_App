package cl.avelyn.bd_control_app.model.network

/**
 * Un mensaje de telemetría o de estado, sin importar si llegó por Supabase (ahora) o por
 * Wi-Fi/UDP del ESP32 real (más adelante): es el tipo común que entiende [SensorDataSource].
 */
sealed interface UdpMsg {
    data class Sensor(val tempC: Float, val humPct: Float) : UdpMsg
    data class Relay(val fanOn: Boolean) : UdpMsg
}

/** Respuesta a un comando de encendido/apagado enviado por [FanCommandSender]. */
data class RelayReply(val ok: Boolean, val fanOn: Boolean)
