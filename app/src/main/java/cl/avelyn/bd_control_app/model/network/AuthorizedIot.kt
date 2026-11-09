package cl.avelyn.bd_control_app.model.network

import cl.avelyn.bd_control_app.model.security.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge

/**
 * Control de acceso por dispositivo, aplicado como decoradores sobre los contratos de transporte.
 * Así la regla vive en un solo lugar y vale para cualquier transporte (Wi-Fi o Supabase), y
 * ningún ViewModel puede saltársela: no reciben el transporte directo sino estos envoltorios.
 */

/** Solo deja pasar la telemetría de los dispositivos que el usuario puede **consultar**. */
class AuthorizedSensorDataSource(
    private val delegate: SensorDataSource,
    private val session: SessionManager
) : SensorDataSource {
    // La política se consulta en cada mensaje (no se captura): si la sesión cambia, el filtro también.
    override fun listen(): Flow<UdpMsg> = delegate.listen().filter { session.policy.canView(it.deviceId) }
}

/** Solo envía el comando si el usuario puede **controlar** ese dispositivo en particular. */
class AuthorizedFanCommandSender(
    private val delegate: FanCommandSender,
    private val session: SessionManager
) : FanCommandSender {
    override suspend fun setFan(deviceId: String, on: Boolean): Result<RelayReply> =
        if (session.policy.canControl(deviceId)) {
            delegate.setFan(deviceId, on)
        } else {
            Result.failure(SecurityException("No tienes permiso para controlar \"$deviceId\""))
        }

    /** El latido es parte del control del relé: mismo permiso. */
    override suspend fun heartbeat(deviceId: String): Result<RelayReply> =
        if (session.policy.canControl(deviceId)) {
            delegate.heartbeat(deviceId)
        } else {
            Result.failure(SecurityException("No tienes permiso para controlar \"$deviceId\""))
        }
}

/** Cambiar la configuración de un sensor (p. ej. su intervalo de medición) cuenta como **controlarlo**. */
class AuthorizedSerialSensorPort(
    private val delegate: SerialSensorPort,
    private val session: SessionManager
) : SerialSensorPort {
    override val state: StateFlow<SerialLinkState> get() = delegate.state
    override val stats: StateFlow<LinkStats> get() = delegate.stats

    /** Confirmar que algo ya se guardó no es "controlar" el sensor: no requiere permiso. */
    override fun markStored(deviceId: String, sample: SampleRef) = delegate.markStored(deviceId, sample)

    override suspend fun setSampleInterval(deviceId: String, intervalMs: Int): Result<Unit> =
        if (session.policy.canControl(deviceId)) {
            delegate.setSampleInterval(deviceId, intervalMs)
        } else {
            Result.failure(SecurityException("No tienes permiso para configurar \"$deviceId\""))
        }
}

/** Junta varias fuentes de telemetría en una sola (hoy: Wi-Fi + el sensor conectado por USB). */
class CompositeSensorDataSource(private vararg val sources: SensorDataSource) : SensorDataSource {
    override fun listen(): Flow<UdpMsg> = merge(*sources.map { it.listen() }.toTypedArray())
}
