package cl.avelyn.bd_control_app.model.repository

import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import cl.avelyn.bd_control_app.model.network.UdpMsg
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/**
 * Guarda en el teléfono el historial de temperatura y humedad de todos los sensores. Esa tabla es
 * también la cola que [cl.avelyn.bd_control_app.model.sync.SyncManager] sube a Supabase.
 *
 * - **Lecturas en vivo:** llegan cada pocos segundos por sensor, así que no se guarda cada una: para cada
 *   sensor se guarda cada [MIN_INTERVAL_MS] o antes si hay un cambio brusco.
 * - **Lecturas recuperadas del buffer del sensor** (mientras la app estaba cerrada) se guardan todas: son el
 *   único registro de ese periodo, y llevan la hora real en que se midieron.
 * - **Sin duplicados:** cada lectura tiene una clave única; si llega dos veces se guarda una sola.
 *
 * El estado de muestreo es independiente por sensor. Pensado para llamarse desde una sola corrutina
 * (la del ViewModel), por eso el mapa no es concurrente.
 */
class SensorRepository(private val dao: SensorLogDao) {

    private class Sampling(var savedAt: Long = 0L, var tempC: Float = 0f, var humPct: Float = 0f)

    private val samplingByDevice = mutableMapOf<String, Sampling>()
    private var insertsSinceCapacityCheck = 0

    /**
     * Guarda la lectura si corresponde. Si lanza excepción NO se le debe confirmar al sensor que se guardó
     * (así la conserva y la reenvía); si retorna normalmente, la lectura quedó guardada o se descartó a propósito.
     */
    suspend fun record(msg: UdpMsg.Sensor) {
        val now = System.currentTimeMillis()
        val recovered = msg.capturedAtMs != null
        val timestamp = msg.capturedAtMs ?: now

        if (!recovered) {
            val last = samplingByDevice.getOrPut(msg.deviceId) { Sampling() }
            val suddenChange = abs(msg.tempC - last.tempC) >= TEMP_JUMP_C || abs(msg.humPct - last.humPct) >= HUM_JUMP_PCT
            if (now - last.savedAt < MIN_INTERVAL_MS && !suddenChange) return
            last.savedAt = now
            last.tempC = msg.tempC
            last.humPct = msg.humPct
        }

        dao.insert(
            SensorLog(
                timestamp = timestamp,
                temperatureC = msg.tempC,
                humidityPct = msg.humPct,
                deviceId = msg.deviceId,
                // sensores por cable: la identidad que fija el propio sensor; Wi-Fi: la hora (una por sensor y por milisegundo)
                sampleKey = msg.sample?.key ?: "w-$timestamp"
            )
        )
        if (++insertsSinceCapacityCheck >= CAPACITY_CHECK_EVERY) {
            insertsSinceCapacityCheck = 0
            enforceCapacity()
        }
    }

    fun latest(deviceId: String, limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<SensorLog>> = dao.latestFor(deviceId, limit)

    /** Sensores con historial guardado. */
    fun deviceIds(): Flow<List<String>> = dao.deviceIds()

    /**
     * Elimina las lecturas **ya subidas** con más de [days] días. Las que aún no se subieron se conservan
     * aunque sean más antiguas: son datos que todavía no existen en ningún otro lado.
     */
    suspend fun purgeOlderThan(days: Int = DEFAULT_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
        dao.purgeSyncedBefore(cutoff)
        enforceCapacity()
    }

    /**
     * Capacidad de la cola: si hay más de [maxUnsynced] lecturas sin subir (semanas sin internet) se descartan las más
     * antiguas para que la base de datos no crezca sin límite. Devuelve cuántas se descartaron.
     */
    suspend fun enforceCapacity(maxUnsynced: Int = MAX_UNSYNCED_ROWS): Int {
        val excess = dao.unsyncedCountNow() - maxUnsynced
        if (excess <= 0) return 0
        dao.dropOldestUnsynced(excess)
        return excess
    }

    companion object {
        private const val MIN_INTERVAL_MS = 10_000L
        private const val TEMP_JUMP_C = 1f
        private const val HUM_JUMP_PCT = 3f
        private const val DEFAULT_HISTORY_LIMIT = 200
        private const val DEFAULT_RETENTION_DAYS = 7
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val CAPACITY_CHECK_EVERY = 200

        /**
         * Tope de lecturas sin subir. Cada fila ocupa unos 70–100 bytes con sus índices (7–10 MB en total, estimado). Con una lectura cada 10 s son
         * 8 640 por día y por sensor: unos 11 días sin internet con un sensor, o ~5 con dos.
         */
        const val MAX_UNSYNCED_ROWS = 100_000
    }
}
