package cl.avelyn.bd_control_app.model.network

import kotlinx.coroutines.flow.Flow

/**
 * Fuente de la telemetría de los sensores y del estado de los relés, sin importar el transporte.
 * Implementaciones: [WifiIotBridge] (ESP32 reales por UDP) y [SupabaseIotBridge] (simulador).
 * Los mensajes de todos los dispositivos llegan mezclados; se distinguen por [UdpMsg.deviceId].
 */
interface SensorDataSource {
    fun listen(): Flow<UdpMsg>
}

/**
 * Envía el comando de encendido/apagado al relé identificado por [deviceId], sin importar
 * el transporte. Implementaciones: [WifiIotBridge] (TCP cifrado) y [SupabaseIotBridge] (simulador,
 * que ignora el id porque solo representa un relé).
 */
interface FanCommandSender {
    suspend fun setFan(deviceId: String, on: Boolean): Result<RelayReply>

    /**
     * Latido: le dice al relé que la app sigue viva y con su lazo de control sano. Si el relé pasa un buen rato sin oírlo
     * entra en modo seguro (ventila por ciclos por su cuenta). No cambia el estado del ventilador.
     */
    suspend fun heartbeat(deviceId: String): Result<RelayReply>
}

/**
 * Envía configuración a un sensor (hoy: cada cuánto mide y reporta). Solo los sensores conectados
 * por el cable USB (serial) la admiten; implementación: [SerialLink].
 */
interface SensorConfigSender {
    suspend fun setSampleInterval(deviceId: String, intervalMs: Int): Result<Unit>
}
