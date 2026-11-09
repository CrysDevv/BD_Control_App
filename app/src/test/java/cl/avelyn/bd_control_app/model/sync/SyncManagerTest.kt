package cl.avelyn.bd_control_app.model.sync

import cl.avelyn.bd_control_app.model.entity.SensorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SyncManagerTest {

    private class FakeStore : SyncStore {
        private val lock = Any()
        private val rows = mutableListOf<SensorLog>()
        private val pending = MutableStateFlow(0)
        private var nextId = 1L
        @Volatile var failNextMarks = 0

        fun add(key: String, ts: Long) = synchronized(lock) {
            rows += SensorLog(id = nextId++, timestamp = ts, temperatureC = 20f, humidityPct = 50f, deviceId = "sensor-1", sampleKey = key)
            pending.value = rows.count { !it.synced }
        }

        fun syncedKeys() = synchronized(lock) { rows.filter { it.synced }.map { it.sampleKey } }
        fun unsyncedKeys() = synchronized(lock) { rows.filter { !it.synced }.map { it.sampleKey } }

        override suspend fun unsynced(limit: Int) = synchronized(lock) { rows.filter { !it.synced }.sortedBy { it.timestamp }.take(limit) }

        override suspend fun markSynced(ids: List<Long>) = synchronized(lock) {
            if (failNextMarks > 0) { failNextMarks--; throw IOException("disco lleno") }
            rows.replaceAll { if (it.id in ids) it.copy(synced = true) else it }
            pending.value = rows.count { !it.synced }
        }

        override fun unsyncedCount(): Flow<Int> = pending
    }

    /** Emula el servidor: ignora duplicados por clave, como `ON CONFLICT DO NOTHING`. */
    private class FakeSink : ReadingsSink {
        val remote = linkedSetOf<String>()
        val batches = mutableListOf<List<String>>()
        val attempts = AtomicInteger(0)
        @Volatile var failNext = 0

        override suspend fun upload(rows: List<SensorLog>): Result<Unit> {
            attempts.incrementAndGet()
            if (failNext > 0) { failNext--; return Result.failure(IOException("sin respuesta del servidor")) }
            synchronized(this) { batches += rows.map { it.sampleKey }; rows.forEach { remote += it.sampleKey } }
            return Result.success(Unit)
        }
    }

    private val config = SyncConfig(batchSize = 3, initialBackoffMs = 30, maxBackoffMs = 120)

    private fun start(store: FakeStore, sink: FakeSink, online: MutableStateFlow<Boolean>): Pair<SyncManager, Job> {
        val manager = SyncManager(store, sink, online, config)
        val job = CoroutineScope(Dispatchers.Default).launch { manager.run() }
        return manager to job
    }

    private suspend fun awaitUntil(ms: Long = 3000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; delay(10) }
        return condition()
    }

    @Test
    fun `sin internet las lecturas se acumulan y no se sube nada`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(false)
        val (manager, job) = start(store, sink, online)
        (1..5).forEach { store.add("k$it", it.toLong()) }
        assertTrue(awaitUntil { manager.status.value.pending == 5 })
        delay(200)
        assertEquals(0, sink.attempts.get())
        assertFalse(manager.status.value.isOnline)
        assertEquals(5, store.unsyncedKeys().size)
        job.cancel()
    }

    @Test
    fun `al volver internet sube todo por lotes de la mas antigua a la mas nueva`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(false)
        val (manager, job) = start(store, sink, online)
        // se agregan desordenadas: debe respetar la hora de medición, no el orden de llegada
        listOf(5, 1, 4, 2, 3, 7, 6).forEach { store.add("k$it", it.toLong()) }
        assertTrue(awaitUntil { manager.status.value.pending == 7 })

        online.value = true
        assertTrue(awaitUntil { manager.status.value.pending == 0 })
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5", "k6", "k7"), sink.remote.toList())
        assertTrue("lotes de a 3: ${sink.batches}", sink.batches.all { it.size <= 3 } && sink.batches.size == 3)
        val st = manager.status.value
        assertTrue(st.uploadedTotal == 7L && st.lastSyncMs != null && st.lastError == null)
        job.cancel()
    }

    @Test
    fun `las lecturas nuevas con internet se suben solas`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        val (manager, job) = start(store, sink, online)
        delay(100)
        store.add("a", 1); store.add("b", 2)
        assertTrue(awaitUntil { sink.remote.containsAll(listOf("a", "b")) && manager.status.value.pending == 0 })
        store.add("c", 3)
        assertTrue(awaitUntil { "c" in sink.remote })
        job.cancel()
    }

    @Test
    fun `si la subida falla no se pierde nada y se reintenta con espera hasta lograrlo`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        sink.failNext = 3
        val (manager, job) = start(store, sink, online)
        (1..4).forEach { store.add("k$it", it.toLong()) }

        assertTrue(awaitUntil { manager.status.value.lastError != null })
        assertEquals("sin respuesta del servidor", manager.status.value.lastError)
        assertEquals("durante el fallo nada se marca como subido", emptyList<String>(), store.syncedKeys())

        assertTrue("al final sube todo", awaitUntil { manager.status.value.pending == 0 })
        assertEquals(setOf("k1", "k2", "k3", "k4"), sink.remote)
        assertNull("el error se limpia al lograrlo", manager.status.value.lastError)
        assertTrue("hubo reintentos: ${sink.attempts.get()}", sink.attempts.get() >= 4)
        job.cancel()
    }

    @Test
    fun `si se pierde internet en pleno fallo deja de reintentar y retoma al volver`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        sink.failNext = 1000
        val (manager, job) = start(store, sink, online)
        store.add("k1", 1)
        assertTrue(awaitUntil { sink.attempts.get() >= 2 })
        online.value = false
        delay(400)                                        // deja pasar el reintento en curso
        val frozen = sink.attempts.get()
        delay(400)
        assertEquals("sin internet no intenta subir", frozen, sink.attempts.get())

        sink.failNext = 0
        online.value = true
        assertTrue(awaitUntil { "k1" in sink.remote && manager.status.value.pending == 0 })
        assertEquals(setOf("k1"), sink.remote)
        job.cancel()
    }

    @Test
    fun `si la app no alcanza a marcar lo subido se reenvia y el servidor no duplica`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        store.failNextMarks = 1                           // la subida llega, pero falla guardar el "ya subido" (p. ej. se cierra la app)
        val (manager, job) = start(store, sink, online)
        (1..3).forEach { store.add("k$it", it.toLong()) }

        // (pending ya vale 0 al inicio: se espera el resultado real, no ese estado de partida)
        assertTrue(awaitUntil { sink.remote.size == 3 && manager.status.value.pending == 0 })
        assertEquals("el servidor tiene cada lectura UNA vez", listOf("k1", "k2", "k3"), sink.remote.toList())
        assertTrue("pero se envió más de una vez: ${sink.batches}", sink.batches.sumOf { it.size } > 3)
        assertNotNull(job)
        job.cancel()
    }

    @Test
    fun `un error de la base de datos local no termina el sincronizador`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        store.failNextMarks = 3
        val (manager, job) = start(store, sink, online)
        store.add("k1", 1)
        assertTrue(awaitUntil { manager.status.value.lastError == "disco lleno" })
        assertTrue("sigue vivo y termina", awaitUntil { "k1" in sink.remote && manager.status.value.pending == 0 })
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun `se detiene al cancelar`() = runBlocking {
        val store = FakeStore(); val sink = FakeSink(); val online = MutableStateFlow(true)
        val (_, job) = start(store, sink, online)
        delay(100)
        job.cancel()
        delay(100)
        assertTrue(job.isCancelled)
        store.add("late", 1)
        delay(200)
        assertEquals(0, sink.attempts.get())
    }
}
