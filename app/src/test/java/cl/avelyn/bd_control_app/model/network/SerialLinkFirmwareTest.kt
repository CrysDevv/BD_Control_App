package cl.avelyn.bd_control_app.model.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import cl.avelyn.bd_control_app.model.sync.ReadingsSink
import cl.avelyn.bd_control_app.model.sync.SyncManager
import cl.avelyn.bd_control_app.model.sync.SyncStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prueba de integración: el [SerialLink] real de la app contra el firmware REAL del sensor.
 *
 * `firmware/sensor/test/host_sim.cpp` compila el mismo `bd_serial.h` que corre en el ESP32 como un
 * programa de computador; aquí se lanza como proceso y sus pipes hacen de "cable USB". Así se prueba
 * el protocolo de punta a punta (CRC, comandos, buffer, recuperación de lecturas) sin hardware.
 * Requiere `g++`; si no está disponible la prueba se omite (no falla).
 */
class SerialLinkFirmwareTest {

    // ---------- "Cable" hacia el simulador del firmware ----------

    private class ProcessPort(private val proc: Process, private val killOnClose: Boolean) : SerialPortIo {
        private val input = proc.inputStream
        private val output = proc.outputStream

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val available = input.available()
                if (available > 0) return input.read(buffer, 0, minOf(available, buffer.size))
                if (!proc.isAlive) {
                    if (input.available() > 0) continue
                    throw IOException("El cable se desconectó")
                }
                if (System.currentTimeMillis() >= deadline) return 0
                Thread.sleep(3)
            }
        }

        override fun write(data: ByteArray, timeoutMs: Int) {
            if (!proc.isAlive) throw IOException("El cable se desconectó")
            output.write(data)
            output.flush()
        }

        override fun isAlive() = proc.isAlive
        override fun close() { if (killOnClose) proc.destroyForcibly() }
    }

    /**
     * [persistent] = el ESP32 sigue encendido aunque la app cierre el puerto (el teléfono lo alimenta con la
     * app cerrada). Si no, cerrar el puerto equivale a desenchufar el cable: la placa se apaga y, al volver,
     * arranca de nuevo con otro bootId (como el firmware real, que lo elige al azar).
     */
    private class FakeCable(
        private val simulator: File,
        private val args: List<String>,
        private val persistent: Boolean = false
    ) : SerialPortProvider {
        @Volatile var plugged = true
        private val processes = CopyOnWriteArrayList<Process>()
        private val boots = AtomicInteger(1000)

        override fun open(): PortOpenResult {
            if (!plugged) return PortOpenResult.NoDevice
            val process = if (persistent && processes.isNotEmpty()) processes.first() else {
                val extra = if (args.contains("--boot-id")) emptyList() else listOf("--boot-id", boots.incrementAndGet().toString())
                ProcessBuilder(listOf(simulator.absolutePath) + args + extra).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    .also { processes += it }
            }
            return PortOpenResult.Opened(ProcessPort(process, killOnClose = !persistent), "ESP32 (simulado)")
        }

        fun pullCable() {
            plugged = false
            processes.lastOrNull()?.destroyForcibly()
        }

        fun shutdown() = processes.forEach { it.destroyForcibly() }
    }

    private val fast = SerialLinkConfig(
        readTimeoutMs = 50, pingAfterMs = 500, deadAfterMs = 1500, infoEveryMs = 300,
        commandTimeoutMs = 300, idleRetryMs = 100, initialBackoffMs = 100, maxBackoffMs = 400,
        confEveryMs = 100, syncMinGapMs = 100, syncStallMs = 1000
    )

    private fun sim(vararg extra: String, persistent: Boolean = false, binary: File = simulator!!) =
        FakeCable(binary, listOf("--id", "sensor-1", "--interval", "200", "--min-interval", "100") + extra, persistent)

    /** Hace lo que el ViewModel real: guarda cada lectura y, recién entonces, se lo confirma al enlace. */
    private class Consumer(val link: SerialLink) {
        val readings = CopyOnWriteArrayList<UdpMsg.Sensor>()
        private var job: Job? = null

        fun start(scope: CoroutineScope) {
            job = scope.launch(Dispatchers.Default) {
                link.listen().collect { msg ->
                    if (msg is UdpMsg.Sensor) {
                        readings += msg
                        msg.sample?.let { link.markStored(msg.deviceId, it) }
                    }
                }
            }
        }

        fun stop() { job?.cancel() }
        fun ns() = readings.mapNotNull { it.sample?.n }
        fun uniqueNs() = ns().toSortedSet()
    }

    private suspend fun awaitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; delay(25) }
        return condition()
    }

    private suspend fun SerialLink.awaitConnected(ms: Long = 5000) =
        withTimeoutOrNull(ms) { state.first { it is SerialLinkState.Connected && it.deviceId != null } }

    private fun scope() = CoroutineScope(Dispatchers.Default)

    // ---------- Pruebas ----------

    @Test
    fun `recibe lecturas del firmware e ignora el log de arranque del ESP32`() = runBlocking {
        val cable = sim("--noise")
        val link = SerialLink(cable, fast)
        val consumer = Consumer(link).also { it.start(scope()) }
        try {
            assertTrue(awaitUntil(5000) { consumer.readings.size >= 3 })
            val first = consumer.readings.first()
            assertTrue(first.deviceId == "sensor-1" && first.viaSerial && first.sample != null)
            val stats = link.stats.value
            assertTrue("ruido ignorado: $stats", stats.noiseLines >= 3 && stats.framesCorrupt == 0L && stats.framesLost == 0L)
        } finally { consumer.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `una instruccion de la app llega al firmware y este la obedece`() = runBlocking {
        val cable = sim()
        val link = SerialLink(cable, fast)
        val consumer = Consumer(link).also { it.start(scope()) }
        try {
            assertNotNull(link.awaitConnected())
            delay(1500)
            val before = consumer.readings.size
            assertTrue(link.setSampleInterval("sensor-1", 1000).isSuccess)
            assertEquals(1000, (link.state.value as SerialLinkState.Connected).intervalMs)
            delay(300)
            val mark = consumer.readings.size
            delay(3200)
            val after = consumer.readings.size - mark
            assertTrue("antes=$before después=$after", before >= 4 && after in 2..5)
            assertTrue(link.setSampleInterval("sensor-1", 10).isFailure) // fuera de rango: ni se envía
            assertTrue(link.setSampleInterval("otro", 5000).isFailure)  // no es el sensor conectado
        } finally { consumer.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `las tramas danadas se detectan y se recuperan sin perder ninguna lectura`() = runBlocking {
        val cable = sim("--corrupt-every", "3", "--corrupt-buf")   // 1 de cada 3 tramas de datos llega dañada, también en la repetición
        val link = SerialLink(cable, fast)
        val consumer = Consumer(link).also { it.start(scope()) }
        try {
            delay(4000)
            val stats = link.stats.value
            val ns = consumer.uniqueNs()
            val max = ns.last()
            assertTrue("hubo tramas dañadas: $stats", stats.framesCorrupt >= 4)
            // todas las lecturas hasta las últimas en vuelo llegaron, sin huecos ni duplicados
            val missing = (1..(max - 3)).filter { it !in ns }
            assertTrue("faltan $missing ($stats)", missing.isEmpty())
            assertEquals("sin duplicados", ns.size, consumer.ns().size)
            assertTrue("se recuperaron por SYNC: $stats", stats.recovered >= 3)
            assertTrue(consumer.readings.all { it.tempC in 20.0f..20.95f && it.humPct in 50.0f..59.0f })
        } finally { consumer.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `con la app cerrada y el cable conectado el sensor guarda y la app recupera todo con su hora`() = runBlocking {
        val cable = sim("--link-timeout", "1000", persistent = true)
        // --- la app escucha un rato ---
        val link1 = SerialLink(cable, fast)
        val c1 = Consumer(link1).also { it.start(scope()) }
        assertTrue(awaitUntil(5000) { c1.readings.size >= 3 })
        delay(500)
        c1.stop(); delay(200)                                   // la app se cierra; la placa sigue encendida
        val closedAt = System.currentTimeMillis()
        val lastLive = c1.uniqueNs().last()
        delay(3500)                                             // 3,5 s sin app: el sensor solo guarda (≈17 lecturas a 200 ms)
        // --- la app vuelve a abrirse ---
        val reopenedAt = System.currentTimeMillis()
        val link2 = SerialLink(cable, fast)
        val c2 = Consumer(link2).also { it.start(scope()) }
        try {
            assertTrue(awaitUntil(6000) { link2.stats.value.recovered >= 12 })
            delay(500)
            val offline = c2.readings.filter { it.capturedAtMs != null }.sortedBy { it.sample!!.n }
            assertTrue("recuperadas: ${offline.size}", offline.size >= 12)
            // continuidad: las recuperadas empiezan donde terminó lo que se vio en vivo (o justo antes) y no tienen huecos
            val ns = offline.map { it.sample!!.n }
            assertTrue("sin huecos: $ns", ns.zipWithNext().all { (a, b) -> b == a + 1 })
            assertTrue("empalma con lo visto en vivo", ns.first() <= lastLive + 3)
            // fechas: crecientes, separadas ≈ 200 ms y dentro de la ventana en que la app estuvo cerrada
            val ts = offline.map { it.capturedAtMs!! }
            assertTrue("fechas crecientes", ts.zipWithNext().all { (a, b) -> b > a })
            val gaps = ts.zipWithNext().map { (a, b) -> b - a }
            assertTrue("separación ≈ 200 ms: $gaps", gaps.all { it in 150..260 })
            assertTrue("dentro de la ventana", ts.first() >= closedAt - 1500 && ts.last() <= reopenedAt + 300)
            // los datos son los del sensor
            assertTrue(offline.all { it.tempC in 20.0f..20.95f && it.humPct in 50.0f..59.0f })
            // ya se guardaron y confirmaron: el sensor libera su buffer
            assertTrue("buffer liberado", awaitUntil(3000) { link2.stats.value.sensorBuffered >= 0 })
        } finally { c2.stop(); delay(150) }

        // --- un tercer arranque de la app: no debe quedar nada pendiente (todo se confirmó) ---
        val link3 = SerialLink(cable, fast)
        val c3 = Consumer(link3).also { it.start(scope()) }
        try {
            assertNotNull(link3.awaitConnected())
            assertTrue("el sensor liberó lo confirmado: ${link3.stats.value}", awaitUntil(3000) { link3.stats.value.sensorCapacity > 0 && link3.stats.value.sensorBuffered <= 4 })
        } finally { c3.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `si el buffer del sensor se llena se pierde lo mas antiguo y la app lo sabe`() = runBlocking {
        val small = smallSimulator
        assumeTrue("no se pudo compilar el simulador con buffer pequeño", small != null)
        val cable = sim("--link-timeout", "1000", persistent = true, binary = small!!)   // buffer de 10 lecturas
        val link1 = SerialLink(cable, fast)
        val c1 = Consumer(link1).also { it.start(scope()) }
        assertTrue(awaitUntil(5000) { c1.readings.size >= 2 })
        c1.stop(); delay(200)
        delay(3500)                                              // ≈17 lecturas con capacidad 10: se pierden ≈7
        val link2 = SerialLink(cable, fast)
        val c2 = Consumer(link2).also { it.start(scope()) }
        try {
            assertTrue(awaitUntil(6000) { link2.stats.value.recovered >= 8 })
            delay(1000)
            val stats = link2.stats.value
            assertEquals(10, stats.sensorCapacity)
            assertTrue("descartadas reportadas: $stats", stats.sensorDropped >= 4)
            val offline = c2.readings.filter { it.capturedAtMs != null }.map { it.sample!!.n }.sorted()
            assertTrue("solo caben 10: ${offline.size}", offline.size in 8..11)
            assertTrue("sin pedir en bucle lo que ya no existe", link2.stats.value.recovered < 20)
        } finally { c2.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `se reconecta solo despues de desenchufar y volver a enchufar el cable`() = runBlocking {
        val cable = sim()
        val link = SerialLink(cable, fast)
        val consumer = Consumer(link).also { it.start(scope()) }
        try {
            assertTrue(awaitUntil(5000) { consumer.readings.isNotEmpty() })
            cable.pullCable()
            assertNotNull(withTimeoutOrNull(3000) { link.state.first { it == SerialLinkState.NoCable } })
            val before = consumer.readings.size
            cable.plugged = true
            assertNotNull("no reconectó", link.awaitConnected())
            assertTrue("la telemetría se reanuda (con otro bootId)", awaitUntil(3000) { consumer.readings.size > before })
            assertTrue(link.stats.value.reconnects >= 1)
            assertTrue(consumer.readings.map { it.sample!!.bootId }.toSet().size >= 2)
        } finally { consumer.stop(); delay(150); cable.shutdown() }
    }

    @Test
    fun `los comandos se reintentan si se pierde la respuesta`() = runBlocking {
        val dropped = AtomicInteger(2)
        val inner = sim()
        val cable = SerialPortProvider {
            when (val r = inner.open()) {
                is PortOpenResult.Opened -> PortOpenResult.Opened(object : SerialPortIo by r.io {
                    // se "pierden" los 2 primeros comandos SET en el cable
                    override fun write(data: ByteArray, timeoutMs: Int) {
                        if (String(data).startsWith("\$SET") && dropped.getAndDecrement() > 0) return
                        r.io.write(data, timeoutMs)
                    }
                }, r.name)
                else -> r
            }
        }
        val link = SerialLink(cable, fast)
        val job = scope().launch { link.listen().collect { } }
        try {
            assertNotNull(link.awaitConnected())
            assertTrue("debió llegar al 3er intento", link.setSampleInterval("sensor-1", 2000).isSuccess)
        } finally { job.cancel(); delay(150); inner.shutdown() }
    }

    // ---------- Cadena completa: sensor -> app -> base local -> nube ----------

    /** La "memoria del teléfono": sobrevive entre una apertura de la app y la siguiente. */
    private class PhoneDb : SensorLogDao, SyncStore {
        val rows = MutableStateFlow<List<SensorLog>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(log: SensorLog): Long = synchronized(this) {
            if (rows.value.any { it.deviceId == log.deviceId && it.sampleKey == log.sampleKey }) return@synchronized -1L
            rows.value = rows.value + log.copy(id = nextId++)
            nextId - 1
        }
        override fun latestFor(deviceId: String, limit: Int): Flow<List<SensorLog>> = rows.map { it.filter { r -> r.deviceId == deviceId }.take(limit) }
        override fun deviceIds(): Flow<List<String>> = rows.map { l -> l.map { it.deviceId }.distinct() }
        override suspend fun unsynced(limit: Int) = rows.value.filter { !it.synced }.sortedBy { it.timestamp }.take(limit)
        override fun unsyncedCount(): Flow<Int> = rows.map { l -> l.count { !it.synced } }
        override suspend fun unsyncedCountNow() = rows.value.count { !it.synced }
        override suspend fun markSynced(ids: List<Long>) = synchronized(this) { rows.value = rows.value.map { if (it.id in ids) it.copy(synced = true) else it } }
        override suspend fun purgeSyncedBefore(beforeEpochMillis: Long) = Unit
        override suspend fun dropOldestUnsynced(count: Int) = Unit
    }

    /** "Supabase": ignora duplicados por (dispositivo, clave), como el índice único del servidor. */
    private class FakeCloud : ReadingsSink {
        val received = CopyOnWriteArrayList<String>()
        val uniqueRows = linkedMapOf<String, SensorLog>()
        override suspend fun upload(rows: List<SensorLog>): Result<Unit> {
            synchronized(this) { rows.forEach { received += it.sampleKey; uniqueRows.putIfAbsent("${it.deviceId}/${it.sampleKey}", it) } }
            return Result.success(Unit)
        }
    }

    /** Lo que hace el ViewModel real: guardar en la base local y, si quedó guardado, confirmarle al sensor. */
    private fun persistingConsumer(
        link: SerialLink, repo: SensorRepository, scope: CoroutineScope, onStored: (Long) -> Unit = {}
    ): Job = scope.launch(Dispatchers.Default) {
        link.listen().collect { msg ->
            if (msg is UdpMsg.Sensor) {
                repo.record(msg)
                msg.sample?.let { link.markStored(msg.deviceId, it); onStored(it.n) }
            }
        }
    }

    @Test
    fun `las lecturas del periodo sin app llegan a la nube una sola vez aunque no haya internet al recuperarlas`() = runBlocking {
        val cable = sim("--link-timeout", "1000", persistent = true)
        val phone = PhoneDb()
        val cloud = FakeCloud()
        val online = MutableStateFlow(false)                       // el teléfono NO tiene internet durante todo el episodio
        val syncJob = scope().launch { SyncManager(phone, cloud, online).run() }
        val repo = SensorRepository(phone)

        // 1) la app escucha un rato
        val link1 = SerialLink(cable, fast)
        val lastLive = java.util.concurrent.atomic.AtomicLong(0)
        val job1 = persistingConsumer(link1, repo, scope()) { n -> lastLive.accumulateAndGet(n, ::maxOf) }
        assertTrue(awaitUntil(5000) { lastLive.get() >= 3 })
        delay(500)
        job1.cancel(); delay(200)
        val maxLiveN = lastLive.get()

        // 2) la app está cerrada 3,5 s: el sensor sigue vivo y guarda
        delay(3500)

        // 3) la app vuelve a abrirse y recupera, todavía SIN internet
        val link2 = SerialLink(cable, fast)
        val job2 = persistingConsumer(link2, repo, scope())
        try {
            assertTrue(awaitUntil(6000) { link2.stats.value.recovered >= 12 })
            delay(600)
            assertEquals("sin internet no se sube nada", 0, cloud.received.size)
            val local = phone.rows.value
            val localKeys = local.map { it.sampleKey }
            assertTrue("hay ${local.size} lecturas guardadas en el teléfono, todas pendientes", local.size >= 12 && local.none { it.synced })
            assertEquals("sin duplicados en el teléfono", localKeys.size, localKeys.toSet().size)

            // 4) vuelve internet: se sube todo
            online.value = true
            assertTrue("subió todo", awaitUntil(5000) { cloud.received.size >= local.size && phone.rows.value.all { it.synced } })
            assertEquals("cada lectura llegó a la nube UNA vez", cloud.received.size, cloud.received.toSet().size)
            assertEquals(phone.rows.value.map { it.sampleKey }.toSet(), cloud.received.toSet())

            // 5) no falta ninguna lectura del periodo en que la app estuvo cerrada: empiezan justo después de la última
            //    vista en vivo y no tienen huecos (las anteriores pudieron guardarse o no según el espaciado en vivo)
            val offlineNs = local.map { it.sampleKey.substringAfter('-').toLong() }.filter { it > maxLiveN }.sorted()
            assertTrue("hay lecturas del periodo sin app: $offlineNs", offlineNs.size >= 12)
            assertEquals("empalma exactamente con lo visto en vivo", maxLiveN + 1, offlineNs.first())
            assertTrue("sin huecos: $offlineNs", offlineNs.zipWithNext().all { (a, b) -> b == a + 1 })
            assertTrue("todas llegaron a la nube", offlineNs.all { n -> cloud.received.any { it.endsWith("-$n") } })
        } finally { job2.cancel(); syncJob.cancel(); delay(150); cable.shutdown() }
    }

    companion object {
        private var simulator: File? = null
        private var smallSimulator: File? = null

        private fun compile(source: File, vararg flags: String): File? {
            val out = File.createTempFile("bd_host_sim", "")
            out.deleteOnExit()
            val ok = try {
                ProcessBuilder(listOf("g++", "-std=c++11", "-O1") + flags + listOf("-o", out.absolutePath, source.absolutePath))
                    .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor(60, TimeUnit.SECONDS)
            } catch (_: IOException) { false }
            return if (ok && out.length() > 0) out else null
        }

        @JvmStatic
        @BeforeClass
        fun buildSimulators() {
            val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "firmware/sensor/test/host_sim.cpp") }
                .firstOrNull { it.exists() }
            assumeTrue("No se encontró firmware/sensor/test/host_sim.cpp", source != null)
            simulator = compile(source!!)
            assumeTrue("g++ no está disponible: se omite la prueba contra el firmware real", simulator != null)
            smallSimulator = compile(source, "-DBD_BUFFER_CAPACITY=10")   // para probar el desborde del buffer
        }

        @JvmStatic
        @AfterClass
        fun cleanup() { simulator?.delete(); smallSimulator?.delete() }
    }
}
