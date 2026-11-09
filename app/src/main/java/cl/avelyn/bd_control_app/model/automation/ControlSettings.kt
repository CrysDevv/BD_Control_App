package cl.avelyn.bd_control_app.model.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Preferencias del control del ventilador y del ahorro de energía. */
interface ControlSettings {
    /** Relés en modo automático (por humedad). Si el usuario toma el control a mano, el relé sale de esta lista. */
    val automaticRelays: StateFlow<Set<String>>
    fun setAutomatic(relayId: String, enabled: Boolean)

    /** Modo ahorro de energía: el sensor mide más espaciado cuando la humedad está quieta (ver AdaptiveSampler). */
    val powerSaving: StateFlow<Boolean>
    fun setPowerSaving(enabled: Boolean)
}

/** En memoria (pruebas). */
class InMemoryControlSettings(automatic: Set<String> = emptySet(), powerSaving: Boolean = true) : ControlSettings {
    private val _auto = MutableStateFlow(automatic)
    private val _saving = MutableStateFlow(powerSaving)
    override val automaticRelays: StateFlow<Set<String>> = _auto.asStateFlow()
    override val powerSaving: StateFlow<Boolean> = _saving.asStateFlow()

    override fun setAutomatic(relayId: String, enabled: Boolean) {
        _auto.update { if (enabled) it + relayId else it - relayId }
    }

    override fun setPowerSaving(enabled: Boolean) {
        _saving.value = enabled
    }
}
