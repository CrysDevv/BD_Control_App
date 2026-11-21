package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.BuildConfig
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.components.StatusBadge
import cl.avelyn.bd_control_app.ui.theme.DarkBackground
import cl.avelyn.bd_control_app.ui.theme.DarkBorder
import cl.avelyn.bd_control_app.ui.theme.DarkSurface
import cl.avelyn.bd_control_app.ui.theme.OrangePrimary
import cl.avelyn.bd_control_app.ui.theme.TextMuted
import cl.avelyn.bd_control_app.ui.theme.TextWhite
import cl.avelyn.bd_control_app.viewmodel.AuthStep
import cl.avelyn.bd_control_app.viewmodel.AuthViewModel

/**
 * Pantalla de inicio de sesión refinada según las reglas de Refactoring UI (Item 3 UI/UX).
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onOpenSimulator: () -> Unit,
    deepLinkUri: String? = null,
    viewModel: AuthViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var usernameInput by remember { mutableStateOf(ui.username) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(ui.isLoggedIn) {
        if (ui.isLoggedIn) onLoginSuccess()
    }

    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            viewModel.handleDeepLink(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra Superior: Badge Versión + Configuración
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(
                text = "ESP32 Climate v2.4",
                textColor = OrangePrimary,
                backgroundColor = Color(0xFF2C1612),
                showDot = true,
                dotColor = OrangePrimary,
                modifier = Modifier.border(1.dp, Color(0xFF4A2218), RoundedCornerShape(50))
            )

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configuración",
                    tint = TextWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Sección Central: Icono Hero + Título + Formulario
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Control de Clima",
                    tint = OrangePrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Bodega — Control de ventilación",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ingresa con tu cuenta para ver y controlar tus dispositivos en tiempo real.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (ui.step == AuthStep.USERNAME_PASSWORD) {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = {
                        usernameInput = it
                        viewModel.onUsernameChanged(it)
                    },
                    label = { Text("Usuario", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        disabledContainerColor = DarkSurface,
                        focusedBorderColor = OrangePrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    enabled = !ui.isLoading
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Contraseña", color = TextMuted) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña",
                                tint = TextMuted
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        disabledContainerColor = DarkSurface,
                        focusedBorderColor = OrangePrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    enabled = !ui.isLoading
                )
            } else {
                Text(
                    text = stringResource(R.string.login_otp_sent_hint, ui.email),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text("Código de 6 dígitos", color = TextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = OrangePrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    enabled = !ui.isLoading
                )
            }

            ui.error?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Sección Inferior: Jerarquía de Botones (Primary vs Secondary)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (ui.step == AuthStep.USERNAME_PASSWORD) {
                // Botón Primario Destacado en Naranja
                Button(
                    onClick = { viewModel.loginWithPassword(usernameInput, passwordInput) },
                    enabled = !ui.isLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary)
                ) {
                    if (ui.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = TextWhite
                        )
                    } else {
                        Text(
                            text = "Iniciar Sesión",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextWhite
                        )
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.verifyOtp(codeInput) },
                    enabled = !ui.isLoading && codeInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary)
                ) {
                    if (ui.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = TextWhite
                        )
                    } else {
                        Text("Verificar Código", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            codeInput = ""
                            viewModel.resetToPasswordStep()
                        },
                        enabled = !ui.isLoading
                    ) {
                        Text("Volver", color = TextMuted)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = { viewModel.resendOtp() },
                        enabled = !ui.isLoading
                    ) {
                        Text("Reenviar Código", color = OrangePrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Botón Secundario de Pruebas (De-enfatizado con Outlined style)
            OutlinedButton(
                onClick = { viewModel.skipLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurface)
            ) {
                Text(
                    text = stringResource(R.string.login_test_mode_button),
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenSimulator() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = OrangePrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "¿Eres el dispositivo emisor? Panel de simulación",
                        color = OrangePrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowForward,
                        contentDescription = null,
                        tint = OrangePrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
