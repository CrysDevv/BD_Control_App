package cl.avelyn.bd_control_app.model.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable

private const val TABLE = "bodega_status"
private const val ROW_ID = 1

// Objetos parciales: Postgrest actualiza solo las columnas presentes en cada upsert,
// así que cada lado del sistema solo "toca" las columnas que le corresponden.
@Serializable
private data class TelemetryUpsert(val id: Int = ROW_ID, val temp_c: Double, val hum_pct: Double)

@Serializable
private data class CommandUpsert(val id: Int = ROW_ID, val fan_command: Boolean)

@Serializable
private data class StateUpsert(val id: Int = ROW_ID, val fan_state: Boolean)

/** Fila completa tal como la entrega Realtime en cada cambio (todas las columnas, no solo las que cambiaron). */
@Serializable
private data class BodegaRow(
    val id: Int,
    val temp_c: Double? = null,
    val hum_pct: Double? = null,
    val fan_command: Boolean? = null,
    val fan_state: Boolean? = null
)

/**
 * Simula la comunicación entre los dos ESP32 usando una única fila de Postgres
 * (tabla `bodega_status`, ver el script SQL del informe) sincronizada en vivo con
 * Supabase Realtime, mientras no se cuenta con el hardware real. Implementa
 * [SensorDataSource] y [FanCommandSender], así que pasar a una versión Wi-Fi real
 * cuando llegue el ESP32 es solo cambiar qué se instancia en
 * [cl.avelyn.bd_control_app.di.AppContainer].
 *
 * Columnas de `bodega_status` (fila única, id = 1):
 * - temp_c, hum_pct   -> las escribe el dispositivo emisor (simulador), equivalente a la telemetría UDP.
 * - fan_command       -> la escribe esta app, equivalente al comando TCP.
 * - fan_state         -> la escribe el simulador tras "ejecutar" el comando, equivalente al estado anunciado por el relé.
 *
 * Limitación documentada: la tabla queda sin Row Level Security (o con una política
 * abierta) para este proyecto académico; en una app real se restringiría con
 * Supabase Auth y políticas RLS por usuario.
 *
 * Requisito de Wi-Fi: toda escritura ([setFan], [publishTelemetry], [reportState]) exige
 * primero pasar [WifiGuard.isOnExpectedWifi]. Solo lectura (recibir telemetría o estado)
 * no lo exige. Ver [WifiGuard] para el porqué esto garantiza que ambos dispositivos
 * estén en la misma red.
 */
class SupabaseIotBridge(
    private val client: SupabaseClient,
    private val wifiGuard: WifiGuard
) : SensorDataSource, FanCommandSender {

    private val realtimeChannel = client.realtime.channel("bodega_status_changes")
    private var isSubscribed = false

    private fun requireWifi() {
        check(wifiGuard.isOnExpectedWifi()) {
            "Debes estar conectado a la red Wi-Fi \"${WifiGuard.EXPECTED_SSID}\" para enviar datos"
        }
    }

    private suspend fun ensureSubscribed() {
        if (isSubscribed) return
        realtimeChannel.subscribe()
        isSubscribed = true
    }

    /** Un Flow con la fila completa cada vez que cambia, usado tanto por [listen] como por [listenForCommands]. */
    private fun rowChanges(): Flow<BodegaRow> = realtimeChannel
        .postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = TABLE }
        .onStart { ensureSubscribed() }
        .map { it.decodeRecord<BodegaRow>() }

    // ---------- Lado "app de monitoreo": recibe telemetría y estado ----------

    override fun listen(): Flow<UdpMsg> = rowChanges().transform { row ->
        if (row.temp_c != null && row.hum_pct != null) {
            emit(UdpMsg.Sensor(DeviceIds.DEFAULT_SENSOR, tempC = row.temp_c.toFloat(), humPct = row.hum_pct.toFloat()))
        }
        row.fan_state?.let { emit(UdpMsg.Relay(DeviceIds.DEFAULT_RELAY, fanOn = it)) }
    }

    /**
     * Equivalente al comando TCP: publica la intención, la confirmación real llega por [listen].
     * [deviceId] se ignora: el simulador solo representa un único relé.
     */
    /** El simulador no tiene un relé real que deba oír latidos. */
    override suspend fun heartbeat(deviceId: String): Result<RelayReply> = Result.success(RelayReply(ok = true, fanOn = false))

    override suspend fun setFan(deviceId: String, on: Boolean): Result<RelayReply> = runCatching {
        requireWifi()
        client.from(TABLE).upsert(CommandUpsert(fan_command = on))
        RelayReply(ok = true, fanOn = on)
    }

    // ---------- Lado "dispositivo emisor" (simulador del sensor + relé) ----------

    /** Publica una lectura, tal como lo haría el sensor real cada 2 segundos. */
    suspend fun publishTelemetry(tempC: Float, humPct: Float) {
        requireWifi()
        client.from(TABLE).upsert(TelemetryUpsert(temp_c = tempC.toDouble(), hum_pct = humPct.toDouble()))
    }

    /** Escucha los comandos que envía la app de monitoreo, como lo haría el relé real. */
    fun listenForCommands(): Flow<Boolean> = rowChanges().mapNotNull { it.fan_command }

    /** Confirma que el ventilador "físico" simulado ya quedó en ese estado. */
    suspend fun reportState(fanOn: Boolean) {
        requireWifi()
        client.from(TABLE).upsert(StateUpsert(fan_state = fanOn))
    }
}
