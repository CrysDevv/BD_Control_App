package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.ui.components.StatusBadge
import cl.avelyn.bd_control_app.ui.theme.DarkBackground
import cl.avelyn.bd_control_app.ui.theme.DarkBorder
import cl.avelyn.bd_control_app.ui.theme.DarkSurface
import cl.avelyn.bd_control_app.ui.theme.OrangeContainer
import cl.avelyn.bd_control_app.ui.theme.OrangePrimary
import cl.avelyn.bd_control_app.ui.theme.StatusGreen
import cl.avelyn.bd_control_app.ui.theme.StatusGreenBg
import cl.avelyn.bd_control_app.ui.theme.StatusRed
import cl.avelyn.bd_control_app.ui.theme.StatusRedBg
import cl.avelyn.bd_control_app.ui.theme.TextMuted
import cl.avelyn.bd_control_app.ui.theme.TextWhite
import cl.avelyn.bd_control_app.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pantalla de Historial optimizada UI/UX (Item 6).
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Bar Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = "Volver",
                        tint = TextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "Historial",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "Monitoreo de temperatura y humedad",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            // Botón Limpiar: Visible/activo solo cuando existen registros
            if (ui.logs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(StatusRedBg)
                        .border(1.dp, Color(0xFF4A1B20), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Limpiar",
                        tint = StatusRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Limpiar",
                        color = StatusRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Selector si hay múltiples dispositivos
        if (ui.deviceIds.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.deviceIds) { id ->
                    FilterChip(
                        selected = id == ui.selectedId,
                        onClick = { viewModel.selectDevice(id) },
                        label = { Text(id, color = TextWhite) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Lista de Registros de Historial (Aprovecha el alto vertical disponible)
        if (ui.logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.history_empty),
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ui.logs) { log ->
                    HistoryLogCard(log = log, timeFormat = timeFormat)
                }
            }
        }
    }
}

@Composable
private fun HistoryLogCard(log: SensorLog, timeFormat: SimpleDateFormat) {
    val isElevated = log.temperatureC > 26.5f
    val timeStr = timeFormat.format(log.timestamp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(OrangeContainer)
                        .border(1.dp, Color(0xFF4A2218), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = "Temperatura",
                        tint = OrangePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "%.1f °C".format(Locale.US, log.temperatureC),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isElevated) "+0.6 °C" else "Sin variación",
                            fontSize = 11.sp,
                            color = if (isElevated) OrangePrimary else TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Humedad ref: %.1f %%  •  %s".format(Locale.US, log.humidityPct, timeStr),
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            StatusBadge(
                text = if (isElevated) "Elevada" else "Normal",
                textColor = if (isElevated) StatusRed else StatusGreen,
                backgroundColor = if (isElevated) StatusRedBg else StatusGreenBg,
                showDot = false
            )
        }
    }
}
