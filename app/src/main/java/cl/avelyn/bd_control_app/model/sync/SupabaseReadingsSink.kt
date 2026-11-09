package cl.avelyn.bd_control_app.model.sync

import cl.avelyn.bd_control_app.model.entity.SensorLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Fila de la tabla `sensor_readings` de Supabase. Ver `docs/supabase_lecturas.sql`. */
@Serializable
private data class ReadingRow(
    val device_id: String,
    val sample_key: String,
    val ts_ms: Long,
    val temp_c: Double,
    val hum_pct: Double
)

/**
 * Sube las lecturas a la tabla `sensor_readings`. Usa "insertar e ignorar duplicados" sobre la clave única
 * `(device_id, sample_key)`: si una lectura ya estaba (porque una subida anterior llegó pero la app no alcanzó
 * a enterarse), el servidor la ignora en vez de duplicarla ni fallar. Solo necesita permiso de INSERT.
 */
class SupabaseReadingsSink(private val client: SupabaseClient) : ReadingsSink {

    override suspend fun upload(rows: List<SensorLog>): Result<Unit> {
        val payload = rows.map {
            ReadingRow(
                device_id = it.deviceId,
                sample_key = it.sampleKey,
                ts_ms = it.timestamp,
                temp_c = it.temperatureC.toDouble(),
                hum_pct = it.humidityPct.toDouble()
            )
        }
        return try {
            client.from("sensor_readings").upsert(payload) {
                onConflict = "device_id,sample_key"
                ignoreDuplicates = true
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
