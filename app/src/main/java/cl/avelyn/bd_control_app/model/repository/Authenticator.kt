package cl.avelyn.bd_control_app.model.repository

/** Resultado del primer paso del login (usuario y contraseña). */
sealed interface PasswordCheck {
    /** Usuario inexistente o contraseña incorrecta (a propósito no se distingue). */
    data object InvalidCredentials : PasswordCheck

    /** La contraseña es correcta pero la cuenta no tiene segundo paso configurado: no se permite entrar. */
    data object SecondStepNotConfigured : PasswordCheck

    /** La contraseña es correcta; falta el código del segundo paso. */
    class NeedsSecondStep(val pending: PendingLogin) : PasswordCheck
}

/** Login a medias: la contraseña ya se verificó, falta el código TOTP. Vive solo en memoria. */
class PendingLogin(val username: String, internal val totpSecret: ByteArray)

/** Login en dos pasos. [AuthViewModel] depende de esta interfaz, no de Supabase. */
interface Authenticator {
    /** Paso 1: usuario y contraseña. */
    suspend fun checkPassword(username: String, password: CharArray): PasswordCheck

    /** Paso 2: código de 6 dígitos de la app de autenticación. */
    fun checkSecondStep(pending: PendingLogin, code: String): Boolean
}
