package cl.avelyn.bd_control_app.model.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import cl.avelyn.bd_control_app.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import java.security.MessageDigest
import java.util.UUID

/**
 * Login con la cuenta de Google del usuario, contra Supabase Auth:
 * 1. Credential Manager pide una cuenta de Google al teléfono (sin salir de la app) y entrega un idToken.
 * 2. Ese idToken se valida en Supabase ([io.github.jan.supabase.auth.Auth]), que abre la sesión.
 *
 * Reemplaza el login manual anterior (usuario/contraseña + TOTP contra una tabla `users` propia,
 * ver `docs/migracion_google_auth.md`): ahora la identidad la gestionan Google y Supabase, y ya no
 * existe ninguna contraseña que guardar ni verificar en nuestro lado.
 */
class AuthRepository(
    private val client: SupabaseClient,
    private val appContext: Context
) : Authenticator {

    override suspend fun signInWithGoogle(activityContext: Context): GoogleSignInResult {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank() || webClientId.contains("REEMPLAZAR")) {
            Log.e(TAG, "google.webClientId no está configurado en local.properties")
            return GoogleSignInResult.Error("Google Web Client ID no está configurado. Agrega 'google.webClientId' en local.properties.")
        }

        // Nonce de un solo uso: Google lo firma dentro del idToken y aquí se lo mandamos a Supabase
        // en texto plano para que compruebe que es el mismo nonce (evita que un idToken capturado
        // se pueda reutilizar más tarde).
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = sha256(rawNonce)

        val idToken = try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false) // también ofrece cuentas que nunca usaron esta app
                .setAutoSelectEnabled(false) // siempre mostrar el selector de cuentas, nunca elegir solo
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

            val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
            GoogleIdTokenCredential.createFrom(response.credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            return GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            return GoogleSignInResult.NoGoogleAccount
        } catch (e: GetCredentialException) {
            Log.w(TAG, "No se pudo abrir el selector de cuentas de Google: ${e.message}")
            return GoogleSignInResult.Error("No se pudo abrir el selector de cuentas de Google: ${e.message}")
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG, "Credencial de Google inválida: ${e.message}")
            return GoogleSignInResult.Error("Google devolvió una credencial inválida. Intenta de nuevo.")
        } catch (e: Exception) {
            Log.e(TAG, "Error en inicio de sesión con Google: ${e.message}", e)
            return GoogleSignInResult.Error("Error al validar con servidor: ${e.message ?: e.toString()}")
        }

        return try {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                nonce = rawNonce
            }
            val email = client.auth.currentUserOrNull()?.email
                ?: return GoogleSignInResult.Error("Tu cuenta de Google no tiene un correo asociado.")
            GoogleSignInResult.Success(email)
        } catch (e: Exception) {
            Log.w(TAG, "Supabase rechazó el inicio de sesión con Google: ${e.message}")
            GoogleSignInResult.Error("Supabase rechazó el inicio de sesión: ${e.message ?: "Revisa tu conexión o configuración de Supabase."}")
        }
    }

    override suspend fun signOut() {
        runCatching { client.auth.signOut() }
        // Olvida la cuenta recordada por Credential Manager; si no, la próxima vez que se pida login
        // puede auto-seleccionarse sola la misma cuenta y no dejar elegir otra.
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }

    companion object {
        private const val TAG = "AuthRepository"

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
