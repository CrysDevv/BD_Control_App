package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.components.SensorCard
import cl.avelyn.bd_control_app.viewmodel.DashboardViewModel
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = Factories)
) {
    val state by viewModel.bodegaState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mensaje de error si la última operación falló
            state.userError?.let { err ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::clearUserError) {
                                Text("OK", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // 1. Cabecera/indicador de estado en línea/desconectado
            item {
                HeaderOnlineCard(
                    online = state.online,
                    lastSeenFormatted = state.lastSeenFormatted
                )
            }

            // 2. Tarjetas de lectura: Temperatura y Humedad
            item {
                TelemetryCards(
                    tempC = state.tempC,
                    humPct = state.humPct,
                    online = state.online
                )
            }

            // 3. Estado real del ventilador (fan_state) + "Aplicando..."
            item {
                FanStateCard(
                    fanOn = state.fanOn,
                    pending = state.pending,
                    pendingTimeout = state.pendingTimeout
                )
            }

            // 4. Selector de modo: Automático / Manual
            item {
                ModeSelectorCard(
                    isAuto = state.isAuto,
                    enabled = state.online && !state.isBusy && state.canControl,
                    onSelectAuto = { viewModel.setAuto() },
                    onSelectManual = { viewModel.setManualMode() }
                )
            }

            // 5. Botones ENCENDER / APAGAR
            item {
                ManualControlsCard(
                    enabled = state.online && !state.isBusy && state.canControl,
                    onTurnOn = { viewModel.setFanManual(true) },
                    onTurnOff = { viewModel.setFanManual(false) }
                )
            }

            // 6. Umbrales (hum_on, hum_off) - visibles solo en modo automático
            if (state.isAuto) {
                item {
                    ThresholdsCard(
                        humOnInput = state.humOnInput,
                        humOffInput = state.humOffInput,
                        enabled = state.online && !state.isBusy && state.canControl,
                        onHumOnChanged = viewModel::updateHumOnInput,
                        onHumOffChanged = viewModel::updateHumOffInput,
                        onSave = viewModel::saveThresholds
                    )
                }
            }

            // 7. Explicación si está fuera de línea o sin permisos
            if (!state.online) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Controles deshabilitados: El dispositivo está fuera de línea.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else if (!state.canControl) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Controles deshabilitados: No tienes permisos para controlar este dispositivo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { viewModel.logout(); onLogout() }) {
                Text(stringResource(R.string.dashboard_logout_button))
            }
            Button(onClick = onOpenHistory) {
                Text(stringResource(R.string.dashboard_history_button))
            }
        }
    }
}

@Composable
private fun HeaderOnlineCard(online: Boolean, lastSeenFormatted: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Control Bodega (ESP32)", style = MaterialTheme.typography.titleMedium)
                if (!online && lastSeenFormatted != null) {
                    Text(
                        "Última conexión: $lastSeenFormatted",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = if (online) "En línea" else "Desconectado",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryCards(tempC: Double?, humPct: Double?, online: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SensorCard(
                label = stringResource(R.string.dashboard_temperature_label),
                value = tempC?.let { "%.1f °C".format(Locale.US, it) } ?: "-- °C",
                isOnline = online,
                onlineLabel = stringResource(R.string.dashboard_sensor_online),
                offlineLabel = stringResource(R.string.dashboard_sensor_offline)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            SensorCard(
                label = stringResource(R.string.dashboard_humidity_label),
                value = humPct?.let { "%.1f %%".format(Locale.US, it) } ?: "-- %",
                isOnline = online,
                onlineLabel = stringResource(R.string.dashboard_sensor_online),
                offlineLabel = stringResource(R.string.dashboard_sensor_offline)
            )
        }
    }
}

@Composable
private fun FanStateCard(fanOn: Boolean, pending: Boolean, pendingTimeout: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (fanOn) "🌀" else "⏸",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Column {
                        Text("Estado del Ventilador", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = if (fanOn) "Encendido" else "Apagado",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (fanOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (pending) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!pendingTimeout) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            text = if (pendingTimeout) "El dispositivo no responde" else "Aplicando...",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pendingTimeout) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelectorCard(
    isAuto: Boolean,
    enabled: Boolean,
    onSelectAuto: () -> Unit,
    onSelectManual: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Modo de Operación", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = isAuto,
                    onClick = onSelectAuto,
                    enabled = enabled,
                    label = { Text("Automático") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !isAuto,
                    onClick = onSelectManual,
                    enabled = enabled,
                    label = { Text("Manual") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ManualControlsCard(
    enabled: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Control Manual", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onTurnOn,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("ENCENDER")
                }
                Button(
                    onClick = onTurnOff,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("APAGAR")
                }
            }
        }
    }
}

@Composable
private fun ThresholdsCard(
    humOnInput: String,
    humOffInput: String,
    enabled: Boolean,
    onHumOnChanged: (String) -> Unit,
    onHumOffChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Umbrales de Humedad (% RH)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = humOnInput,
                    onValueChange = onHumOnChanged,
                    label = { Text("Encender (hum_on)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = humOffInput,
                    onValueChange = onHumOffChanged,
                    label = { Text("Apagar (hum_off)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar Umbrales")
            }
        }
    }
}
