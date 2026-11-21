package cl.avelyn.bd_control_app.viewmodel

import cl.avelyn.bd_control_app.model.repository.Authenticator
import cl.avelyn.bd_control_app.model.repository.DeviceAccessSource
import cl.avelyn.bd_control_app.model.repository.OtpSendResult
import cl.avelyn.bd_control_app.model.repository.OtpVerifyResult
import cl.avelyn.bd_control_app.model.repository.PasswordAuthResult
import cl.avelyn.bd_control_app.model.security.AccessPolicy
import cl.avelyn.bd_control_app.model.security.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthViewModelTest {

    private class FakeAuthenticator : Authenticator {
        var lastUsername: String? = null
        var lastSentEmail: String? = null
        var lastVerifiedCode: String? = null
        var shouldFailPassword: Boolean = false
        var shouldFailOtp: Boolean = false

        override suspend fun signInWithPassword(username: String, password: CharArray): PasswordAuthResult {
            lastUsername = username
            return if (shouldFailPassword) {
                PasswordAuthResult.Error("Usuario o contraseña incorrectos.")
            } else {
                PasswordAuthResult.Success(username, "$username@example.com")
            }
        }

        override suspend fun sendOtp(email: String): OtpSendResult {
            lastSentEmail = email
            return OtpSendResult.Success
        }

        override suspend fun verifyOtp(email: String, code: String): OtpVerifyResult {
            lastVerifiedCode = code
            return if (shouldFailOtp) {
                OtpVerifyResult.Error("Código inválido")
            } else {
                OtpVerifyResult.Success(email)
            }
        }

        override suspend fun verifyDeepLink(uriString: String): OtpVerifyResult {
            return OtpVerifyResult.Success("test@example.com")
        }

        override suspend fun signOut() {}
    }

    private class FakeDeviceAccess : DeviceAccessSource {
        override suspend fun policyFor(email: String): AccessPolicy {
            return AccessPolicy(emptyList())
        }
    }

    @Test
    fun testSkipLoginOpensSessionWithWildcardPermission() {
        val session = SessionManager()
        val viewModel = AuthViewModel(FakeAuthenticator(), FakeDeviceAccess(), session)

        viewModel.skipLogin()

        assertTrue(viewModel.ui.value.isLoggedIn)
        assertEquals("tester@local", session.email)
        assertTrue(session.policy.canView("esp32"))
        assertTrue(session.policy.canControl("esp32"))
    }
}
