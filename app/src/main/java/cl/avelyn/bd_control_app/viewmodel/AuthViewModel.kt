package cl.avelyn.bd_control_app.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.avelyn.bd_control_app.model.repository.Authenticator
import cl.avelyn.bd_control_app.model.repository.DeviceAccessSource
import cl.avelyn.bd_control_app.model.repository.GoogleSignInResult
import cl.avelyn.bd_control_app.model.security.AccessPolicy
import cl.avelyn.bd_control_app.model.security.DevicePermission
import cl.avelyn.bd_control_app.model.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false
)

/**
 * Login con Google (ver [Authenticator]): el usuario elige su cuenta con el selector nativo y,
 * solo al validarla contra Supabase y cargar sus permisos por dispositivo ([DeviceAccessSource]),
 * se abre la sesión ([SessionManager]) y [AuthUiState.isLoggedIn] pasa a true.
 * Si los permisos no se pueden cargar no se entra (se deniega por defecto) y se cierra la sesión
 * recién abierta en Supabase, para no dejarla a medias.
 */
class AuthViewModel(
    private val authenticator: Authenticator,
    private val deviceAccess: DeviceAccessSource,
    private val session: SessionManager
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    /** Abre el selector de cuentas de Google. [activityContext] hace falta para mostrarlo. */
    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            when (val result = authenticator.signInWithGoogle(activityContext)) {
                is GoogleSignInResult.Cancelled -> _ui.update { it.copy(isLoading = false) }

                is GoogleSignInResult.NoGoogleAccount -> _ui.update {
                    it.copy(isLoading = false, error = "No hay ninguna cuenta de Google en este teléfono. Agrega una en Ajustes y vuelve a intentarlo.")
                }

                is GoogleSignInResult.Error -> _ui.update { it.copy(isLoading = false, error = result.message) }

                is GoogleSignInResult.Success -> {
                    val policy = try {
                        deviceAccess.policyFor(result.email)
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudieron cargar los permisos: ${e.message}")
                        authenticator.signOut()
                        _ui.update { it.copy(isLoading = false, error = "No se pudieron cargar tus permisos. Revisa tu conexión e inténtalo de nuevo.") }
                        return@launch
                    }
                    session.open(result.email, policy)
                    _ui.update { it.copy(isLoading = false, isLoggedIn = true) }
                }
            }
        }
    }

    /** Permite saltar la autenticación para facilitar pruebas rápidas sin Google/Supabase. */
    fun skipLogin() {
        val testPolicy = AccessPolicy(
            listOf(DevicePermission(AccessPolicy.WILDCARD, canView = true, canControl = true))
        )
        session.open("tester@local", testPolicy)
        _ui.update { it.copy(isLoading = false, error = null, isLoggedIn = true) }
    }

    /** Cierra sesión: borra los permisos, la sesión de Supabase y limpia cualquier error pendiente. */
    fun logout() {
        session.close()
        viewModelScope.launch { authenticator.signOut() }
        _ui.value = AuthUiState()
    }

    companion object {
        private const val TAG = "AuthViewModel"
    }
}
