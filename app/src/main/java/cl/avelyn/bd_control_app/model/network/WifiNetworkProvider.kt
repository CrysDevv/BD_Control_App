package cl.avelyn.bd_control_app.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Acceso a la red Wi-Fi del teléfono para hablar con los ESP32.
 *
 * Problema que resuelve: la red de los ESP32 (`BODEGA_NET`) no tiene internet. Android puede
 * decidir enviar el tráfico por datos móviles aunque el teléfono esté conectado a ella, y
 * entonces ni los comandos TCP ni los broadcast UDP llegan. Por eso los sockets se atan
 * explícitamente a la red Wi-Fi con [Network.bindSocket] (ver [WifiIotBridge]).
 */
data class WifiInfoState(
    val isConnected: Boolean = false,
    val isListeningUdp: Boolean = true
)

/**
 * Acceso a la red Wi-Fi del teléfono para hablar con los ESP32.
 */
class WifiNetworkProvider(context: Context, private val useMulticastLock: Boolean = true) {

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi by lazy { appContext.getSystemService(WifiManager::class.java) }

    /** La red Wi-Fi conectada ahora mismo (haya o no internet en ella), o null si no hay. */
    @Suppress("DEPRECATION")
    fun currentWifi(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * Emite una vez al empezar y luego cada vez que el teléfono (re)conecta a una red Wi-Fi.
     */
    fun wifiAvailableEvents(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        trySend(Unit)
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    /** Estado reactivo de la red Wi-Fi para actualizar la UI. */
    fun wifiNetworkState(): Flow<WifiInfoState> = callbackFlow {
        val update = {
            val isWifi = currentWifi() != null
            trySend(WifiInfoState(isConnected = isWifi, isListeningUdp = true))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { update() }
            override fun onLost(network: Network) { update() }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        update()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    private val multicastLock: WifiManager.MulticastLock by lazy {
        wifi.createMulticastLock("bd_control_udp").apply { setReferenceCounted(false) }
    }

    fun acquireMulticastLock() {
        if (useMulticastLock && !multicastLock.isHeld) {
            try { multicastLock.acquire() } catch (_: Exception) {}
        }
    }

    fun releaseMulticastLock() {
        if (useMulticastLock && multicastLock.isHeld) {
            try { multicastLock.release() } catch (_: Exception) {}
        }
    }
}
