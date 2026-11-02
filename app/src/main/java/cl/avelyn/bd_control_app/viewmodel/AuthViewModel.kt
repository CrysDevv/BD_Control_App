package cl.avelyn.bd_control_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val lockedUntilMs: Long? = null,
    val isLoggedIn: Boolean = false
)

/**
 * Login contra la tabla `users` de Supabase (ver [AuthRepository]). La app no crea ni
 * siembra cuentas: las credenciales válidas se dan de alta una sola vez por SQL. Bloquea
 * nuevos intentos por [LOCKOUT_MS] tras [MAX_ATTEMPTS] fallos consecutivos, sin revelar
 * si el usuario existe o no.
 */
class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    private var failedAttempts = 0

    fun login(username: String, password: CharArray) {
        val lockedUntil = _ui.value.lockedUntilMs
        if (lockedUntil != null && System.currentTimeMillis() < lockedUntil) {
            _ui.update { it.copy(error = "Demasiados intentos. Espera unos segundos e intenta de nuevo.") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val success = runCatching { authRepository.login(username, password) }.getOrDefault(false)
            if (success) {
                failedAttempts = 0
                _ui.update { it.copy(isLoading = false, isLoggedIn = true, lockedUntilMs = null) }
            } else {
                failedAttempts++
                val lockUntil = if (failedAttempts >= MAX_ATTEMPTS) {
                    failedAttempts = 0
                    System.currentTimeMillis() + LOCKOUT_MS
                } else null
                _ui.update {
                    it.copy(isLoading = false, error = "Usuario o contraseña incorrectos", lockedUntilMs = lockUntil)
                }
            }
        }
    }

    /** Cierra sesión y limpia también cualquier bloqueo o error pendiente. */
    fun logout() {
        failedAttempts = 0
        _ui.value = AuthUiState()
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
