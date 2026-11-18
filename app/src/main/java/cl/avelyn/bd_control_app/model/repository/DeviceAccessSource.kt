package cl.avelyn.bd_control_app.model.repository

import cl.avelyn.bd_control_app.model.security.AccessPolicy

/** Origen de los permisos por dispositivo de un usuario. */
interface DeviceAccessSource {
    /** Permisos del usuario (identificado por su correo de Google); lanza excepción si no se pudieron
     *  cargar (en ese caso no se inicia sesión). */
    suspend fun policyFor(email: String): AccessPolicy
}
