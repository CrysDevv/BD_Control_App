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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.components.SensorCard
import cl.avelyn.bd_control_app.viewmodel.DashboardViewModel

/**
 * Panel principal: temperatura y humedad en vivo, y control manual del ventilador.
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
        SensorCard(
            label = stringResource(R.string.dashboard_temperature_label),
            value = ui.tempC?.let { "%.1f °C".format(it) } ?: stringResource(R.string.dashboard_no_data),
            isOnline = ui.isSensorOnline,
            onlineLabel = stringResource(R.string.dashboard_sensor_online),
            offlineLabel = stringResource(R.string.dashboard_sensor_offline)
        )

        Spacer(modifier = Modifier.height(12.dp))

        SensorCard(
            label = stringResource(R.string.dashboard_humidity_label),
            value = ui.humPct?.let { "%.1f %%".format(it) } ?: stringResource(R.string.dashboard_no_data),
            isOnline = ui.isSensorOnline,
            onlineLabel = stringResource(R.string.dashboard_sensor_online),
            offlineLabel = stringResource(R.string.dashboard_sensor_offline)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dashboard_fan_label), style = MaterialTheme.typography.titleLarge)
                    Switch(
                        checked = ui.fanOn ?: false,
                        onCheckedChange = { viewModel.setFan(it) },
                        enabled = ui.isRelayOnline && !ui.isBusy
                    )
                }

                Text(
                    text = if (ui.isRelayOnline) {
                        stringResource(R.string.dashboard_relay_online)
                    } else {
                        stringResource(R.string.dashboard_relay_offline)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )

                ui.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onLogout) {
                Text(stringResource(R.string.dashboard_logout_button))
            }
            Button(onClick = onOpenHistory) {
                Text(stringResource(R.string.dashboard_history_button))
            }
        }
    }
}
