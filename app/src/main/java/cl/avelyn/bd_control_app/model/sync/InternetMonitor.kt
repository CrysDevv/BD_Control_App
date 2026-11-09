package cl.avelyn.bd_control_app.model.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap

/**
 * Avisa cuándo el teléfono tiene internet **de verdad** (red con salida a internet y validada por Android), no solo
 * "conectado a una red". Importa porque el teléfono suele estar en el Wi-Fi de los relés (BODEGA_NET), que no tiene
 * internet: ahí Android usa los datos móviles, y esta señal sigue siendo verdadera mientras haya cualquiera de las dos.
 */
class InternetMonitor(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val online: Flow<Boolean> = callbackFlow {
        val validated: MutableSet<Network> = ConcurrentHashMap.newKeySet()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ok) validated.add(network) else validated.remove(network)
                trySend(validated.isNotEmpty())
            }

            override fun onLost(network: Network) {
                validated.remove(network)
                trySend(validated.isNotEmpty())
            }
        }
        trySend(false) // hasta que Android informe de las redes existentes (lo hace enseguida)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            callback
        )
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
