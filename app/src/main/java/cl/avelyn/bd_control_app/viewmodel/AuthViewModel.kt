package cl.avelyn.bd_control_app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.repository.Authenticator
import cl.avelyn.bd_control_app.model.repository.DeviceAccessSource
import cl.avelyn.bd_control_app.model.repository.OtpSendResult
import cl.avelyn.bd_control_app.model.repository.OtpVerifyResult
import cl.avelyn.bd_control_app.model.repository.PasswordAuthResult
import cl.avelyn.bd_control_app.model.security.AccessPolicy
import cl.avelyn.bd_control_app.model.security.DevicePermission
import cl.avelyn.bd_control_app.model.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep {
    USERNAME_PASSWORD,
    OTP_CODE
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val step: AuthStep = AuthStep.USERNAME_PASSWORD,
    val username: String = "",
    val email: String = ""
)

/**
 * Gestor del flujo de inicio de sesión de dos pasos:
 * 1. Validación de nombre de usuario y contraseña contra la tabla `users` en Supabase.
 * 2. Verificación mediante código OTP de 6 dígitos o presionado de enlace directo desde el correo (Deep Link).
 */
class AuthViewModel(
    private val authenticator: Authenticator,
    private val deviceAccess: DeviceAccessSource,
    private val session: SessionManager
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun onUsernameChanged(newUsername: String) {
        _ui.update { it.copy(username = newUsername, error = null) }
    }

    /** Paso 1: Valida usuario y contraseña en Supabase `users` y envía el correo con el enlace / código OTP. */
    fun loginWithPassword(usernameInput: String = _ui.value.username, passwordInput: String) {
        val trimmedUsername = usernameInput.trim()
        Log.d(TAG, "[UI-EVENT] Intento de loginWithPassword con usuario = '$trimmedUsername'")

        if (trimmedUsername.isBlank() || passwordInput.isBlank()) {
            val msg = "Por favor ingresa usuario y contraseña."
            Log.w(TAG, "[UI-EVENT] Validación fallida en UI: $msg")
            _ui.update { it.copy(error = msg) }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null, username = trimmedUsername) }
            Log.d(TAG, "[UI-VM] Llamando a authenticator.signInWithPassword('$trimmedUsername')...")
            val authResult = authenticator.signInWithPassword(trimmedUsername, passwordInput.toCharArray())
            when (authResult) {
                is PasswordAuthResult.Error -> {
                    Log.w(TAG, "[UI-VM] Error devuelto por signInWithPassword: ${authResult.message}")
                    _ui.update { it.copy(isLoading = false, error = authResult.message) }
                }
                is PasswordAuthResult.Success -> {
                    val userEmail = authResult.email
                    Log.d(TAG, "[UI-VM] signInWithPassword EXITOSO. Usuario: '${authResult.username}', Email: '$userEmail'. Enviando OTP...")
                    val otpResult = authenticator.sendOtp(userEmail)
                    when (otpResult) {
                        is OtpSendResult.Error -> {
                            Log.w(TAG, "[UI-VM] Error al enviar OTP a '$userEmail': ${otpResult.message}")
                            _ui.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Contraseña correcta, pero no se pudo enviar el correo: ${otpResult.message}"
                                )
                            }
                        }
                        is OtpSendResult.Success -> {
                            Log.d(TAG, "[UI-VM] OTP enviado con éxito a '$userEmail'. Cambiando UI a AuthStep.OTP_CODE.")
                            _ui.update {
                                it.copy(
                                    isLoading = false,
                                    step = AuthStep.OTP_CODE,
                                    username = authResult.username,
                                    email = userEmail,
                                    error = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reenvía el correo de autenticación al correo del usuario activo. */
    fun resendOtp() {
        val email = _ui.value.email
        Log.d(TAG, "[UI-EVENT] Reenviando código OTP a '$email'...")
        if (email.isBlank()) {
            Log.w(TAG, "[UI-EVENT] No hay email configurado para reenviar OTP.")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            when (val result = authenticator.sendOtp(email)) {
                is OtpSendResult.Error -> {
                    Log.w(TAG, "[UI-VM] Error reenviando OTP: ${result.message}")
                    _ui.update { it.copy(isLoading = false, error = result.message) }
                }
                is OtpSendResult.Success -> {
                    Log.d(TAG, "[UI-VM] OTP reenviado con éxito a '$email'")
                    _ui.update { it.copy(isLoading = false, error = null) }
                }
            }
        }
    }

    /** Paso 2 (manual): Verifica el código OTP de 6 dígitos ingresado por el usuario. */
    fun verifyOtp(code: String) {
        val trimmedCode = code.trim()
        Log.d(TAG, "[UI-EVENT] Intento de verifyOtp con código de ${trimmedCode.length} dígitos")

        if (trimmedCode.length < 6) {
            val msg = "El código OTP debe tener 6 dígitos."
            Log.w(TAG, "[UI-EVENT] Código OTP demasiado corto: '$trimmedCode'")
            _ui.update { it.copy(error = msg) }
            return
        }

        val email = _ui.value.email
        val username = _ui.value.username
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            Log.d(TAG, "[UI-VM] Verificando código OTP con authenticator.verifyOtp('$email', '$trimmedCode')...")
            when (val result = authenticator.verifyOtp(email, trimmedCode)) {
                is OtpVerifyResult.Error -> {
                    Log.w(TAG, "[UI-VM] Error en verifyOtp: ${result.message}")
                    _ui.update { it.copy(isLoading = false, error = result.message) }
                }
                is OtpVerifyResult.Success -> {
                    Log.d(TAG, "[UI-VM] OTP verificado EXITOSAMENTE para '${result.email}'. Cargando permisos de usuario...")
                    val policy = try {
                        deviceAccess.policyFor(result.email)
                    } catch (e: Exception) {
                        Log.w(TAG, "[UI-VM] Excepción al cargar permisos para '${result.email}': ${e.message}", e)
                        authenticator.signOut()
                        _ui.update {
                            it.copy(
                                isLoading = false,
                                error = "No se pudieron cargar tus permisos. Revisa tu conexión e inténtalo de nuevo."
                            )
                        }
                        return@launch
                    }
                    val userIdentity = username.ifBlank { result.email }
                    Log.d(TAG, "[UI-VM] Permisos cargados. Abriendo sesión para '$userIdentity'...")
                    session.open(userIdentity, policy)
                    _ui.update { it.copy(isLoading = false, isLoggedIn = true) }
                }
            }
        }
    }

    /** Paso 2 (automático): Procesa el enlace de autenticación presionado desde el correo (Deep Link). */
    fun handleDeepLink(uriString: String) {
        val username = _ui.value.username
        Log.d(TAG, "[UI-EVENT] Recibido Deep Link para procesar: '$uriString'")
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            when (val result = authenticator.verifyDeepLink(uriString)) {
                is OtpVerifyResult.Error -> {
                    Log.w(TAG, "[UI-VM] Error verificando Deep Link: ${result.message}")
                    _ui.update { it.copy(isLoading = false, error = result.message) }
                }
                is OtpVerifyResult.Success -> {
                    Log.d(TAG, "[UI-VM] Deep Link verificado EXITOSAMENTE para '${result.email}'. Cargando permisos...")
                    val policy = try {
                        deviceAccess.policyFor(result.email)
                    } catch (e: Exception) {
                        Log.w(TAG, "[UI-VM] Excepción al cargar permisos por Deep Link: ${e.message}", e)
                        authenticator.signOut()
                        _ui.update {
                            it.copy(
                                isLoading = false,
                                error = "No se pudieron cargar tus permisos. Revisa tu conexión e inténtalo de nuevo."
                            )
                        }
                        return@launch
                    }
                    val userIdentity = username.ifBlank { result.email }
                    Log.d(TAG, "[UI-VM] Abriendo sesión por Deep Link para '$userIdentity'...")
                    session.open(userIdentity, policy)
                    _ui.update { it.copy(isLoading = false, isLoggedIn = true) }
                }
            }
        }
    }

    /** Permite volver al paso de ingresar usuario y contraseña. */
    fun resetToPasswordStep() {
        Log.d(TAG, "[UI-EVENT] Volviendo al paso de usuario y contraseña (USERNAME_PASSWORD)")
        _ui.update { it.copy(step = AuthStep.USERNAME_PASSWORD, error = null) }
    }

    /** Permite saltar la autenticación para facilitar pruebas rápidas (Modo Pruebas). */
    fun skipLogin() {
        Log.d(TAG, "[UI-EVENT] Activando Modo Pruebas (skipLogin). Usuario: 'tester@local'")
        val testPolicy = AccessPolicy(
            listOf(DevicePermission(AccessPolicy.WILDCARD, canView = true, canControl = true))
        )
        session.open("tester@local", testPolicy)
        _ui.update { it.copy(isLoading = false, error = null, isLoggedIn = true) }
    }

    /** Cierra sesión: borra los permisos, la sesión y limpia cualquier error pendiente. */
    fun logout() {
        Log.d(TAG, "[UI-EVENT] Cerrando sesión de usuario...")
        session.close()
        viewModelScope.launch { authenticator.signOut() }
        _ui.value = AuthUiState()
    }

    companion object {
        private const val TAG = "AuthViewModel"
    }
}
