package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.BuildConfig
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.components.CircularGauge
import cl.avelyn.bd_control_app.ui.components.StatusBadge
import cl.avelyn.bd_control_app.ui.theme.DarkBackground
import cl.avelyn.bd_control_app.ui.theme.DarkBorder
import cl.avelyn.bd_control_app.ui.theme.DarkSurface
import cl.avelyn.bd_control_app.ui.theme.DarkSurfaceVariant
import cl.avelyn.bd_control_app.ui.theme.OrangeContainer
import cl.avelyn.bd_control_app.ui.theme.OrangePrimary
import cl.avelyn.bd_control_app.ui.theme.StatusAmber
import cl.avelyn.bd_control_app.ui.theme.StatusAmberBg
import cl.avelyn.bd_control_app.ui.theme.StatusCyan
import cl.avelyn.bd_control_app.ui.theme.StatusGreen
import cl.avelyn.bd_control_app.ui.theme.StatusGreenBg
import cl.avelyn.bd_control_app.ui.theme.StatusRed
import cl.avelyn.bd_control_app.ui.theme.StatusRedBg
import cl.avelyn.bd_control_app.ui.theme.TextMuted
import cl.avelyn.bd_control_app.ui.theme.TextWhite
import cl.avelyn.bd_control_app.viewmodel.BodegaStatusUiState
import cl.avelyn.bd_control_app.viewmodel.DashboardViewModel
import java.util.Locale

