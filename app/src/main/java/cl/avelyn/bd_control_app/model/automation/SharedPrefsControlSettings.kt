package cl.avelyn.bd_control_app.model.automation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Guarda las preferencias en `SharedPreferences`, así sobreviven al cierre de la app. */
class SharedPrefsControlSettings(context: Context) : ControlSettings {

    private val prefs = context.applicationContext.getSharedPreferences("control_settings", Context.MODE_PRIVATE)

    private val _auto = MutableStateFlow(prefs.getStringSet(KEY_AUTO, emptySet())?.toSet() ?: emptySet())
    private val _saving = MutableStateFlow(prefs.getBoolean(KEY_SAVING, true))

    override val automaticRelays: StateFlow<Set<String>> = _auto.asStateFlow()
    override val powerSaving: StateFlow<Boolean> = _saving.asStateFlow()

    override fun setAutomatic(relayId: String, enabled: Boolean) {
        _auto.update { if (enabled) it + relayId else it - relayId }
        prefs.edit().putStringSet(KEY_AUTO, _auto.value).apply()   // putStringSet: se guarda una copia, no el conjunto vivo
    }

    override fun setPowerSaving(enabled: Boolean) {
        _saving.value = enabled
        prefs.edit().putBoolean(KEY_SAVING, enabled).apply()
    }

    private companion object {
        const val KEY_AUTO = "automatic_relays"
        const val KEY_SAVING = "power_saving"
    }
}
