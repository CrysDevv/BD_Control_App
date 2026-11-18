package cl.avelyn.bd_control_app.model.repository

import android.content.Context

/** Resultado de pedirle a Google una cuenta e iniciar sesión con ella en Supabase. */
sealed interface GoogleSignInResult {
    /** El usuario cerró el selector de cuentas sin elegir ninguna. No es un error. */
    data object Cancelled : GoogleSignInResult

    /** El teléfono no tiene ninguna cuenta de Google configurada. */
    data object NoGoogleAccount : GoogleSignInResult

    /** Se inició sesión en Supabase con esta cuenta. */
    class Success(val email: String) : GoogleSignInResult

    /** Google o Supabase rechazaron el intento; [message] ya está listo para mostrarse. */
    class Error(val message: String) : GoogleSignInResult
}

/** Login con la cuenta de Google del usuario. [AuthViewModel] depende de esta interfaz, no de Supabase. */
interface Authenticator {
    /** Abre el selector nativo de cuentas de Google y, si se elige una, valida la sesión contra Supabase. */
    suspend fun signInWithGoogle(activityContext: Context): GoogleSignInResult

    /** Cierra la sesión en Supabase y olvida la cuenta elegida (para poder entrar con otra la próxima vez). */
    suspend fun signOut()
}
