package cl.avelyn.bd_control_app.model.energy

import cl.avelyn.bd_control_app.model.AppVisibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/** Lee el estado de la batería del teléfono (en Android: [AndroidBatteryReader]). */
fun interface BatteryReader {
    fun read(nowMs: Long): BatterySample
}

/**
 * Mide cuánta batería consume el teléfono mientras la app está a la vista: toma una muestra por minuto y estima el
 * ritmo de descarga ([DischargeEstimator]). Solo muestrea en primer plano (medir también cuesta energía).
 * El sensor USB cuenta en ese gasto: la placa se alimenta de esta misma batería (ver docs/energia.md).
 */
class EnergyMonitor(
    private val reader: BatteryReader,
    private val visibility: AppVisibility,
    private val estimator: DischargeEstimator = DischargeEstimator(),
    private val everyMs: Long = 60_000,
    private val keepMs: Long = 6 * 3_600_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val samples = ArrayDeque<BatterySample>()
    private val _report = MutableStateFlow(EnergyReport())
    val report: StateFlow<EnergyReport> = _report.asStateFlow()

    /** Toma una muestra ahora y actualiza el reporte. */
    fun sampleNow() {
        val now = clockMs()
        val s = reader.read(now)
        synchronized(samples) {
            samples.addLast(s)
            while (samples.isNotEmpty() && now - samples.first().timeMs > keepMs) samples.removeFirst()
            _report.value = estimator.report(samples.toList())
        }
    }

    suspend fun run() {
        visibility.isForeground.collectLatest { foreground ->
            if (!foreground) return@collectLatest
            while (true) {
                sampleNow()
                delay(everyMs)
            }
        }
    }
}
