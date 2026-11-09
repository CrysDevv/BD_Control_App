package cl.avelyn.bd_control_app.model.repository

import cl.avelyn.bd_control_app.model.security.AccessPolicy
import cl.avelyn.bd_control_app.model.security.DevicePermission
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

/** Fila de la tabla `device_permissions`. Ver `docs/supabase_2fa_permisos.sql`. */
@Serializable
private data class PermissionRow(
    val device_id: String,
    val can_view: Boolean = false,
    val can_control: Boolean = false
)

/** Carga desde Supabase qué dispositivos puede consultar y controlar cada usuario. */
class DeviceAccessRepository(private val client: SupabaseClient) : DeviceAccessSource {

    override suspend fun policyFor(username: String): AccessPolicy {
        val rows = client.from("device_permissions")
            .select(columns = Columns.list("device_id", "can_view", "can_control")) {
                filter { eq("username", username) }
            }
            .decodeList<PermissionRow>()
        return AccessPolicy(rows.map { DevicePermission(it.device_id, it.can_view, it.can_control) })
    }
}
