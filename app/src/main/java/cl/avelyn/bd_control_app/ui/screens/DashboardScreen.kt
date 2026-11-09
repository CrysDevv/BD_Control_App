package cl.avelyn.bd_control_app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.model.alerts.Alert
import cl.avelyn.bd_control_app.model.alerts.AlertSeverity
import cl.avelyn.bd_control_app.model.automation.AutomationStatus
import cl.avelyn.bd_control_app.model.energy.EnergyReport
import cl.avelyn.bd_control_app.model.network.DeviceType
import cl.avelyn.bd_control_app.model.network.LinkStats
import cl.avelyn.bd_control_app.model.network.SerialLinkState
import cl.avelyn.bd_control_app.model.sync.SyncStatus
import cl.avelyn.bd_control_app.ui.components.SensorCard
import cl.avelyn.bd_control_app.viewmodel.DashboardViewModel
import cl.avelyn.bd_control_app.viewmodel.DeviceUi

/**
 * Panel principal: una sección por cada dispositivo descubierto en la red. Cada sensor muestra
 * su temperatura y humedad en vivo; cada relé tiene su propio interruptor de ventilador.
 * Solicita el permiso de ubicación al entrar (lo necesita WifiGuard para leer el SSID
 * y exigir la misma red Wi-Fi en los dos dispositivos); si se rechaza, el interruptor
 * del ventilador seguirá mostrando el error de WifiGuard al intentar usarlo.
 */
