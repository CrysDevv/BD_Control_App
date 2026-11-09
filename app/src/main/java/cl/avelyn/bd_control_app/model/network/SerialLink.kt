package cl.avelyn.bd_control_app.model.network

import cl.avelyn.bd_control_app.model.network.SerialProtocol.Message
import cl.avelyn.bd_control_app.model.network.SerialProtocol.Parsed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------------------------
// Abstracción del puerto: en Android es un puerto USB real (UsbSerialPortProvider); en las pruebas,
// el firmware compilado en el computador. SerialLink no sabe la diferencia.
// ---------------------------------------------------------------------------------------------

interface SerialPortIo {
    /** Lee hasta `buffer.size` bytes. Devuelve 0 si pasó [timeoutMs] sin datos. Lanza [IOException] si el enlace se perdió. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int)

    /** false si el dispositivo ya no está conectado (cable desenchufado). */
    fun isAlive(): Boolean

    fun close()
}

sealed interface PortOpenResult {
    class Opened(val io: SerialPortIo, val name: String) : PortOpenResult
    data object NoDevice : PortOpenResult
    class NeedsPermission(val name: String) : PortOpenResult
    class Failed(val message: String) : PortOpenResult
}

fun interface SerialPortProvider {
    /** Intenta abrir el puerto ahora (rápido, no espera a que se conecte un cable). */
    fun open(): PortOpenResult
}

/** Estado del enlace USB, para mostrárselo al usuario. */
sealed interface SerialLinkState {
    /** El teléfono no soporta USB host, o el modo serial está desactivado. */
    data object Unavailable : SerialLinkState

    /** No hay ningún dispositivo USB conectado. */
    data object NoCable : SerialLinkState

    data class PermissionRequired(val deviceName: String) : SerialLinkState
    data class Connecting(val deviceName: String) : SerialLinkState

    /** [deviceId] e [intervalMs] son null hasta que el sensor se presenta (`HELLO`). */
    data class Connected(val deviceName: String, val deviceId: String?, val intervalMs: Int?) : SerialLinkState

    data class Failed(val message: String) : SerialLinkState
}

/** Calidad del enlace desde que la app abrió el puerto (útil para diagnosticar cables o ruido eléctrico). */
data class LinkStats(
    val framesOk: Long = 0,
    /** Tramas que parecían tales pero llegaron dañadas (CRC o formato): se descartan y se recuperan con SYNC. */
    val framesCorrupt: Long = 0,
    /** Lecturas en vivo que no llegaron (saltos en el contador del sensor). Se recuperan pidiéndolas de nuevo (SYNC). */
    val framesLost: Long = 0,
    /** Líneas que no son tramas (log de arranque del ESP32). No cuentan como error. */
    val noiseLines: Long = 0,
    val overflows: Long = 0,
    /** Veces que se perdió el enlace y se reabrió el puerto. */
    val reconnects: Long = 0,
    /** Lecturas recuperadas del buffer del sensor (las que llegaron mientras la app no escuchaba o se dañaron en vivo). */
    val recovered: Long = 0,
    /** Última foto del buffer del sensor: lecturas pendientes, capacidad, y las que se perdieron por desborde. */
    val sensorBuffered: Int = 0,
    val sensorCapacity: Int = 0,
    val sensorDropped: Long = 0
)

data class SerialLinkConfig(
    /** ENERGÍA: cada vuelta sin datos despierta la CPU; 500 ms son 2 despertares por segundo en vez de 5. Los datos que llegan se leen al instante. */
    val readTimeoutMs: Int = 500,
    val writeTimeoutMs: Int = 500,
    /** Sin recibir nada por este tiempo se envía un `PING` (el sensor puede estar en un intervalo largo). */
    val pingAfterMs: Long = 4_000,
    /** Sin recibir nada (ni siquiera `PONG`) por este tiempo se da el enlace por perdido y se reabre el puerto. */
    val deadAfterMs: Long = 12_000,
    /** Mientras no se conozca el id del sensor se le pregunta (`INFO`) con esta frecuencia. */
    val infoEveryMs: Long = 1_000,
    val commandTimeoutMs: Long = 1_000,
    val commandAttempts: Int = 3,
    val idleRetryMs: Long = 1_500,
    /** Cada cuánto como mucho se le confirma al sensor lo ya guardado (CONF). */
    val confEveryMs: Long = 500,
    /** Mínimo entre dos SYNC, y tiempo sin actividad tras el cual un SYNC sin terminar se da por perdido. */
    val syncMinGapMs: Long = 500,
    val syncStallMs: Long = 3_000,
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 8_000
)

