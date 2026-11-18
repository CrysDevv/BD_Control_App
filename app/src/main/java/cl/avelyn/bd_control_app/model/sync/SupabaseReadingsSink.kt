package cl.avelyn.bd_control_app.model.sync

import cl.avelyn.bd_control_app.model.entity.SensorLog
import io.github.jan.supabase.SupabaseClient

/**
 * Almacenamiento de lecturas en Supabase.
 * La app ya no escribe `temp_c` ni `hum_pct` en `bodega_status` porque de eso se encarga el ESP32.
 */
class SupabaseReadingsSink(private val client: SupabaseClient) : ReadingsSink {

    override suspend fun upload(rows: List<SensorLog>): Result<Unit> {
        // La app no escribe telemetría en bodega_status (es propiedad exclusiva del ESP32).
        return Result.success(Unit)
    }
}
