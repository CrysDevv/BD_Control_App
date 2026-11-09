package cl.avelyn.bd_control_app.model.energy

/** Una lectura del estado de la batería del teléfono. [currentMa] depende del fabricante y puede faltar. */
data class BatterySample(
    val timeMs: Long,
    val levelPct: Int,
    val charging: Boolean,
    val currentMa: Int? = null,
    val temperatureC: Float? = null
)

data class EnergyReport(
    val levelPct: Int? = null,
    val charging: Boolean = false,
    /** Descarga estimada en %/hora, o null si aún no hay datos suficientes o está cargando. */
    val dischargePctPerHour: Float? = null,
    /** Horas que durarían el resto de la batería a ese ritmo. */
    val hoursRemaining: Float? = null,
    /** Corriente media de descarga en mA (indicativa: depende de cómo la informe el teléfono). */
    val avgCurrentMa: Int? = null,
    val temperatureC: Float? = null
)

/**
 * Estima cuánto se descarga el teléfono mientras la app está en uso, a partir de muestras de la batería.
 * Usa el último tramo continuo SIN cargar y una recta de mínimos cuadrados del nivel contra el tiempo (el nivel solo
 * cambia de a 1 %, así que los extremos solos serían muy ruidosos). Necesita al menos [minSpanMs] y [minPoints] puntos.
 */
class DischargeEstimator(
    private val minSpanMs: Long = 10 * 60_000,
    private val minPoints: Int = 3
) {
    fun report(samples: List<BatterySample>): EnergyReport {
        val last = samples.lastOrNull() ?: return EnergyReport()
        val base = EnergyReport(levelPct = last.levelPct, charging = last.charging, temperatureC = last.temperatureC)
        if (last.charging) return base

        // último tramo continuo descargando
        val segment = samples.takeLastWhile { !it.charging }
        if (segment.size < minPoints) return base
        val spanMs = segment.last().timeMs - segment.first().timeMs
        if (spanMs < minSpanMs) return base

        val hours = { s: BatterySample -> (s.timeMs - segment.first().timeMs) / 3_600_000.0 }
        val n = segment.size
        val meanX = segment.sumOf(hours) / n
        val meanY = segment.sumOf { it.levelPct.toDouble() } / n
        val sxx = segment.sumOf { (hours(it) - meanX) * (hours(it) - meanX) }
        if (sxx == 0.0) return base
        val slope = segment.sumOf { (hours(it) - meanX) * (it.levelPct - meanY) } / sxx   // % por hora (negativa al descargar)
        val rate = (-slope).toFloat()

        val currents = segment.mapNotNull { it.currentMa }.map { kotlin.math.abs(it) }.filter { it > 0 }
        return base.copy(
            dischargePctPerHour = if (rate > 0f) rate else null,
            hoursRemaining = if (rate > 0.05f) last.levelPct / rate else null,
            avgCurrentMa = if (currents.isEmpty()) null else currents.average().toInt()
        )
    }
}
