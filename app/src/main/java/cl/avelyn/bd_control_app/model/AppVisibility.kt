package cl.avelyn.bd_control_app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ¿La app está a la vista del usuario? Mientras no lo esté se deja de escuchar sensores y de decidir: ahorra batería
 * (se cierra el puerto USB, el socket y los temporizadores). No se pierde nada: el sensor sigue guardando lecturas en su
 * buffer y se recuperan al volver, y el relé entra en modo seguro si pasa mucho rato (ver docs/plan_contingencia.md).
 */
interface AppVisibility {
    val isForeground: StateFlow<Boolean>
}

/** Siempre en primer plano (pruebas). */
object AlwaysForeground : AppVisibility {
    override val isForeground: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
