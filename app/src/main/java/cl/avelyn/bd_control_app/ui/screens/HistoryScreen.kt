package cl.avelyn.bd_control_app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.avelyn.bd_control_app.R
import cl.avelyn.bd_control_app.di.Factories
import cl.avelyn.bd_control_app.ui.components.LineChart
import cl.avelyn.bd_control_app.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/** Historial de temperatura y humedad: gráfico de la temperatura y lista de registros. */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = Factories)
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
        }

        // Con un solo sensor no hace falta elegir; con varios se muestra un chip por sensor.
        if (ui.deviceIds.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.deviceIds) { id ->
                    FilterChip(
                        selected = id == ui.selectedId,
                        onClick = { viewModel.selectDevice(id) },
                        label = { Text(id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (ui.logs.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LineChart(
                values = ui.logs.asReversed().map { it.temperatureC },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(ui.logs) { log ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dateFormat.format(log.timestamp))
                        Text("%.1f °C".format(log.temperatureC))
                        Text("%.1f %%".format(log.humidityPct))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
