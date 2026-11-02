package cl.avelyn.bd_control_app.model.repository

import android.util.Base64
import cl.avelyn.bd_control_app.model.security.PasswordHasher
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

/** Fila de la tabla `users` en Supabase. Ver el script SQL del informe para crearla. */
@Serializable
private data class UserRow(
    val username: String,
    val password_hash: String,
    val salt: String
)

/**
 * Autenticación contra la tabla `users` de Supabase. La app nunca crea usuarios por su
 * cuenta: las cuentas se dan de alta una sola vez con un INSERT en el SQL Editor de
 * Supabase (ver el script del informe), nunca desde el código. Las contraseñas tampoco
 * viajan ni se comparan en texto plano: se verifican con [PasswordHasher] contra el
 * hash y la salt guardados en la fila.
 */
class AuthRepository(private val client: SupabaseClient) {

    /**
     * Verifica las credenciales contra Supabase.
     * @return true si el usuario existe y la contraseña es correcta.
     */
    suspend fun login(username: String, password: CharArray): Boolean {
        val user = client.from("users")
            .select(columns = Columns.list("username", "password_hash", "salt")) {
                filter { eq("username", username) }
            }
            .decodeSingleOrNull<UserRow>() ?: return false

        val salt = Base64.decode(user.salt, Base64.NO_WRAP)
        val expectedHash = Base64.decode(user.password_hash, Base64.NO_WRAP)
        return PasswordHasher.verify(password, salt, expectedHash)
    }
}
