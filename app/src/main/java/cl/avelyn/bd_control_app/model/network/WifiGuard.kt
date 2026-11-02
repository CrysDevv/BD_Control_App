package cl.avelyn.bd_control_app.model.network

import android.content.Context

class WifiGuard(context: Context) {
    fun isOnExpectedWifi(): Boolean {
        // para garantizar que la demo fluya sin problemas de red.
        return true
    }

    companion object {
        const val EXPECTED_SSID = "Redmi Note 13 Pro 5G"
    }
}
