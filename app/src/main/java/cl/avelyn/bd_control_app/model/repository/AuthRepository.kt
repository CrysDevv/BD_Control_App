package cl.avelyn.bd_control_app.model.repository

import android.util.Base64
import cl.avelyn.bd_control_app.model.security.PasswordHasher
import cl.avelyn.bd_control_app.model.security.Totp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Fila de la tabla `users` en Supabase. Ver `docs/supabase_2fa_permisos.sql` para crearla/ampliarla. */
@Serializable
private data class UserRow(
    val username: String,
    val password_hash: String,
    val salt: String,
    /** Secreto TOTP en Base32. Null mientras el usuario no tenga configurado el segundo paso. */
    val totp_secret: String? = null
)

/**
 * Autenticación en dos pasos contra la tabla `users` de Supabase:
 * 1. algo que sabes: la contraseña, verificada con [PasswordHasher] contra el hash y la salt guardados;
 * 2. algo que tienes: un código TOTP ([Totp]) generado por la app de autenticación del usuario.
 *
 * Las cuentas se dan de alta una sola vez por SQL (ver `tools/new_user.py`), nunca desde la app.
 * Un código TOTP ya aceptado no se puede reutilizar (anti-replay), aunque se cierre sesión y se vuelva a entrar.
 */
class AuthRepository(private val client: SupabaseClient) : Authenticator {

    private val lastAcceptedStep = ConcurrentHashMap<String, Long>()

    override suspend fun checkPassword(username: String, password: CharArray): PasswordCheck {
        val user = client.from("users")
            .select(columns = Columns.list("username", "password_hash", "salt", "totp_secret")) {
                filter { eq("username", username) }
            }
            .decodeSingleOrNull<UserRow>() ?: return PasswordCheck.InvalidCredentials

        val salt = Base64.decode(user.salt, Base64.NO_WRAP)
        val expectedHash = Base64.decode(user.password_hash, Base64.NO_WRAP)
        if (!PasswordHasher.verify(password, salt, expectedHash)) return PasswordCheck.InvalidCredentials

        val secret = user.totp_secret?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Totp.decodeBase32(it) }.getOrNull() }
            ?: return PasswordCheck.SecondStepNotConfigured
        return PasswordCheck.NeedsSecondStep(PendingLogin(user.username, secret))
    }

    override fun checkSecondStep(pending: PendingLogin, code: String): Boolean {
        val last = lastAcceptedStep[pending.username] ?: -1L
        val step = Totp.verify(pending.totpSecret, code.trim(), System.currentTimeMillis(), lastUsedStep = last)
            ?: return false
        lastAcceptedStep[pending.username] = step
        return true
    }
}
