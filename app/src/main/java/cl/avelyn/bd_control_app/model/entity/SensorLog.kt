package cl.avelyn.bd_control_app.model.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cl.avelyn.bd_control_app.model.network.DeviceIds

/**
 * Una lectura de temperatura y humedad de un sensor, guardada por
 * [cl.avelyn.bd_control_app.model.repository.SensorRepository]. Esta tabla es además la **cola de
 * sincronización**: una fila con [synced] = false todavía no se subió a Supabase y se conserva
 * (aunque no haya internet, o la app se cierre) hasta que se suba.
 *
 * [sampleKey] identifica la lectura de forma única y estable dentro de su [deviceId] (para los
 * sensores por cable es `bootId-n`, la identidad que fija el propio sensor). El índice único
 * impide guardarla dos veces y la clave también hace idempotente la subida a Supabase.
 */
@Entity(
    tableName = "sensor_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["deviceId", "sampleKey"], unique = true)
    ]
)
data class SensorLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long, // epoch millis de cuando se MIDIÓ (no de cuando llegó)
    val temperatureC: Float,
    val humidityPct: Float,
    val deviceId: String = DeviceIds.DEFAULT_SENSOR,
    val sampleKey: String,
    val synced: Boolean = false
)
