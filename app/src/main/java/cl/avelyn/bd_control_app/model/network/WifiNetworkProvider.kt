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
class WifiNetworkProvider(context: Context, private val useMulticastLock: Boolean = false) {

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    /** La red Wi-Fi conectada ahora mismo (haya o no internet en ella), o null si no hay. */
    @Suppress("DEPRECATION") // allNetworks sigue funcionando; es la forma más simple de no depender de "validated"
    fun currentWifi(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * Emite una vez al empezar y luego cada vez que el teléfono (re)conecta a una red Wi-Fi.
     * [WifiIotBridge] lo usa para volver a abrir el socket UDP: tras un cambio de red el socket
     * anterior queda atado a una red que ya no existe y deja de recibir sin dar error.
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

    // ENERGÍA: por defecto NO se toma. Retener el MulticastLock obliga al Wi-Fi a recibir todo el tráfico multicast y, según
    // la documentación de Android, "puede causar un consumo de batería notable". Los anuncios del relé son BROADCAST,
    // que Android entrega sin necesitar el lock. Si en algún teléfono no llegaran, activar con `iot.multicastLock=true`.
    private val multicastLock: WifiManager.MulticastLock =
        wifi.createMulticastLock("bd_control_udp").apply { setReferenceCounted(false) }

    fun acquireMulticastLock() {
        if (useMulticastLock && !multicastLock.isHeld) multicastLock.acquire()
    }

    fun releaseMulticastLock() {
        if (multicastLock.isHeld) multicastLock.release()
    }
}
