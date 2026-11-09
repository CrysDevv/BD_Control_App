package cl.avelyn.bd_control_app.model.energy

/** Una lectura de humedad con su hora, para ver cómo evoluciona. */
data class HumidityPoint(val timeMs: Long, val humPct: Float)

/**
 * Elige cada cuánto conviene que mida el sensor, según qué tan rápido cambia la humedad, para gastar menos batería
 * sin perder reacción. Cada lectura cuesta: una trama por el cable, una escritura en la base de datos, y después una
 * fila que subir. Con el aire quieto no aporta nada medir cada 2 s.
 *
 * - **Activa** (la humedad se mueve ≥ [activeRangePct] puntos en la ventana, o está cerca de los umbrales del
 *   automático): [activeIntervalMs].
 * - **Calma** (se movió menos de [calmRangePct] puntos y hay datos de al menos [minHistoryMs]): [calmIntervalMs].
 * - **Intermedia**: [normalIntervalMs].
 * Para no cambiar de intervalo a cada rato, un cambio solo se recomienda si pasó [minChangeGapMs] desde el último.
 */
data class AdaptiveConfig(
    val activeIntervalMs: Int = 10_000,
    val normalIntervalMs: Int = 30_000,
    val calmIntervalMs: Int = 60_000,
    val windowMs: Long = 5 * 60_000,
    val minHistoryMs: Long = 4 * 60_000,
    val activeRangePct: Float = 3f,
    val calmRangePct: Float = 1f,
    /** Distancia a los umbrales del automático por debajo de la cual se mide rápido, por si está a punto de actuar. */
    val nearThresholdPct: Float = 5f,
    val minChangeGapMs: Long = 2 * 60_000
)

class AdaptiveSampler(
    private val config: AdaptiveConfig = AdaptiveConfig(),
    private val onThresholdPct: Float = 70f,
    private val offThresholdPct: Float = 60f
) {
    private val lastChangeAt = mutableMapOf<String, Long>()

    /** Intervalo según la evolución reciente ([history], de cualquier orden), sin considerar el límite de cambios. */
    fun target(history: List<HumidityPoint>, nowMs: Long): Int {
        val window = history.filter { nowMs - it.timeMs <= config.windowMs }
        if (window.isEmpty()) return config.normalIntervalMs
        val latest = window.maxByOrNull { it.timeMs }!!.humPct
        val range = window.maxOf { it.humPct } - window.minOf { it.humPct }
        val span = window.maxOf { it.timeMs } - window.minOf { it.timeMs }

        // Cerca de un umbral del automático: puede estar a punto de actuar, conviene mirar seguido.
        val nearOn = latest >= onThresholdPct - config.nearThresholdPct
        val nearOff = latest in (offThresholdPct - config.nearThresholdPct)..(offThresholdPct + config.nearThresholdPct)
        val nearAction = nearOn || nearOff

        return when {
            range >= config.activeRangePct || nearAction -> config.activeIntervalMs
            span >= config.minHistoryMs && range < config.calmRangePct -> config.calmIntervalMs
            else -> config.normalIntervalMs
        }
    }

    /**
     * Intervalo a pedirle al sensor [deviceId], o null si no hay que cambiar nada (ya es el recomendado, o se cambió
     * hace poco). Llamar [applied] cuando el cambio se haya hecho bien.
     */
    fun recommend(deviceId: String, history: List<HumidityPoint>, currentMs: Int?, nowMs: Long): Int? {
        val target = target(history, nowMs)
        if (target == currentMs) return null
        val last = lastChangeAt[deviceId]
        if (last != null && nowMs - last < config.minChangeGapMs) return null
        return target
    }

    fun applied(deviceId: String, nowMs: Long) {
        lastChangeAt[deviceId] = nowMs
    }
}
