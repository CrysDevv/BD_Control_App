package cl.avelyn.bd_control_app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cl.avelyn.bd_control_app.ui.theme.OrangePrimary

/**
 * Tarjeta de sensor adaptada al diseño oscuro con medidor circular.
 */
@Composable
fun SensorCard(
    label: String,
    value: String,
    isOnline: Boolean,
    onlineLabel: String,
    offlineLabel: String,
    modifier: Modifier = Modifier,
    accentColor: Color = OrangePrimary,
    centerSymbol: String = "⚡",
    progress: Float = 0.65f
) {
    CircularGauge(
        label = label,
        value = value,
        subtext = if (isOnline) onlineLabel else offlineLabel,
        progress = if (isOnline) progress else 0.05f,
        accentColor = if (isOnline) accentColor else Color.Gray,
        centerSymbol = centerSymbol,
        modifier = modifier
    )
}
