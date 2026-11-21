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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.theme.DarkBackground
import cl.avelyn.bd_control_app.ui.theme.DarkBorder
import cl.avelyn.bd_control_app.ui.theme.DarkSurface
import cl.avelyn.bd_control_app.ui.theme.OrangePrimary
import cl.avelyn.bd_control_app.ui.theme.StatusGreen
import cl.avelyn.bd_control_app.ui.theme.StatusRed
import cl.avelyn.bd_control_app.ui.theme.TextMuted
import cl.avelyn.bd_control_app.ui.theme.TextWhite
import cl.avelyn.bd_control_app.viewmodel.SimulatorViewModel

/**
 * Panel de Desarrollador / Emisor (Simulador) refinado con vectores Material.
 */
@Composable
fun SimulatorScreen(
    onBack: () -> Unit,
    viewModel: SimulatorViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
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

                    Text(
                        text = stringResource(R.string.simulator_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Text(stringResource(R.string.history_back), color = TextWhite, fontSize = 12.sp)
                }
            }

            // Card Simulación de Entradas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "${stringResource(R.string.simulator_temp_label)}: %.1f °C".format(ui.tempC),
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = ui.tempC,
                        onValueChange = viewModel::setTemp,
                        valueRange = -10f..50f,
                        colors = SliderDefaults.colors(
                            thumbColor = OrangePrimary,
                            activeTrackColor = OrangePrimary,
                            inactiveTrackColor = DarkBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "${stringResource(R.string.simulator_hum_label)}: %.1f %%".format(ui.humPct),
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = ui.humPct,
                        onValueChange = viewModel::setHum,
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = OrangePrimary,
                            activeTrackColor = OrangePrimary,
                            inactiveTrackColor = DarkBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.simulator_auto_label),
                            color = TextWhite,
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = ui.isAutoSending,
                            onCheckedChange = viewModel::setAutoSending,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextWhite,
                                checkedTrackColor = OrangePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkBorder
                            )
                        )
                    }

                    ui.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (!ui.isAutoSending) {
                        Button(
                            onClick = viewModel::sendNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.simulator_send_now), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Card Estado del Ventilador Recibido
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.simulator_fan_received_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (ui.fanOn) {
                                stringResource(R.string.simulator_fan_state_on)
                            } else {
                                stringResource(R.string.simulator_fan_state_off)
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (ui.fanOn) StatusGreen else StatusRed
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (ui.fanOn) StatusGreen else StatusRed,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Botón Abajo
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurface)
        ) {
            Text("Volver", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
