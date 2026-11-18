package cl.avelyn.bd_control_app.viewmodel

import android.content.Context
import cl.avelyn.bd_control_app.model.repository.Authenticator
import cl.avelyn.bd_control_app.model.repository.DeviceAccessSource
import cl.avelyn.bd_control_app.model.repository.GoogleSignInResult
import cl.avelyn.bd_control_app.model.security.AccessPolicy
import cl.avelyn.bd_control_app.model.security.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthViewModelTest {

    private class FakeAuthenticator : Authenticator {
        override suspend fun signInWithGoogle(activityContext: Context): GoogleSignInResult {
            return GoogleSignInResult.Success("test@example.com")
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
