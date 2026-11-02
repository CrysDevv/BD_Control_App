package cl.avelyn.bd_control_app.model.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un registro histórico de temperatura y humedad, guardado por [cl.avelyn.bd_control_app.model.repository.SensorRepository]
 * cada vez que llega una lectura válida por UDP (con el filtro de frecuencia/salto descrito ahí).
 */
@Entity(tableName = "sensor_log", indices = [Index(value = ["timestamp"])])
data class SensorLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long, // epoch millis
    val temperatureC: Float,
    val humidityPct: Float
)
