package cl.avelyn.bd_control_app.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cl.avelyn.bd_control_app.model.entity.SensorLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorLogDao {

    @Insert
    suspend fun insert(log: SensorLog)

    /** Flow: la UI del historial se actualiza sola cuando se inserta un nuevo registro. */
    @Query("SELECT * FROM sensor_log ORDER BY timestamp DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<SensorLog>>

    @Query("DELETE FROM sensor_log WHERE timestamp < :beforeEpochMillis")
    suspend fun purgeBefore(beforeEpochMillis: Long)
}