@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val serialState by viewModel.serialState.collectAsStateWithLifecycle()
    val serialStats by viewModel.serialStats.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val energy by viewModel.energy.collectAsStateWithLifecycle()
    val powerSaving by viewModel.powerSaving.collectAsStateWithLifecycle()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* concedido o no, WifiGuard revisa igual al momento de escribir */ }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ui.alerts.isNotEmpty()) {
                item { AlertsCard(ui.alerts) }
            }
            item { EnergyCard(energy, powerSaving, onToggle = { viewModel.setPowerSaving(it) }) }
            item { SyncStatusCard(sync) }
            if (serialState !is SerialLinkState.Unavailable) {
                item { SerialStatusCard(serialState, serialStats) }
            }
            if (ui.devices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dashboard_searching_devices),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            items(ui.devices, key = { it.id }) { device ->
                when (device.type) {
                    DeviceType.SENSOR -> SensorSection(device, onInterval = { viewModel.setSampleInterval(device.id, it) })
                    DeviceType.RELAY -> RelaySection(device, onToggle = { viewModel.setFan(device.id, it) }, onAutomatic = { viewModel.setAutomatic(device.id, it) })
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
private fun SensorSection(device: DeviceUi, onInterval: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dashboard_sensor_title, device.id),
            style = MaterialTheme.typography.titleMedium
        )
        SensorCard(
            label = stringResource(R.string.dashboard_temperature_label),
            value = device.tempC?.let { "%.1f °C".format(it) } ?: stringResource(R.string.dashboard_no_data),
            isOnline = device.isOnline,
            onlineLabel = stringResource(R.string.dashboard_sensor_online),
            offlineLabel = stringResource(R.string.dashboard_sensor_offline)
        )
        SensorCard(
            label = stringResource(R.string.dashboard_humidity_label),
            value = device.humPct?.let { "%.1f %%".format(it) } ?: stringResource(R.string.dashboard_no_data),
            isOnline = device.isOnline,
            onlineLabel = stringResource(R.string.dashboard_sensor_online),
            offlineLabel = stringResource(R.string.dashboard_sensor_offline)
        )

        if (device.viaSerial) {
            Text(
                text = stringResource(R.string.dashboard_sample_interval_label),
                style = MaterialTheme.typography.labelLarge
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SAMPLE_INTERVALS_MS) { ms ->
                    FilterChip(
                        selected = device.sampleIntervalMs == ms,
                        onClick = { onInterval(ms) },
                        enabled = device.isOnline && device.canControl && !device.isBusy,
                        label = { Text(stringResource(R.string.dashboard_seconds_format, ms / 1000)) }
                    )
                }
            }
            if (!device.canControl) {
                Text(
                    text = stringResource(R.string.dashboard_sensor_read_only),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        device.error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RelaySection(device: DeviceUi, onToggle: (Boolean) -> Unit, onAutomatic: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dashboard_relay_title, device.id),
                    style = MaterialTheme.typography.titleLarge
                )
                Switch(
                    checked = device.fanOn ?: false,
                    onCheckedChange = onToggle,
                    enabled = device.isOnline && !device.isBusy && device.canControl
                )
            }

            Text(
                text = if (device.isOnline) {
                    stringResource(R.string.dashboard_relay_online)
                } else {
                    stringResource(R.string.dashboard_relay_offline)
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (device.failSafe) {
                Text(
                    text = stringResource(R.string.relay_failsafe),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (device.canControl) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.relay_automatic_label), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = device.automatic, onCheckedChange = onAutomatic, enabled = device.isOnline)
                }
                if (device.automatic) {
                    Text(
                        text = when (device.automationStatus) {
                            AutomationStatus.PAUSED_NO_DATA -> stringResource(R.string.relay_automatic_paused)
                            else -> stringResource(R.string.relay_automatic_active)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (device.automationStatus == AutomationStatus.PAUSED_NO_DATA) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!device.canControl) {
                Text(
                    text = stringResource(R.string.dashboard_relay_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            device.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** Intervalos de medición que ofrece la interfaz (el firmware acepta de 1 a 600 s). */
private val SAMPLE_INTERVALS_MS = listOf(2_000, 10_000, 30_000, 60_000)

/** Estado del cable USB y calidad del enlace serial con el sensor. */
@Composable
private fun SerialStatusCard(state: SerialLinkState, stats: LinkStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when (state) {
                    is SerialLinkState.NoCable -> stringResource(R.string.serial_no_cable)
                    is SerialLinkState.PermissionRequired -> stringResource(R.string.serial_permission, state.deviceName)
                    is SerialLinkState.Connecting -> stringResource(R.string.serial_connecting, state.deviceName)
                    is SerialLinkState.Connected -> stringResource(R.string.serial_connected, state.deviceId ?: state.deviceName)
                    is SerialLinkState.Failed -> stringResource(R.string.serial_failed, state.message)
                    is SerialLinkState.Unavailable -> ""
                },
                style = MaterialTheme.typography.titleSmall
            )
            if (state is SerialLinkState.Connected) {
                Text(
                    text = stringResource(
                        R.string.serial_stats,
                        stats.framesOk, stats.framesCorrupt, stats.framesLost, stats.reconnects
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (stats.sensorCapacity > 0) {
                    Text(
                        text = stringResource(
                            R.string.serial_buffer,
                            stats.recovered, stats.sensorBuffered, stats.sensorCapacity, stats.sensorDropped
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Estado de la sincronización con la nube: qué hay guardado en el teléfono esperando subir y si hay internet. */
@Composable
private fun SyncStatusCard(sync: SyncStatus) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sync_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = when {
                    sync.pending > 0 && !sync.isOnline -> stringResource(R.string.sync_offline_pending, sync.pending)
                    sync.pending > 0 && sync.isSyncing -> stringResource(R.string.sync_uploading, sync.pending)
                    sync.pending > 0 -> stringResource(R.string.sync_waiting, sync.pending)
                    !sync.isOnline -> stringResource(R.string.sync_offline_empty)
                    else -> stringResource(R.string.sync_all_done)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            sync.lastSyncMs?.let {
                Text(
                    text = stringResource(
                        R.string.sync_last, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            sync.lastError?.let {
                Text(
                    text = stringResource(R.string.sync_error, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Avisos al operador: lo que está fallando y qué hace el sistema al respecto (ver docs/plan_contingencia.md). */
@Composable
private fun AlertsCard(alerts: List<Alert>) {
    val critical = alerts.any { it.severity == AlertSeverity.CRITICAL }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleSmall)
            alerts.forEach {
                Text(
                    text = (if (it.severity == AlertSeverity.CRITICAL) "⛔ " else if (it.severity == AlertSeverity.WARNING) "⚠ " else "ℹ ") + it.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Batería del teléfono y modo ahorro. El sensor por cable se alimenta de esta misma batería. */
@Composable
private fun EnergyCard(energy: EnergyReport, powerSaving: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.energy_title), style = MaterialTheme.typography.titleSmall)
                Switch(checked = powerSaving, onCheckedChange = onToggle)
            }
            Text(
                text = stringResource(if (powerSaving) R.string.energy_saving_on else R.string.energy_saving_off),
                style = MaterialTheme.typography.bodySmall
            )
            energy.levelPct?.let { level ->
                Text(
                    text = if (energy.charging) stringResource(R.string.energy_charging, level)
                    else stringResource(R.string.energy_level, level),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            energy.dischargePctPerHour?.let { rate ->
                Text(
                    text = stringResource(R.string.energy_rate, rate, energy.hoursRemaining ?: 0f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            energy.avgCurrentMa?.let {
                Text(text = stringResource(R.string.energy_current, it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
