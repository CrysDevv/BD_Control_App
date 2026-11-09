package cl.avelyn.bd_control_app.model.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Transporte real: habla por Wi-Fi con los ESP32 (ver `firmware/`). Implementa los mismos
 * contratos que [SupabaseIotBridge], así que los ViewModels no cambian al pasar de uno a otro.
 *
 * **Telemetría (UDP broadcast, puerto 4210):** [listen] recibe los mensajes de TODOS los
 * sensores y relés de la red, cada uno con su `id`. De cada anuncio de relé se aprende su IP
 * (la dirección de origen del datagrama), así se pueden controlar varios relés sin
 * configurar direcciones a mano.
 *
 * **Comandos (TCP cifrado AES-256-GCM, puerto 5000):** [setFan] abre una conexión corta al
 * relé indicado, envía el comando y lee su respuesta.
 *
 * **Reconexión:**
 * - El socket UDP se reabre solo, con espera creciente, si falla (ver [receiveLoop]).
 * - Se reabre también cada vez que el teléfono se conecta a una red Wi-Fi ([WifiNetworkProvider]).
 * - Cada comando se reintenta [MAX_COMMAND_ATTEMPTS] veces antes de reportar error.
 * - Un dispositivo que deja de anunciarse se marca "sin conexión" en el ViewModel y vuelve
 *   solo cuando reaparece: no hay sesión que reconstruir porque ni UDP ni los comandos TCP la usan.
 */
class WifiIotBridge(
    private val networks: WifiNetworkProvider,
    aesKeyHex: String
) : SensorDataSource, FanCommandSender {

    /** Se interpreta al primer comando: así una llave mal configurada solo afecta a [setFan], no a la telemetría. */
    private val aesKey: ByteArray by lazy { WireProtocol.hexToKey(aesKeyHex) }

    /** IP de cada relé, aprendida de sus anuncios UDP. */
    private val relayAddresses = ConcurrentHashMap<String, InetAddress>()

    // ---------- Telemetría UDP ----------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun listen(): Flow<UdpMsg> =
        networks.wifiAvailableEvents()
            .flatMapLatest { receiveLoop() }
            // El lock se maneja aquí y no dentro de receiveLoop: flatMapLatest solapa el cierre del
            // loop viejo con el inicio del nuevo, y un release tardío dejaría al nuevo sin lock.
            .onStart { networks.acquireMulticastLock() }
            .onCompletion { networks.releaseMulticastLock() }

    private fun receiveLoop(): Flow<UdpMsg> = flow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val socket = try {
                openUdpSocket()
            } catch (e: IOException) {
                Log.w(TAG, "No se pudo abrir el socket UDP: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            try {
                backoffMs = INITIAL_BACKOFF_MS
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val msg = try {
                        receiveMessage(socket)
                    } catch (_: SocketTimeoutException) {
                        continue // sin datos: se vuelve a revisar si la corrutina sigue activa
                    } catch (e: IOException) {
                        Log.w(TAG, "Socket UDP caído, se reabrirá: ${e.message}")
                        break
                    }
                    if (msg != null) emit(msg)
                }
            } finally {
                socket.close()
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun openUdpSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = UDP_RECEIVE_TIMEOUT_MS
            networks.currentWifi()?.bindSocket(socket) // fuerza Wi-Fi aunque la red no tenga internet
            socket.bind(InetSocketAddress(WireProtocol.UDP_PORT))
            return socket
        } catch (e: Exception) {
            socket.close()
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun receiveMessage(socket: DatagramSocket): UdpMsg? {
        val packet = DatagramPacket(ByteArray(UDP_BUFFER_SIZE), UDP_BUFFER_SIZE)
        socket.receive(packet)
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val msg = WireProtocol.parseDatagram(text) ?: return null
        if (msg is UdpMsg.Relay) relayAddresses[msg.deviceId] = packet.address
        return msg
    }

    // ---------- Comandos TCP cifrados ----------

    override suspend fun setFan(deviceId: String, on: Boolean): Result<RelayReply> =
        exchange(deviceId) { WireProtocol.fanCommandJson(deviceId, on) }

    override suspend fun heartbeat(deviceId: String): Result<RelayReply> =
        exchange(deviceId) { WireProtocol.heartbeatJson(deviceId) }

    /** Envía un comando cifrado al relé y espera su respuesta, con reintentos ante fallos de red. */
    private suspend fun exchange(deviceId: String, json: () -> String): Result<RelayReply> = runCatching {
        var lastError: IOException? = null
        for (attempt in 1..MAX_COMMAND_ATTEMPTS) {
            try {
                return@runCatching withContext(Dispatchers.IO) { sendEncrypted(deviceId, json()) }
            } catch (e: IOException) {
                // Solo se reintentan fallos de red. Una llave inválida (IllegalArgumentException),
                // un relé desconocido (IllegalStateException) o una cancelación se propagan tal cual.
                lastError = e
                Log.w(TAG, "Comando a $deviceId falló (intento $attempt/$MAX_COMMAND_ATTEMPTS): ${e.message}")
                if (attempt < MAX_COMMAND_ATTEMPTS) delay(COMMAND_RETRY_DELAY_MS * attempt)
            }
        }
        throw IOException("No se pudo comunicar con el relé \"$deviceId\"", lastError)
    }

    private fun sendEncrypted(deviceId: String, json: String): RelayReply {
        val host = relayAddresses[deviceId] ?: run {
            // Sin anuncio previo solo se puede asumir la IP fija del relé cuando es el punto de acceso (firmware antiguo).
            check(deviceId == DeviceIds.DEFAULT_RELAY) { "El relé \"$deviceId\" aún no se ha anunciado en la red" }
            InetAddress.getByName(FALLBACK_RELAY_IP)
        }
        val key = aesKey
        val frame = WireProtocol.encryptFrame(key, json.toByteArray(Charsets.UTF_8))

        return Socket().use { socket ->
            networks.currentWifi()?.bindSocket(socket) // antes de conectar
            socket.soTimeout = TCP_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, WireProtocol.TCP_PORT), TCP_CONNECT_TIMEOUT_MS)
            socket.getOutputStream().apply { write(frame); flush() }

            val input = DataInputStream(socket.getInputStream())
            val len = input.readUnsignedShort()
            if (len !in WireProtocol.MIN_BODY_LEN..WireProtocol.MAX_BODY_LEN) throw IOException("Respuesta con largo inválido ($len)")
            val body = ByteArray(len).also { input.readFully(it) }
            val plain = WireProtocol.decryptBody(key, body) ?: throw IOException("Respuesta del relé no autenticada")
            WireProtocol.parseRelayReply(String(plain, Charsets.UTF_8)) ?: throw IOException("Respuesta del relé ilegible")
        }
    }

    private companion object {
        const val TAG = "WifiIotBridge"
        const val UDP_BUFFER_SIZE = 512
        const val UDP_RECEIVE_TIMEOUT_MS = 1000
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8000L
        const val TCP_CONNECT_TIMEOUT_MS = 3000
        const val TCP_READ_TIMEOUT_MS = 3000
        const val MAX_COMMAND_ATTEMPTS = 3
        const val COMMAND_RETRY_DELAY_MS = 400L

        /** IP fija del relé cuando actúa como punto de acceso (ver `relay.ino`). */
        const val FALLBACK_RELAY_IP = "192.168.4.1"
    }
}
