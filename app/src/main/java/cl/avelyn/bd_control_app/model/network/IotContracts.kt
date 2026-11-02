package cl.avelyn.bd_control_app.model.network

import kotlinx.coroutines.flow.Flow

/**
 * Fuente de la telemetría del sensor y del estado del relé, sin importar el transporte real.
 * Hoy la implementa [SupabaseIotBridge] (Postgrest + Realtime, simulando esa comunicación
 * entre dos teléfonos mientras no se cuenta con el hardware); cuando llegue el ESP32 real,
 * una nueva implementación sobre Wi-Fi/UDP puede reemplazarla sin tocar ningún ViewModel.
 */
interface SensorDataSource {
    fun listen(): Flow<UdpMsg>
}

/**
 * Envía el comando de encendido/apagado del ventilador, sin importar el transporte real.
 * Hoy la implementa [SupabaseIotBridge]; con el ESP32 real sería una implementación sobre TCP.
 */
interface FanCommandSender {
    suspend fun setFan(on: Boolean): Result<RelayReply>
}