/** Lo que la interfaz necesita saber del enlace serial. */
interface SerialSensorPort : SensorConfigSender {
    val state: StateFlow<SerialLinkState>
    val stats: StateFlow<LinkStats>

    /**
     * La app ya **guardó de forma durable** esa lectura (o decidió descartarla a propósito). Recién entonces
     * se le confirma al sensor para que la libere de su buffer; antes de eso la conserva y la reenvía si hace falta.
     */
    fun markStored(deviceId: String, sample: SampleRef)
}

/** Sin serial (teléfono sin USB host, o desactivado en la configuración). */
object NoSerialPort : SerialSensorPort {
    override val state: StateFlow<SerialLinkState> = MutableStateFlow(SerialLinkState.Unavailable).asStateFlow()
    override val stats: StateFlow<LinkStats> = MutableStateFlow(LinkStats()).asStateFlow()
    override suspend fun setSampleInterval(deviceId: String, intervalMs: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Este teléfono no soporta sensores por USB"))

    override fun markStored(deviceId: String, sample: SampleRef) = Unit
}

/**
 * Lleva la cuenta de qué lecturas de un arranque del sensor (un [bootId]) ya se recibieron y cuáles
 * ya se guardaron. Dos ideas:
 * - **recibidas:** para no entregar dos veces la misma lectura (llega en vivo y de nuevo en un SYNC) y
 *   para detectar huecos (lecturas que se dañaron en el cable).
 * - **guardadas, en orden:** [storedUpTo] es la última n tal que TODAS las anteriores ya están guardadas. Es lo único
 *   que se le confirma al sensor (CONF es acumulativo), así que nunca se libera una lectura que tenga un hueco antes.
 */
internal class SampleTracker(val bootId: Long) {
    private var initialized = false
    private val delivered = HashSet<Long>()
    private val stored = TreeSet<Long>()

    /** Última n confirmable (todas las anteriores guardadas), o -1 si aún no se conoce desde dónde cuenta el sensor. */
    @get:Synchronized
    var storedUpTo: Long = -1
        private set

    @get:Synchronized
    var highest: Long = 0
        private set

    val isInitialized: Boolean @Synchronized get() = initialized

    /** true si [n] es nueva (no se había recibido ni guardado antes). */
    @Synchronized
    fun accept(n: Long): Boolean {
        if (initialized && n <= storedUpTo) return false
        if (!delivered.add(n)) return false
        if (n > highest) highest = n
        if (delivered.size > MAX_TRACKED) delivered.removeAll { it < highest - MAX_TRACKED }
        return true
    }

    /** El sensor informa cuál es la lectura más antigua que aún conserva: lo anterior ya no se puede (ni hace falta) recuperar. */
    @Synchronized
    fun initialize(oldest: Long) {
        val base = oldest - 1
        if (!initialized || base > storedUpTo) storedUpTo = base
        initialized = true
        advance()
    }

    @Synchronized
    fun markStored(n: Long) {
        stored.add(n)
        advance()
    }

    private fun advance() {
        if (!initialized) return
        stored.removeAll { it <= storedUpTo }
        while (stored.remove(storedUpTo + 1)) storedUpTo++
        delivered.removeAll { it <= storedUpTo }
    }

    /** ¿Hay lecturas entre la última guardada y la más alta recibida que nunca llegaron? */
    @Synchronized
    fun hasGaps(): Boolean = initialized && highest > storedUpTo && delivered.size < highest - storedUpTo

