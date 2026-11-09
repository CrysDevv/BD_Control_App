package cl.avelyn.bd_control_app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.repository.Authenticator
import cl.avelyn.bd_control_app.model.repository.DeviceAccessSource
import cl.avelyn.bd_control_app.model.repository.PasswordCheck
import cl.avelyn.bd_control_app.model.repository.PendingLogin
import cl.avelyn.bd_control_app.model.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** En qué paso del login está el usuario. */
enum class LoginStep { CREDENTIALS, VERIFICATION }

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val lockedUntilMs: Long? = null,
    val step: LoginStep = LoginStep.CREDENTIALS,
    val isLoggedIn: Boolean = false
)

/**
 * Login en dos pasos (ver [Authenticator]):
 * 1. usuario y contraseña; 2. código de un solo uso (TOTP) de la app de autenticación.
 * Solo al completar ambos, y tras cargar los permisos por dispositivo del usuario
 * ([DeviceAccessSource]), se abre la sesión ([SessionManager]) y [AuthUiState.isLoggedIn] pasa a true.
 * Si los permisos no se pueden cargar no se entra: se deniega por defecto.
 *
 * Los fallos de cualquiera de los dos pasos cuentan para el mismo límite: tras [MAX_ATTEMPTS]
 * fallos consecutivos se bloquea por [LOCKOUT_MS] y se vuelve al primer paso. Nunca se revela
 * si el usuario existe o qué paso falló en una cuenta ajena.
 */
class AuthViewModel(
    private val authenticator: Authenticator,
    private val deviceAccess: DeviceAccessSource,
    private val session: SessionManager
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    private var failedAttempts = 0
    private var pending: PendingLogin? = null

    private fun isLocked(): Boolean {
        val lockedUntil = _ui.value.lockedUntilMs
        if (lockedUntil != null && System.currentTimeMillis() < lockedUntil) {
            _ui.update { it.copy(error = "Demasiados intentos. Espera unos segundos e intenta de nuevo.") }
            return true
        }
        return false
    }

    /** Paso 1: usuario y contraseña. */
    fun login(username: String, password: CharArray) {
        if (isLocked()) return

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val result = try {
                authenticator.checkPassword(username, password)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo verificar la contraseña: ${e.message}")
                _ui.update { it.copy(isLoading = false, error = ERROR_NETWORK) }
                return@launch
            }
            when (result) {
                is PasswordCheck.InvalidCredentials -> registerFailure("Usuario o contraseña incorrectos")
                is PasswordCheck.SecondStepNotConfigured -> _ui.update {
                    it.copy(isLoading = false, error = "Tu cuenta no tiene configurada la verificación en 2 pasos. Contacta al administrador.")
                }
                is PasswordCheck.NeedsSecondStep -> {
                    pending = result.pending
                    _ui.update { it.copy(isLoading = false, step = LoginStep.VERIFICATION, error = null) }
                }
            }
        }
    }

    /** Paso 2: código de 6 dígitos. */
    fun verifyCode(code: String) {
        val current = pending ?: return
        if (isLocked()) return

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            if (!authenticator.checkSecondStep(current, code)) {
                registerFailure("Código incorrecto o vencido")
                return@launch
            }
            val policy = try {
                deviceAccess.policyFor(current.username)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron cargar los permisos: ${e.message}")
                _ui.update { it.copy(isLoading = false, error = "No se pudieron cargar tus permisos. Revisa tu conexión e inténtalo de nuevo.") }
                return@launch
            }
            session.open(current.username, policy)
            pending = null
            failedAttempts = 0
            _ui.update { it.copy(isLoading = false, isLoggedIn = true, lockedUntilMs = null) }
        }
    }

    /** Vuelve al paso 1 (por ejemplo, si se equivocó de usuario). */
    fun cancelVerification() {
        pending = null
        _ui.update { it.copy(step = LoginStep.CREDENTIALS, error = null) }
    }

    /** Cierra sesión: borra los permisos y limpia cualquier bloqueo, error o login a medias. */
    fun logout() {
        pending = null
        failedAttempts = 0
        session.close()
        _ui.value = AuthUiState()
    }

    private fun registerFailure(message: String) {
        failedAttempts++
        val lockUntil = if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0
            pending = null // tras un bloqueo hay que empezar de nuevo por la contraseña
            System.currentTimeMillis() + LOCKOUT_MS
        } else null
        _ui.update {
            it.copy(
                isLoading = false,
                error = message,
                lockedUntilMs = lockUntil,
                step = if (lockUntil != null) LoginStep.CREDENTIALS else it.step
            )
        }
    }

    companion object {
        private const val TAG = "AuthViewModel"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
        private const val ERROR_NETWORK = "No se pudo completar la verificación. Revisa tu conexión e inténtalo de nuevo."
    }
}
