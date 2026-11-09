package cl.avelyn.bd_control_app.model.repository

import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.network.SampleRef
import cl.avelyn.bd_control_app.model.network.UdpMsg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRepositoryTest {

    /** Imita a Room: índice único (deviceId, sampleKey) con IGNORE, e ids autoincrementales. */
    private class FakeDao : SensorLogDao {
        val rows = MutableStateFlow<List<SensorLog>>(emptyList())
        private var nextId = 1L
        var failInsert = false

        override suspend fun insert(log: SensorLog): Long {
            if (failInsert) throw IllegalStateException("base de datos no disponible")
            if (rows.value.any { it.deviceId == log.deviceId && it.sampleKey == log.sampleKey }) return -1
            rows.value = rows.value + log.copy(id = nextId++)
            return nextId - 1
        }

        override fun latestFor(deviceId: String, limit: Int): Flow<List<SensorLog>> =
            rows.map { l -> l.filter { it.deviceId == deviceId }.sortedByDescending { it.timestamp }.take(limit) }

        override fun deviceIds(): Flow<List<String>> = rows.map { l -> l.map { it.deviceId }.distinct().sorted() }
        override suspend fun unsynced(limit: Int) = rows.value.filter { !it.synced }.sortedBy { it.timestamp }.take(limit)
        override fun unsyncedCount(): Flow<Int> = rows.map { l -> l.count { !it.synced } }
        override suspend fun unsyncedCountNow() = rows.value.count { !it.synced }
        override suspend fun markSynced(ids: List<Long>) { rows.value = rows.value.map { if (it.id in ids) it.copy(synced = true) else it } }
        override suspend fun purgeSyncedBefore(beforeEpochMillis: Long) { rows.value = rows.value.filterNot { it.synced && it.timestamp < beforeEpochMillis } }
        override suspend fun dropOldestUnsynced(count: Int) {
            val drop = rows.value.filter { !it.synced }.sortedBy { it.timestamp }.take(count).map { it.id }.toSet()
            rows.value = rows.value.filterNot { it.id in drop }
        }
    }

    private fun live(id: String = "sensor-1", t: Float = 20f, h: Float = 50f, n: Long? = null) =
        UdpMsg.Sensor(id, t, h, viaSerial = n != null, sample = n?.let { SampleRef(7, it) })

    private fun recovered(n: Long, at: Long, t: Float = 20f, h: Float = 50f) =
        UdpMsg.Sensor("sensor-1", t, h, viaSerial = true, sample = SampleRef(7, n), capturedAtMs = at)

    @Test
    fun `una lectura recuperada se guarda con la hora en que se midio y su clave unica`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        repo.record(recovered(n = 12, at = 1_700_000_000_000))
        val row = dao.rows.value.single()
        assertEquals(1_700_000_000_000, row.timestamp)
        assertEquals("7-12", row.sampleKey)
        assertEquals(false, row.synced)
    }

    @Test
    fun `la misma lectura recibida dos veces se guarda una sola`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        repo.record(recovered(5, 1_700_000_000_000))
        repo.record(recovered(5, 1_700_000_000_050)) // llegó de nuevo (hora algo distinta): misma identidad
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `las recuperadas se guardan todas aunque esten pegadas pero las en vivo se espacian`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        for (n in 1L..20L) repo.record(recovered(n, 1_700_000_000_000 + n * 200))
        assertEquals("todas las recuperadas", 20, dao.rows.value.size)

        val dao2 = FakeDao(); val repo2 = SensorRepository(dao2)
        repo2.record(live(n = 1)); repo2.record(live(n = 2)); repo2.record(live(n = 3))    // iguales y seguidas
        assertEquals("en vivo: solo la primera", 1, dao2.rows.value.size)
        repo2.record(live(t = 25f, n = 4))                                                  // salto brusco: se guarda
        assertEquals(2, dao2.rows.value.size)
    }

    @Test
    fun `el espaciado en vivo es independiente por sensor`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        repo.record(live("a")); repo.record(live("b")); repo.record(live("a"))
        assertEquals(listOf("a", "b"), dao.rows.value.map { it.deviceId })
    }

    @Test
    fun `una lectura Wi-Fi recibe una clave basada en su hora`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        repo.record(live("sensor-wifi"))
        val key = dao.rows.value.single().sampleKey
        assertTrue(key, key.startsWith("w-") && key.drop(2).toLong() > 1_000_000_000_000)
    }

    @Test
    fun `si guardar falla la excepcion llega al que llama para no confirmarle nada al sensor`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        dao.failInsert = true
        val result = runCatching { repo.record(recovered(1, 1_700_000_000_000)) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `la limpieza por antiguedad solo borra lo ya subido`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        val old = System.currentTimeMillis() - 30L * 86_400_000
        repo.record(recovered(1, old)); repo.record(recovered(2, old + 1)); repo.record(recovered(3, System.currentTimeMillis()))
        dao.markSynced(listOf(1))                        // solo la 1 se subió
        repo.purgeOlderThan(days = 7)
        assertEquals("la antigua sin subir se conserva", listOf("7-2", "7-3"), dao.rows.value.map { it.sampleKey })
    }

    @Test
    fun `si la cola supera su capacidad se descartan las mas antiguas sin subir`() = runBlocking {
        val dao = FakeDao(); val repo = SensorRepository(dao)
        for (n in 1L..10L) repo.record(recovered(n, 1_700_000_000_000 + n))
        dao.markSynced(listOf(1, 2))                     // las subidas no cuentan para el tope
        val dropped = repo.enforceCapacity(maxUnsynced = 5)
        assertEquals(3, dropped)                         // 8 sin subir -> 5
        assertEquals(setOf("7-1", "7-2", "7-6", "7-7", "7-8", "7-9", "7-10"), dao.rows.value.map { it.sampleKey }.toSet())
        assertEquals(0, repo.enforceCapacity(maxUnsynced = 5))
    }
}
