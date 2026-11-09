package cl.avelyn.bd_control_app.model.sync

import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import kotlinx.coroutines.flow.Flow

/** La cola de sincronización es la propia tabla `sensor_log` de Room. */
class RoomSyncStore(private val dao: SensorLogDao) : SyncStore {
    override suspend fun unsynced(limit: Int): List<SensorLog> = dao.unsynced(limit)
    override suspend fun markSynced(ids: List<Long>) = dao.markSynced(ids)
    override fun unsyncedCount(): Flow<Int> = dao.unsyncedCount()
}
