package cl.avelyn.bd_control_app.model.security

import java.util.concurrent.atomic.AtomicReference

/** El usuario (correo de Google) que inició sesión y lo que puede hacer con cada dispositivo. */
class UserSession(val email: String, val policy: AccessPolicy)

/**
 * Sesión activa de la app, en memoria (se pierde al cerrar la app, a propósito). Se abre solo
 * cuando el login con Google se completa y los permisos se cargaron; se cierra al cerrar sesión.
 * Mientras no haya sesión, [policy] deniega todo.
 */
class SessionManager {
    private val current = AtomicReference<UserSession?>(null)

    val email: String? get() = current.get()?.email

    val policy: AccessPolicy get() = current.get()?.policy ?: AccessPolicy.DENY_ALL

    fun open(email: String, policy: AccessPolicy) {
        current.set(UserSession(email, policy))
    }

    fun close() {
        current.set(null)
    }
}
