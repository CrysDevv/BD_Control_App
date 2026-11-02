package cl.avelyn.bd_control_app.model.repository

import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.network.UdpMsg
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/**
 * Guarda el historial de temperatura y humedad. No se guarda cada lectura (llegan
 * cada 2 s): se guarda cada [MIN_INTERVAL_MS] o antes si hay un cambio brusco,
 * para no llenar la base de datos con valores casi idénticos.
 */
class SensorRepository(private val dao: SensorLogDao) {

    private var lastSavedAt = 0L
    private var lastTempC = 0f
    private var lastHumPct = 0f

    /** Evalúa una lectura ya validada y la guarda si corresponde según la política de muestreo. */
    suspend fun record(msg: UdpMsg.Sensor) {
        val now = System.currentTimeMillis()
        val suddenChange = abs(msg.tempC - lastTempC) >= TEMP_JUMP_C || abs(msg.humPct - lastHumPct) >= HUM_JUMP_PCT
        if (now - lastSavedAt < MIN_INTERVAL_MS && !suddenChange) return

        dao.insert(SensorLog(timestamp = now, temperatureC = msg.tempC, humidityPct = msg.humPct))
        lastSavedAt = now
        lastTempC = msg.tempC
        lastHumPct = msg.humPct
    }

    fun latest(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<SensorLog>> = dao.latest(limit)

    /** Elimina registros más antiguos que [days] días, pensado para llamarse al iniciar la app. */
    suspend fun purgeOlderThan(days: Int = DEFAULT_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
        dao.purgeBefore(cutoff)
    }

    companion object {
        private const val MIN_INTERVAL_MS = 10_000L
        private const val TEMP_JUMP_C = 1f
        private const val HUM_JUMP_PCT = 3f
        private const val DEFAULT_HISTORY_LIMIT = 200
        private const val DEFAULT_RETENTION_DAYS = 7
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
