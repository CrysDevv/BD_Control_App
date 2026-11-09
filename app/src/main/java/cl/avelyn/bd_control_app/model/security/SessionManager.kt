package cl.avelyn.bd_control_app.model.security

import java.util.concurrent.atomic.AtomicReference

/** El usuario que completó los dos pasos del login y lo que puede hacer con cada dispositivo. */
class UserSession(val username: String, val policy: AccessPolicy)

/**
 * Sesión activa de la app, en memoria (se pierde al cerrar la app, a propósito). Se abre solo
 * cuando el usuario supera los dos pasos del login y los permisos se cargaron; se cierra al
 * cerrar sesión. Mientras no haya sesión, [policy] deniega todo.
 */
class SessionManager {
    private val current = AtomicReference<UserSession?>(null)

    val username: String? get() = current.get()?.username

    val policy: AccessPolicy get() = current.get()?.policy ?: AccessPolicy.DENY_ALL

    fun open(username: String, policy: AccessPolicy) {
        current.set(UserSession(username, policy))
    }

    fun close() {
        current.set(null)
    }
}