enum class DashboardViewMode {
    OVERVIEW,       // Screenshot 2: "Mis Bodegas" (Vista inicial)
    DEVICE_DETAIL   // Screenshot 3: "Control Bodega (ESP32)"
}

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = Factories)
) {
    val state by viewModel.bodegaState.collectAsStateWithLifecycle()
    var currentMode by remember { mutableStateOf(DashboardViewMode.OVERVIEW) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Banner de Error
        state.userError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatusRedBg)
                    .border(1.dp, StatusRed, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = err, color = StatusRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearUserError) {
                        Text("OK", color = StatusRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        when (currentMode) {
            DashboardViewMode.OVERVIEW -> {
                OverviewScreenContent(
                    state = state,
                    onSelectDevice = { currentMode = DashboardViewMode.DEVICE_DETAIL },
                    onLogout = { viewModel.logout(); onLogout() }
                )
            }
            DashboardViewMode.DEVICE_DETAIL -> {
                DeviceDetailScreenContent(
                    state = state,
                    onBackToOverview = { currentMode = DashboardViewMode.OVERVIEW },
                    onOpenHistory = onOpenHistory,
                    viewModel = viewModel
                )
            }
        }
    }
}

/**
 * Vista de Muestreo General ("Mis Bodegas" - Screenshot 2)
 */
@Composable
private fun OverviewScreenContent(
    state: BodegaStatusUiState,
    onSelectDevice: () -> Unit,
    onLogout: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header "Mis Bodegas"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MUESTREO GENERAL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Mis Bodegas",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Settings Icon Button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuración",
                        tint = TextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Logout Icon Button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, CircleShape)
                        .clickable { onLogout() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Cerrar Sesión",
                        tint = TextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Tarjeta Resumen Principal "Bodega Principal N° 1"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.online) StatusGreen else StatusRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bodega Principal N° 1",
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            fontSize = 16.sp
                        )
                    }

                    StatusBadge(
                        text = if (state.online) "Óptimo" else "Offline",
                        textColor = if (state.online) StatusGreen else StatusRed,
                        backgroundColor = if (state.online) StatusGreenBg else StatusRedBg,
                        showDot = false
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Telemetría en la Tarjeta: Si está Offline, se atenúan los anillos para evitar contradicción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularGauge(
                        label = "TEMP",
                        value = state.tempC?.let { "%.1f °C".format(Locale.US, it) } ?: "-- °C",
                        progress = if (state.online) (state.tempC?.let { (it / 50.0).toFloat() } ?: 0.5f) else 0.05f,
                        accentColor = if (state.online) OrangePrimary else DarkBorder,
                        icon = Icons.Default.Thermostat,
                        modifier = Modifier.weight(1f)
                    )

                    CircularGauge(
                        label = "HUMEDAD",
                        value = state.humPct?.let { "%.1f %%".format(Locale.US, it) } ?: "-- %",
                        progress = if (state.online) (state.humPct?.let { (it / 100.0).toFloat() } ?: 0.5f) else 0.05f,
                        accentColor = if (state.online) StatusCyan else DarkBorder,
                        icon = Icons.Default.WaterDrop,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Pie de Tarjeta: Sin contradicciones visuales si el nodo está desconectado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sensor: DHT22 Telemetría", fontSize = 11.sp, color = TextMuted)
                    if (state.online) {
                        Text("Actualizado hace instantes", fontSize = 11.sp, color = StatusGreen, fontWeight = FontWeight.Medium)
                    } else {
                        Text(
                            text = state.lastSeenFormatted?.let { "Última conexión: $it" } ?: "Sin conexión",
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Sección Dispositivos ESP32 Vinculados
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Dispositivos ESP32 Vinculados",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(OrangePrimary)
                    .clickable { onSelectDevice() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Vincular",
                    color = TextWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Card de Dispositivo Vinculado ("Control Bodega (ESP32)")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                .clickable { onSelectDevice() }
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
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(OrangeContainer)
                            .border(1.dp, Color(0xFF4A2218), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "ESP32",
                            tint = OrangePrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (state.online) StatusGreen else StatusRed)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Control Bodega (ESP32)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                        Text(
                            text = "T: ${state.tempC?.let { "%.1f °C".format(Locale.US, it) } ?: "--"}  •  H: ${state.humPct?.let { "%.1f %%".format(Locale.US, it) } ?: "--"}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.online) "En línea" else "Offline",
                        fontSize = 12.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Vista Detallada de Control ("Control Bodega (ESP32)" - Screenshot 3)
 */
@Composable
private fun DeviceDetailScreenContent(
    state: BodegaStatusUiState,
    onBackToOverview: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: DashboardViewModel
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
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
                            .clickable { onBackToOverview() },
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
                            text = "Control Bodega (ESP32)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (state.online) StatusGreen else StatusRed)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.online) "En línea" else "Desconectado",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (BuildConfig.DEBUG) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurface)
                                .border(1.dp, Color(0xFF5A2A1A), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = OrangePrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Simulador",
                                color = OrangePrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

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
            }

            // Row Telemetría (Gauges con iconografía diferenciada: Termómetro vs Gota de Agua)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularGauge(
                    label = "TEMPERATURA",
                    value = state.tempC?.let { "%.1f °C".format(Locale.US, it) } ?: "-- °C",
                    subtext = if (state.online) "DHT22 Conectado" else "Sensor sin señal",
                    progress = if (state.online) (state.tempC?.let { (it / 50.0).toFloat() } ?: 0.5f) else 0.05f,
                    accentColor = if (state.online) OrangePrimary else DarkBorder,
                    icon = Icons.Default.Thermostat,
                    modifier = Modifier.weight(1f)
                )

                CircularGauge(
                    label = "HUMEDAD",
                    value = state.humPct?.let { "%.1f %%".format(Locale.US, it) } ?: "-- %",
                    subtext = if (state.online) "DHT22 Conectado" else "Sensor sin señal",
                    progress = if (state.online) (state.humPct?.let { (it / 100.0).toFloat() } ?: 0.5f) else 0.05f,
                    accentColor = if (state.online) StatusCyan else DarkBorder,
                    icon = Icons.Default.WaterDrop,
                    modifier = Modifier.weight(1f)
                )
            }

            // Card Estado del Ventilador
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
                                .background(DarkSurfaceVariant)
                                .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Air,
                                contentDescription = "Ventilador",
                                tint = OrangePrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "ESTADO DEL VENTILADOR",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.fanOn) "Encendido" else "Apagado",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                    }

                    StatusBadge(
                        text = if (state.isAuto) "Modo Auto" else "Modo Manual",
                        textColor = TextWhite,
                        backgroundColor = DarkSurfaceVariant,
                        showDot = false
                    )
                }
            }

            // Selector Modo de Operación
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        text = "MODO DE OPERACIÓN",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Automático
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (state.isAuto) OrangePrimary else Color.Transparent)
                                .clickable(enabled = state.online && !state.isBusy && state.canControl) {
                                    viewModel.setAuto()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Automático",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (state.isAuto) TextWhite else TextMuted
                            )
                        }

                        // Manual
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!state.isAuto) OrangePrimary else Color.Transparent)
                                .clickable(enabled = state.online && !state.isBusy && state.canControl) {
                                    viewModel.setManualMode()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Manual",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (!state.isAuto) TextWhite else TextMuted
                            )
                        }
                    }
                }
            }

            // Controles Manuales
            AnimatedVisibility(visible = !state.isAuto) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "CONTROL MANUAL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Button Encender
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(StatusGreenBg)
                                    .border(1.dp, StatusGreen, RoundedCornerShape(12.dp))
                                    .clickable(enabled = state.online && !state.isBusy && state.canControl) {
                                        viewModel.setFanManual(true)
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• ENCENDER", color = StatusGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            // Button Apagar
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(StatusRedBg)
                                    .border(1.dp, StatusRed, RoundedCornerShape(12.dp))
                                    .clickable(enabled = state.online && !state.isBusy && state.canControl) {
                                        viewModel.setFanManual(false)
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• APAGAR", color = StatusRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Umbrales de Humedad (Etiquetas amigables para el usuario final sin nombres de variables de código)
            AnimatedVisibility(visible = state.isAuto) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "UMBRALES DE HUMEDAD (% RH)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = state.humOnInput,
                                onValueChange = viewModel::updateHumOnInput,
                                label = { Text("Encender al superar (%)", color = TextMuted, fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = state.online && !state.isBusy && state.canControl,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkSurfaceVariant,
                                    unfocusedContainerColor = DarkSurfaceVariant,
                                    focusedBorderColor = OrangePrimary,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite
                                )
                            )

                            OutlinedTextField(
                                value = state.humOffInput,
                                onValueChange = viewModel::updateHumOffInput,
                                label = { Text("Apagar al bajar de (%)", color = TextMuted, fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = state.online && !state.isBusy && state.canControl,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkSurfaceVariant,
                                    unfocusedContainerColor = DarkSurfaceVariant,
                                    focusedBorderColor = OrangePrimary,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = viewModel::saveThresholds,
                            enabled = state.online && !state.isBusy && state.canControl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Guardar Umbrales", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Banner Dispositivo Fuera de Línea
            if (!state.online) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StatusAmberBg)
                        .border(1.dp, StatusAmber, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Controles deshabilitados: El dispositivo está fuera de línea.",
                            color = StatusAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón Fijado Abajo: "Ver historial" ocupa todo el ancho seguro evitando toque accidental con Cerrar sesión
        Button(
            onClick = onOpenHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ver historial de registros", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}
