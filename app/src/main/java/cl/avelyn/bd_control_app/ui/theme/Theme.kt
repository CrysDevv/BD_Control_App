package cl.avelyn.bd_control_app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val BdColorScheme = lightColorScheme(
    primary = BodegaGreen,
    onPrimary = BodegaSurface,
    primaryContainer = BodegaGreenDark,
    secondary = BodegaAmber,
    error = BodegaRed,
    background = BodegaBackground,
    surface = BodegaSurface,
    onSurface = BodegaOnSurface
)

/** Tema único de la app: sin modo oscuro por ahora, para mantener el alcance acotado. */
@Composable
fun BdControlAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BdColorScheme,
        typography = BdTypography,
        content = content
    )
}
