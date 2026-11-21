package cl.avelyn.bd_control_app.model.repository

/** Resultado de validar usuario y contraseña contra la tabla users de Supabase. */
sealed interface PasswordAuthResult {
    data class Success(val username: String, val email: String) : PasswordAuthResult
    data class Error(val message: String) : PasswordAuthResult
}

/** Resultado del envío del código OTP o enlace de autenticación al correo del usuario. */
sealed interface OtpSendResult {
    data object Success : OtpSendResult
    data class Error(val message: String) : OtpSendResult
}

/** Resultado de la verificación del código OTP o enlace de autenticación. */
sealed interface OtpVerifyResult {
    data class Success(val email: String) : OtpVerifyResult
    data class Error(val message: String) : OtpVerifyResult
}

/**
 * Interfaz de autenticación de dos pasos: primero usuario/contraseña en Supabase `users`,
 * seguido de código OTP o enlace de inicio de sesión automático enviado por correo.
 */
interface Authenticator {
    /** Valida el nombre de usuario y contraseña contra la tabla `users` de Supabase. */
    suspend fun signInWithPassword(username: String, password: CharArray): PasswordAuthResult

    /** Solicita a Supabase enviar el correo de autenticación (código OTP / Magic Link). */
    suspend fun sendOtp(email: String): OtpSendResult

    /** Verifica el código OTP de 6 dígitos ingresado manualmente por el usuario. */
    suspend fun verifyOtp(email: String, code: String): OtpVerifyResult

    /** Verifica y procesa el enlace de autenticación presionado desde el correo (Deep Link). */
    suspend fun verifyDeepLink(uriString: String): OtpVerifyResult

    /** Cierra la sesión activa. */
    suspend fun signOut()
}
