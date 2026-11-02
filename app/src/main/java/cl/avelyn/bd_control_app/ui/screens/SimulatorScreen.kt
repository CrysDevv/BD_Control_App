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
import androidx.compose.material3.Slider
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
import cl.avelyn.bd_control_app.viewmodel.SimulatorViewModel

/**
 * Panel de desarrollador: este es el "dispositivo que envía los datos". Se instala en un
 * segundo teléfono (o se abre en un segundo emulador) para simular, mientras no está el
 * hardware, lo que harían los dos ESP32: reportar temperatura/humedad y ejecutar el
 * comando del ventilador que llega desde la app de monitoreo. Solicita el permiso de
 * ubicación al entrar, que WifiGuard necesita para leer el SSID actual.
 */
@Composable
fun SimulatorScreen(
    onBack: () -> Unit,
    viewModel: SimulatorViewModel = viewModel(factory = Factories)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.simulator_title), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${stringResource(R.string.simulator_temp_label)}: %.1f °C".format(ui.tempC))
                Slider(
                    value = ui.tempC,
                    onValueChange = viewModel::setTemp,
                    valueRange = -10f..50f
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("${stringResource(R.string.simulator_hum_label)}: %.1f %%".format(ui.humPct))
                Slider(
                    value = ui.humPct,
                    onValueChange = viewModel::setHum,
                    valueRange = 0f..100f
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.simulator_auto_label))
                    Switch(checked = ui.isAutoSending, onCheckedChange = viewModel::setAutoSending)
                }

                ui.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (!ui.isAutoSending) {
                    Button(
                        onClick = viewModel::sendNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.simulator_send_now))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.simulator_fan_received_label), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (ui.fanOn) {
                        stringResource(R.string.simulator_fan_state_on)
                    } else {
                        stringResource(R.string.simulator_fan_state_off)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (ui.fanOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
