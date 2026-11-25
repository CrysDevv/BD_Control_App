package cl.avelyn.bd_control_app.model.repository

import android.net.Uri
import android.util.Log
import cl.avelyn.bd_control_app.model.security.PasswordHasher
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
private data class UserDbRow(
    val username: String,
    val password_hash: String,
    val salt: String,
    val email: String? = null
)

/**
 * Autenticación en dos pasos:
 * 1. [signInWithPassword]: Valida usuario y contraseña contra la tabla `users` de Supabase.
 * 2. [sendOtp]: Envía correo con código OTP / Magic Link con redirección a `bdcontrol://login`.
 * 3. [verifyOtp]: Comprueba el código OTP de 6 dígitos.
 * 4. [verifyDeepLink]: Procesa automáticamente el enlace presionado desde la app de correo.
 */
class AuthRepository(
    private val client: SupabaseClient
) : Authenticator {

    override suspend fun signInWithPassword(username: String, password: CharArray): PasswordAuthResult {
        val trimmedUsername = username.trim()
        Log.d(TAG, "[LOGIN-PASO-1] Iniciando validación para usuario: '$trimmedUsername' (contraseña de ${password.size} caracteres)")

        if (trimmedUsername.isBlank()) {
            Log.w(TAG, "[LOGIN-PASO-1] Rechazado: El nombre de usuario está en blanco.")
            return PasswordAuthResult.Error("Por favor ingresa un nombre de usuario.")
        }

        val rows = try {
            Log.d(TAG, "[LOGIN-PASO-1] Consultando tabla 'users' en Supabase para username = '$trimmedUsername'...")
            client.from("users")
                .select {
                    filter { eq("username", trimmedUsername) }
                }
                .decodeList<UserDbRow>()
        } catch (e: Exception) {
            Log.w(TAG, "[LOGIN-PASO-1] Error de red / DB al consultar usuario '$trimmedUsername': ${e.message}", e)
            return PasswordAuthResult.Error("Error de conexión con la base de datos: ${e.message}")
        }

        Log.d(TAG, "[LOGIN-PASO-1] Filas retornadas de 'users': ${rows.size}")
        val userRow = rows.firstOrNull()
        if (userRow == null) {
            Log.w(TAG, "[LOGIN-PASO-1] Rechazado: Usuario '$trimmedUsername' no existe en la base de datos.")
            return PasswordAuthResult.Error("Usuario o contraseña incorrectos.")
        }

        val saltBytes = try {
            Base64.getDecoder().decode(userRow.salt)
        } catch (e: Exception) {
            Log.e(TAG, "[LOGIN-PASO-1] Error decodificando salt para '$trimmedUsername'", e)
            return PasswordAuthResult.Error("Formato de datos de usuario inválido en el servidor.")
        }

        val expectedHashBytes = try {
            Base64.getDecoder().decode(userRow.password_hash)
        } catch (e: Exception) {
            Log.e(TAG, "[LOGIN-PASO-1] Error decodificando hash para '$trimmedUsername'", e)
            return PasswordAuthResult.Error("Formato de datos de usuario inválido en el servidor.")
        }

        Log.d(TAG, "[LOGIN-PASO-1] Evaluando PBKDF2 (salt de ${saltBytes.size}B, hash de ${expectedHashBytes.size}B)...")
        val passwordMatches = try {
            PasswordHasher.verify(password, saltBytes, expectedHashBytes)
        } catch (e: Exception) {
            Log.e(TAG, "[LOGIN-PASO-1] Error durante verificación PBKDF2 para '$trimmedUsername'", e)
            false
        }

        if (!passwordMatches) {
            Log.w(TAG, "[LOGIN-PASO-1] Rechazado: Contraseña no coincide con el hash guardado.")
            return PasswordAuthResult.Error("Usuario o contraseña incorrectos.")
        }

        val userEmail = userRow.email?.trim()
        if (userEmail.isNullOrBlank()) {
            Log.w(TAG, "[LOGIN-PASO-1] Rechazado: El usuario '$trimmedUsername' no tiene un email configurado.")
            return PasswordAuthResult.Error("El usuario '$trimmedUsername' no tiene un correo electrónico configurado en la base de datos.")
        }

        Log.d(TAG, "[LOGIN-PASO-1] Contraseña CORRECTA para '$trimmedUsername'. Correo asociado: '$userEmail'")
        return PasswordAuthResult.Success(username = userRow.username, email = userEmail)
    }

    override suspend fun sendOtp(email: String): OtpSendResult {
        Log.d(TAG, "[OTP-ENVIO] Solicitando código OTP a Supabase Auth para email = '$email'...")
        return try {
            client.auth.signInWith(OTP, redirectUrl = REDIRECT_URL) {
                this.email = email
            }
            Log.d(TAG, "[OTP-ENVIO] Código OTP / MagicLink enviado exitosamente a '$email'")
            OtpSendResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "[OTP-ENVIO] Error enviando correo OTP a '$email': ${e.message}", e)
            val msg = e.message ?: ""
            val userFriendlyMessage = when {
                msg.contains("rate limit", ignoreCase = true) || msg.contains("429") ->
                    "Se ha alcanzado el límite de envíos de correo de Supabase. Por favor espera 1 minuto antes de volver a intentar."
                else -> msg.ifBlank { "No se pudo enviar el correo de autenticación. Revisa tu conexión." }
            }
            OtpSendResult.Error(userFriendlyMessage)
        }
    }

    override suspend fun verifyOtp(email: String, code: String): OtpVerifyResult {
        Log.d(TAG, "[OTP-VERIF] Verificando código OTP ($code) para email '$email'...")
        return try {
            client.auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = email,
                token = code.trim()
            )
            val verifiedEmail = client.auth.currentUserOrNull()?.email ?: email
            Log.d(TAG, "[OTP-VERIF] Verificación OTP EXITOSA para '$verifiedEmail'")
            OtpVerifyResult.Success(verifiedEmail)
        } catch (e: Exception) {
            Log.w(TAG, "[OTP-VERIF] Error verificando código OTP para '$email': ${e.message}", e)
            OtpVerifyResult.Error("Código OTP inválido o expirado. Intenta de nuevo.")
        }
    }

    override suspend fun verifyDeepLink(uriString: String): OtpVerifyResult {
        return try {
            Log.d(TAG, "[DEEP-LINK] Procesando URI de autenticación: '$uriString'")
            val uri = Uri.parse(uriString)

            // Caso 1: Parámetro query token_hash (ej: ?token_hash=xxx&type=email)
            val tokenHash = uri.getQueryParameter("token_hash")
            if (!tokenHash.isNullOrBlank()) {
                Log.d(TAG, "[DEEP-LINK] Parámetro token_hash encontrado. Verificando con Supabase Auth...")
                client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, tokenHash = tokenHash)
                val verifiedEmail = client.auth.currentUserOrNull()?.email ?: ""
                Log.d(TAG, "[DEEP-LINK] Token hash verificado EXITOSAMENTE. Email: '$verifiedEmail'")
                return OtpVerifyResult.Success(verifiedEmail)
            }

            // Caso 2: Fragmento URI #access_token=xxx&refresh_token=yyy
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank()) {
                Log.d(TAG, "[DEEP-LINK] Fragmento encontrado en URI. Extrayendo access_token...")
                val params = fragment.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }
                val accessToken = params["access_token"]
                val refreshToken = params["refresh_token"] ?: ""
                if (!accessToken.isNullOrBlank()) {
                    Log.d(TAG, "[DEEP-LINK] Importando tokens a la sesión de Supabase Client...")
                    client.auth.importAuthToken(accessToken, refreshToken, retrieveUser = true)
                    val verifiedEmail = client.auth.currentUserOrNull()?.email ?: ""
                    Log.d(TAG, "[DEEP-LINK] Tokens importados EXITOSAMENTE. Email verificado: '$verifiedEmail'")
                    return OtpVerifyResult.Success(verifiedEmail)
                }
            }

            Log.w(TAG, "[DEEP-LINK] El enlace no contiene ni 'token_hash' ni 'access_token'.")
            OtpVerifyResult.Error("El enlace de autenticación no contiene parámetros válidos.")
        } catch (e: Exception) {
            Log.w(TAG, "[DEEP-LINK] Error procesando Deep Link '$uriString': ${e.message}", e)
            OtpVerifyResult.Error("El enlace de autenticación es inválido o ha expirado.")
        }
    }

    override suspend fun signOut() {
        Log.d(TAG, "[LOGOUT] Solicitando cierre de sesión en Supabase Auth...")
        runCatching { client.auth.signOut() }
    }

    companion object {
        private const val TAG = "AuthRepository"
        private const val REDIRECT_URL = "bdcontrol://login"
    }
}
