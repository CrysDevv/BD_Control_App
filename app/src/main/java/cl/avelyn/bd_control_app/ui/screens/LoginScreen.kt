package cl.avelyn.bd_control_app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.BuildConfig
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.viewmodel.AuthViewModel

/**
 * Pantalla de login: un único botón "Continuar con Google" que abre el selector nativo de cuentas
 * (Credential Manager) y valida la cuenta elegida contra Supabase Auth.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onOpenSimulator: () -> Unit,
    viewModel: AuthViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(ui.isLoggedIn) {
        if (ui.isLoggedIn) onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)

        Text(
            text = stringResource(R.string.login_google_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 24.dp)
        )

        ui.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        Button(
            onClick = {
                // El selector de cuentas de Google necesita una Activity, no basta el contexto de la app.
                context.findActivity()?.let { activity -> viewModel.signInWithGoogle(activity) }
            },
            enabled = !ui.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            if (ui.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            } else {
                Text(stringResource(R.string.login_google_button))
            }
        }

        Button(
            onClick = { viewModel.skipLogin() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text("Saltar Login (Modo Pruebas)")
        }

        // Panel de simulación: herramienta de desarrollo. NO existe en las versiones de release,
        // porque se abre sin iniciar sesión.
        if (BuildConfig.DEBUG) {
            TextButton(
                onClick = onOpenSimulator,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.login_simulator_link))
            }
        }
    }
}

/** Busca la Activity detrás del contexto de un Composable (Compose a veces envuelve el contexto). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
