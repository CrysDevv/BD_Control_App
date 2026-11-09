package cl.avelyn.bd_control_app.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cl.avelyn.bd_control_app.model.entity.SensorLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorLogDao {

    /** Devuelve el id de la fila nueva, o -1 si esa lectura ya estaba guardada (misma [SensorLog.sampleKey]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: SensorLog): Long

    /** Flow: la UI del historial se actualiza sola cuando se inserta un nuevo registro. */
    @Query("SELECT * FROM sensor_log WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    fun latestFor(deviceId: String, limit: Int): Flow<List<SensorLog>>

    /** Ids de los sensores que tienen al menos un registro, para el selector del historial. */
    @Query("SELECT DISTINCT deviceId FROM sensor_log ORDER BY deviceId")
    fun deviceIds(): Flow<List<String>>

    // ---------- Cola de sincronización ----------

    /** Las lecturas más antiguas que aún no se han subido. */
    @Query("SELECT * FROM sensor_log WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<SensorLog>

    @Query("SELECT COUNT(*) FROM sensor_log WHERE synced = 0")
    fun unsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sensor_log WHERE synced = 0")
    suspend fun unsyncedCountNow(): Int

    @Query("UPDATE sensor_log SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // ---------- Mantenimiento ----------

    /** Borra SOLO lecturas ya subidas y antiguas. Las que aún no se subieron nunca se borran por antigüedad. */
    @Query("DELETE FROM sensor_log WHERE synced = 1 AND timestamp < :beforeEpochMillis")
    suspend fun purgeSyncedBefore(beforeEpochMillis: Long)

    /** Último recurso cuando la cola se llena: descarta las [count] lecturas sin subir más antiguas. */
    @Query("DELETE FROM sensor_log WHERE id IN (SELECT id FROM sensor_log WHERE synced = 0 ORDER BY timestamp ASC LIMIT :count)")
    suspend fun dropOldestUnsynced(count: Int)
}
