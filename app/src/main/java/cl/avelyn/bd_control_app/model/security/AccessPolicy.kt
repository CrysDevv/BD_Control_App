package cl.avelyn.bd_control_app.model.security

/** Permisos de un usuario sobre un dispositivo concreto (una fila de la tabla `device_permissions`). */
data class DevicePermission(
    val deviceId: String,
    val canView: Boolean,
    val canControl: Boolean
)

/**
 * Política de acceso por dispositivo: decide si el usuario con sesión iniciada puede **consultar**
 * (ver telemetría e historial) o **controlar** (enviar comandos) *ese* dispositivo en particular.
 *
 * - **Denegar por defecto:** un dispositivo sin fila de permisos no se ve ni se controla. Así
 *   un ESP32 desconocido que aparezca en la red no se muestra.
 * - **Controlar exige poder ver:** `can_control` solo vale si `can_view` también es verdadero.
 * - **Comodín `*`:** una fila con `device_id = '*'` aplica a todos los dispositivos (administrador),
 *   y una fila específica la sobrescribe, incluso para quitar un permiso.
 */
class AccessPolicy(permissions: Collection<DevicePermission>) {

    private val byDevice: Map<String, DevicePermission> = permissions.associateBy { it.deviceId }
    private val wildcard: DevicePermission? = byDevice[WILDCARD]

    private fun find(deviceId: String): DevicePermission? = byDevice[deviceId] ?: wildcard

    fun canView(deviceId: String): Boolean = find(deviceId)?.canView == true

    fun canControl(deviceId: String): Boolean = find(deviceId)?.let { it.canView && it.canControl } == true

    companion object {
        const val WILDCARD = "*"

        /** Sin sesión: no se permite nada. */
        val DENY_ALL = AccessPolicy(emptyList())
    }
}
