package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.viewmodel.AuthViewModel
import cl.avelyn.bd_control_app.viewmodel.LoginStep

/**
 * Pantalla de login en dos pasos: primero usuario y contraseña (tabla `users` de Supabase) y,
 * si son correctos, el código de verificación de 6 dígitos de la app de autenticación.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onOpenSimulator: () -> Unit,
    viewModel: AuthViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    // Cada vez que se cambia de paso se limpia el código, para no dejar uno viejo en pantalla.
    LaunchedEffect(ui.step) { code = "" }

    LaunchedEffect(ui.isLoggedIn) {
        if (ui.isLoggedIn) onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)

        if (ui.step == LoginStep.CREDENTIALS) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.login_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp)
            )

            OutlinedTextField(
                value = code,
                onValueChange = { input -> if (input.length <= CODE_LENGTH && input.all { it in '0'..'9' }) code = input },
                label = { Text(stringResource(R.string.login_code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }

        ui.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Button(
            onClick = {
                if (ui.step == LoginStep.CREDENTIALS) {
                    viewModel.login(username, password.toCharArray())
                    password = "" // la contraseña no se queda en el estado de la pantalla
                } else {
                    viewModel.verifyCode(code)
                }
            },
            enabled = !ui.isLoading && if (ui.step == LoginStep.CREDENTIALS) {
                username.isNotBlank() && password.isNotBlank()
            } else {
                code.length == CODE_LENGTH
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            if (ui.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            } else {
                Text(
                    stringResource(
                        if (ui.step == LoginStep.CREDENTIALS) R.string.login_button else R.string.login_verify_button
                    )
                )
            }
        }

        if (ui.step == LoginStep.VERIFICATION) {
            TextButton(
                onClick = { viewModel.cancelVerification() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.login_back_button))
            }
        }

        // Acceso temporal al panel de simulación, mientras no está el ESP32 real.
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

private const val CODE_LENGTH = 6
