package cl.avelyn.bd_control_app.model.network

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

private const val TABLE = "bodega_status"
private const val ROW_ID = 1

@Serializable
private data class ManualFanUpdate(
    @SerialName("mode") val mode: String,
    @SerialName("fan_command") val fan_command: Boolean,
)

@Serializable
private data class AutoModeUpdate(
    @SerialName("mode") val mode: String,
)

@Serializable
private data class ThresholdsUpdate(
    @SerialName("hum_on") val hum_on: Double,
    @SerialName("hum_off") val hum_off: Double,
)

class SupabaseIotBridge(
    private val client: SupabaseClient
) : SensorDataSource, FanCommandSender {

    suspend fun getStatus(): Result<BodegaStatusDto?> = runCatching {
        client.from(TABLE).select {
            filter { eq("id", ROW_ID) }
        }.decodeSingleOrNull<BodegaStatusDto>()
    }

    fun observeStatus(): Flow<BodegaStatusDto> = channelFlow {
        // 1. Consulta inicial REST API
        try {
            val initial = getStatus().getOrNull()
            if (initial != null) send(initial)
        } catch (e: Exception) {
            Log.w("SupabaseIotBridge", "Error al obtener estado inicial: ${e.message}")
        }

        // 2. Realtime listener (Postgres Changes)
        val realtimeJob = launch {
            var channel: RealtimeChannel? = null
            try {
                val ch = client.realtime.channel("bodega_status_${UUID.randomUUID()}")
                channel = ch
                val changeFlow = ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = TABLE }
                ch.subscribe()
                changeFlow
                    .map { it.decodeRecord<BodegaStatusDto>() }
                    .filter { it.id == ROW_ID }
                    .collect { send(it) }
            } catch (e: Exception) {
                Log.w("SupabaseIotBridge", "Error en Realtime: ${e.message}")
            } finally {
                channel?.let { ch ->
                    try {
                        ch.unsubscribe()
                        client.realtime.removeChannel(ch)
                    } catch (e: Exception) {
                        Log.w("SupabaseIotBridge", "Error al cerrar canal Realtime: ${e.message}")
                    }
                }
            }
        }

        // 3. Polling de respaldo cada 3 segundos
        val pollingJob = launch {
            while (isActive) {
                delay(3000L)
                try {
                    val current = getStatus().getOrNull()
                    if (current != null) send(current)
                } catch (_: Exception) {
                }
            }
        }

        awaitClose {
            realtimeJob.cancel()
            pollingJob.cancel()
        }
    }

    /**
     * Escribe mode='manual' y fan_command=on en UN SOLO UPDATE filtrando por id = 1.
     */
    suspend fun setFanManual(on: Boolean): Result<Unit> = runCatching {
        client.from(TABLE).update(ManualFanUpdate(mode = "manual", fan_command = on)) {
            filter { eq("id", ROW_ID) }
        }
        Unit
    }

    /**
     * Escribe solo mode='auto' en UN SOLO UPDATE filtrando por id = 1 (no toca fan_command).
     */
    suspend fun setAuto(): Result<Unit> = runCatching {
        client.from(TABLE).update(AutoModeUpdate(mode = "auto")) {
            filter { eq("id", ROW_ID) }
        }
        Unit
    }

    /**
     * Valida que hum_on > hum_off. Si se cumple, escribe hum_on y hum_off filtrando por id = 1.
     */
    suspend fun setThresholds(on: Float, off: Float): Result<Unit> {
        if (on <= off) {
            return Result.failure(IllegalArgumentException("La humedad de encendido (hum_on) debe ser mayor que la de apagado (hum_off)"))
        }
        return runCatching {
            client.from(TABLE).update(ThresholdsUpdate(hum_on = on.toDouble(), hum_off = off.toDouble())) {
                filter { eq("id", ROW_ID) }
            }
            Unit
        }
    }

    override fun listen(): Flow<UdpMsg> = observeStatus().transform { status ->
        if (status.temp_c != null && status.hum_pct != null) {
            emit(UdpMsg.Sensor(DeviceIds.DEFAULT_ESP, tempC = status.temp_c.toFloat(), humPct = status.hum_pct.toFloat()))
        }
        val fanReal = status.fan_state ?: false
        emit(UdpMsg.Relay(DeviceIds.DEFAULT_ESP, fanOn = fanReal))
    }

    override suspend fun heartbeat(deviceId: String): Result<RelayReply> = runCatching {
        val status = getStatus().getOrNull()
        RelayReply(ok = true, fanOn = status?.fan_state ?: false)
    }.recover {
        RelayReply(ok = true, fanOn = false)
    }

    override suspend fun setFan(deviceId: String, on: Boolean): Result<RelayReply> = runCatching {
        setFanManual(on).getOrThrow()
        RelayReply(ok = true, fanOn = on)
    }
}