    private companion object {
        const val MAX_TRACKED = 8_192
    }
}

/**
 * Enlace serial (USB) con el ESP32 del sensor.
 *
 * - **Recepción** ([listen]): lee tramas `$TIPO,...*CRC`, descarta las dañadas (y las cuenta) y entrega
 *   las lecturas como [UdpMsg.Sensor], igual que el transporte Wi-Fi.
 * - **Envío** ([setSampleInterval]): comandos con número de secuencia; el sensor responde `ACK`/`ERR`
 *   con la misma secuencia. Si no responde se reintenta [SerialLinkConfig.commandAttempts] veces.
 * - **Reconexión automática:** si se desenchufa el cable, el puerto falla, o el sensor deja de dar
 *   señales de vida (se le envía `PING` cuando calla; si ni así responde), se cierra el puerto y se
 *   vuelve a abrir solo, con espera creciente. No hace falta tocar la app.
 *
 * El enlace solo funciona mientras alguien recolecta [listen] (la pantalla principal).
 */
class SerialLink(
    private val provider: SerialPortProvider,
    private val config: SerialLinkConfig = SerialLinkConfig(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Hora real (ms desde 1970), para fechar las lecturas recuperadas del buffer del sensor. */
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) : SensorDataSource, SerialSensorPort {

    private val _state = MutableStateFlow<SerialLinkState>(SerialLinkState.NoCable)
    override val state: StateFlow<SerialLinkState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(LinkStats())
    override val stats: StateFlow<LinkStats> = _stats.asStateFlow()

    private class Session(val io: SerialPortIo) {
        val writeLock = Mutex()
        val pending = ConcurrentHashMap<Int, CompletableDeferred<Message>>()

        @Volatile var deviceId: String? = null

        /** bootId del sensor en esta conexión; si cambia, el sensor se reinició. */
        @Volatile var currentBoot: Long? = null

        /**
         * false hasta que llega la respuesta a un comando nuestro. Todo lo que el sensor emitió ANTES (mientras la app
         * estaba cerrada, y quedó retenido en el cable) es viejo: se ignora aquí y se recupera por SYNC con su edad real,
         * en vez de fecharlo con la hora de ahora.
         */
        @Volatile var fresh = false

        /** Hay que pedir (otra vez) las lecturas guardadas del sensor. */
        @Volatile var needSync = false
        @Volatile var syncSeq = 0
        @Volatile var syncActivityAt = 0L
        @Volatile var lastSyncAt = Long.MIN_VALUE / 2
        @Volatile var lastConfSent = -1L
        @Volatile var lastConfAt = Long.MIN_VALUE / 2
    }

    /** Un tracker por arranque del sensor; sobrevive a las reconexiones para no repetir lecturas ya entregadas. */
    private val trackers = ConcurrentHashMap<Long, SampleTracker>()
    private fun tracker(bootId: Long) = trackers.getOrPut(bootId) { SampleTracker(bootId) }

    private val active = AtomicReference<Session?>(null)
    private val seqCounter = AtomicInteger(0)

    private fun nextSeq(): Int = seqCounter.updateAndGet { if (it >= 65535) 1 else it + 1 }

    /** Arma líneas completas a partir de bytes sueltos; descarta (y avisa) las que exceden el largo máximo. */
    private class LineAssembler {
        private val buf = ByteArray(SerialProtocol.MAX_LINE)
        private var len = 0
        private var overflow = false

        /** Devuelve la línea cuando llega `\n`; [OVERFLOW] si la línea era demasiado larga; null si aún no termina. */
        fun feed(b: Byte): String? {
            when (b) {
                '\r'.code.toByte() -> return null
                '\n'.code.toByte() -> {
                    val result = when {
                        overflow -> OVERFLOW
                        len == 0 -> null
                        else -> String(buf, 0, len, Charsets.ISO_8859_1)
                    }
                    len = 0
                    overflow = false
                    return result
                }
            }
            if (overflow) return null
            if (len >= buf.size) overflow = true else buf[len++] = b
            return null
        }

        companion object {
            const val OVERFLOW = "\u0000overflow"
        }
    }

    // ---------------- Recepción + reconexión ----------------

    override fun listen(): Flow<UdpMsg> = flow {
        var backoffMs = config.initialBackoffMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val opened = try {
                provider.open()
            } catch (e: Exception) {
                PortOpenResult.Failed(e.message ?: "Error al abrir el puerto USB")
            }
            when (opened) {
                is PortOpenResult.NoDevice -> {
                    _state.value = SerialLinkState.NoCable
                    delay(config.idleRetryMs)
                    continue
                }
                is PortOpenResult.NeedsPermission -> {
                    _state.value = SerialLinkState.PermissionRequired(opened.name)
                    delay(config.idleRetryMs)
                    continue
                }
                is PortOpenResult.Failed -> {
                    _state.value = SerialLinkState.Failed(opened.message)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(config.maxBackoffMs)
                    continue
                }
                is PortOpenResult.Opened -> backoffMs = config.initialBackoffMs
            }

            val session = Session(opened.io)
            val name = opened.name
            active.set(session)
            _state.value = SerialLinkState.Connecting(name)
            try {
                val assembler = LineAssembler()
                val buffer = ByteArray(256)
                var lastRx = clockMs()
                var lastPing = lastRx
                var lastInfo = 0L
                readLoop@ while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        session.io.read(buffer, config.readTimeoutMs)
                    } catch (_: IOException) {
                        break@readLoop // cable desconectado o puerto caído
                    }
                    if (n < 0 || (n == 0 && !session.io.isAlive())) break@readLoop

                    for (i in 0 until n) {
                        val line = assembler.feed(buffer[i]) ?: continue
                        if (line == LineAssembler.OVERFLOW) {
                            _stats.update { it.copy(overflows = it.overflows + 1) }
                            continue
                        }
                        val frame = when (val parsed = SerialProtocol.parseLine(line)) {
                            is Parsed.Noise -> { _stats.update { it.copy(noiseLines = it.noiseLines + 1) }; continue }
                            is Parsed.Corrupt -> { _stats.update { it.copy(framesCorrupt = it.framesCorrupt + 1) }; continue }
                            is Parsed.Valid -> parsed
                        }
                        val msg = SerialProtocol.decode(frame)
                        if (msg == null) { _stats.update { it.copy(framesCorrupt = it.framesCorrupt + 1) }; continue }

                        val now = clockMs()
                        lastRx = now
                        _stats.update { it.copy(framesOk = it.framesOk + 1) }
                        // Barrera: la primera respuesta a un comando nuestro marca el límite entre lo viejo y lo nuevo
                        when (msg) {
                            is Message.Hello -> if (msg.seq != 0) session.fresh = true
                            is Message.Pong, is Message.Ack -> session.fresh = true
                            is Message.Err -> if (msg.seq != 0) session.fresh = true
                            else -> Unit
                        }
                        when (msg) {
                            is Message.Telemetry -> {
                                if (!session.fresh) continue // lectura vieja retenida en el cable: se recupera por SYNC
                                bootSeen(session, name, msg.bootId)
                                if (session.deviceId == null) {
                                    session.deviceId = msg.deviceId
                                    session.needSync = true
                                    _state.value = SerialLinkState.Connected(name, msg.deviceId, null)
                                }
                                val tr = tracker(msg.bootId)
                                val highestBefore = tr.highest
                                if (tr.accept(msg.n)) {
                                    if (highestBefore > 0 && msg.n > highestBefore + 1) {
                                        // saltó el contador: alguna(s) lectura(s) se dañaron en el cable; se piden de nuevo
                                        _stats.update { it.copy(framesLost = it.framesLost + (msg.n - highestBefore - 1)) }
                                        session.needSync = true
                                    }
                                    emit(UdpMsg.Sensor(msg.deviceId, msg.tempC, msg.humPct, viaSerial = true, sample = SampleRef(msg.bootId, msg.n)))
                                }
                            }
                            is Message.Buffered -> {
                                if (!session.fresh) continue
                                session.syncActivityAt = now
                                if (tracker(msg.bootId).accept(msg.n)) {
                                    _stats.update { it.copy(recovered = it.recovered + 1) }
                                    emit(
                                        UdpMsg.Sensor(
                                            msg.deviceId, msg.tempC, msg.humPct, viaSerial = true,
                                            sample = SampleRef(msg.bootId, msg.n),
                                            // el sensor no tiene reloj: dice hace cuánto midió y la app calcula la hora real
                                            capturedAtMs = wallClockMs() - msg.ageMs
                                        )
                                    )
                                }
                            }
                            is Message.SyncStart -> if (msg.seq == session.syncSeq) {
                                session.syncActivityAt = now
                                tracker(msg.bootId).initialize(msg.oldest)
                                _stats.update { it.copy(sensorBuffered = msg.pending, sensorCapacity = msg.capacity, sensorDropped = msg.dropped) }
                            }
                            is Message.SyncEnd -> if (msg.seq == session.syncSeq) {
                                session.syncSeq = 0
                                // si aún faltan lecturas (llegaron dañadas también en la repetición) se vuelve a pedir
                                session.needSync = session.currentBoot?.let { tracker(it).hasGaps() } == true
                            }
                            is Message.Hello -> {
                                session.deviceId = msg.deviceId
                                bootSeen(session, name, msg.bootId)
                                _state.value = SerialLinkState.Connected(name, msg.deviceId, msg.intervalMs)
                                if (msg.seq != 0) session.pending[msg.seq]?.complete(msg)
                            }
                            is Message.Pong -> session.pending[msg.seq]?.complete(msg)
                            is Message.Ack -> session.pending[msg.seq]?.complete(msg)
                            is Message.Err -> session.pending[msg.seq]?.complete(msg)
                        }
                    }

                    // Tareas periódicas: señales de vida, preguntar quién es el sensor (INFO, que además abre la barrera de lo viejo),
                    // recuperar lecturas y confirmar las guardadas.
                    val t = clockMs()
                    try {
                        if ((session.deviceId == null || !session.fresh) && t - lastInfo >= config.infoEveryMs) {
                            lastInfo = t
                            write(session, SerialProtocol.info(nextSeq()))
                        }
                        if (t - lastRx >= config.pingAfterMs && t - lastPing >= config.pingAfterMs) {
                            lastPing = t
                            write(session, SerialProtocol.ping(nextSeq()))
                        }
                        // SYNC en curso que dejó de dar señales: se da por perdido para poder pedirlo otra vez
                        if (session.syncSeq != 0 && t - session.syncActivityAt >= config.syncStallMs) session.syncSeq = 0
                        if (session.deviceId != null && session.needSync && session.syncSeq == 0 && t - session.lastSyncAt >= config.syncMinGapMs) {
                            val seq = nextSeq()
                            session.needSync = false
                            session.syncSeq = seq
                            session.syncActivityAt = t
                            session.lastSyncAt = t
                            write(session, SerialProtocol.sync(seq))
                        }
                        // Confirmación acumulativa: solo hasta donde TODO ya está guardado (sin huecos)
                        val tr = session.currentBoot?.let { trackers[it] }
                        if (tr != null && tr.isInitialized && tr.storedUpTo > session.lastConfSent && t - session.lastConfAt >= config.confEveryMs) {
                            val upTo = tr.storedUpTo
                            session.lastConfAt = t
                            session.lastConfSent = upTo
                            write(session, SerialProtocol.conf(nextSeq(), upTo))
                        }
                    } catch (_: IOException) {
                        break@readLoop
                    }
                    if (t - lastRx >= config.deadAfterMs) break@readLoop
                }
            } finally {
                active.set(null)
                session.pending.values.forEach { it.completeExceptionally(IOException("Se perdió el enlace USB")) }
                runCatching { session.io.close() }
                _stats.update { it.copy(reconnects = it.reconnects + 1) }
                _state.value = SerialLinkState.NoCable
            }
            delay(config.initialBackoffMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Detecta que el sensor se (re)inició (cambió su bootId): sus contadores empiezan de nuevo y hay que pedir lo que haya guardado. */
    private fun bootSeen(session: Session, @Suppress("UNUSED_PARAMETER") name: String, bootId: Long) {
        if (session.currentBoot != bootId) {
            session.currentBoot = bootId
            session.lastConfSent = -1L
            session.needSync = true
        }
    }

    private suspend fun write(session: Session, frame: ByteArray) {
        session.writeLock.withLock { withContext(Dispatchers.IO) { session.io.write(frame, config.writeTimeoutMs) } }
    }

    // ---------------- Comandos app -> sensor ----------------

    override suspend fun setSampleInterval(deviceId: String, intervalMs: Int): Result<Unit> {
        if (intervalMs !in SerialProtocol.MIN_INTERVAL_MS..SerialProtocol.MAX_INTERVAL_MS) {
            return Result.failure(IllegalArgumentException("El intervalo debe estar entre 1 y 600 segundos"))
        }
        val session = active.get()
        if (session == null || session.deviceId != deviceId) {
            return Result.failure(IOException("El sensor \"$deviceId\" no está conectado por USB"))
        }
        val reply = sendCommand(session) { SerialProtocol.setInterval(it, intervalMs) }.getOrElse { return Result.failure(it) }
        return when (reply) {
            is Message.Ack -> {
                _state.update { if (it is SerialLinkState.Connected) it.copy(intervalMs = intervalMs) else it }
                Result.success(Unit)
            }
            is Message.Err -> Result.failure(IllegalStateException("El sensor rechazó el intervalo (${reply.code})"))
            else -> Result.failure(IOException("Respuesta inesperada del sensor"))
        }
    }

    /**
     * Envía un comando y espera su respuesta (la que traiga la misma secuencia). Si no llega en
     * [SerialLinkConfig.commandTimeoutMs] se reenvía, hasta [SerialLinkConfig.commandAttempts] veces.
     * Reenviar es seguro: los comandos son idempotentes y la secuencia es la misma.
     */
    private suspend fun sendCommand(session: Session, build: (seq: Int) -> ByteArray): Result<Message> {
        val seq = nextSeq()
        val frame = build(seq)
        val waiter = CompletableDeferred<Message>()
        session.pending[seq] = waiter
        try {
            repeat(config.commandAttempts) {
                try {
                    write(session, frame)
                    val reply = withTimeoutOrNull(config.commandTimeoutMs) { waiter.await() }
                    if (reply != null) return Result.success(reply)
                } catch (e: IOException) {
                    return Result.failure(e)
                }
            }
            return Result.failure(IOException("El sensor no respondió"))
        } finally {
            session.pending.remove(seq)
        }
    }

    /** Ver [SerialSensorPort.markStored]. */
    override fun markStored(deviceId: String, sample: SampleRef) {
        trackers[sample.bootId]?.markStored(sample.n)
    }
}
