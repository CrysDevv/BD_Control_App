package cl.avelyn.bd_control_app.model.sync

import cl.avelyn.bd_control_app.model.entity.SensorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el sincronizador necesita de la cola local (la base de datos del teléfono). */
interface SyncStore {
    suspend fun unsynced(limit: Int): List<SensorLog>
    suspend fun markSynced(ids: List<Long>)
    fun unsyncedCount(): Flow<Int>
}

/** Almacenamiento externo (Supabase). */
interface ReadingsSink {
    /** Sube las lecturas. Debe ser idempotente: subir dos veces la misma lectura no puede duplicarla. */
    suspend fun upload(rows: List<SensorLog>): Result<Unit>
}

data class SyncStatus(
    /** Lecturas guardadas en el teléfono que aún no se han subido. */
    val pending: Int = 0,
    val isOnline: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncMs: Long? = null,
    val lastError: String? = null,
    val uploadedTotal: Long = 0
)

data class SyncConfig(
    val batchSize: Int = 100,
    val initialBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 300_000
)

/**
 * Sincroniza la cola local con el almacenamiento externo cuando hay internet.
 *
 * - **Sin internet** no hace nada: las lecturas siguen acumulándose en el teléfono (la cola es la base de datos,
 *   que sobrevive aunque se cierre la app) y el estado dice cuántas esperan.
 * - **Con internet** sube por lotes, de la más antigua a la más nueva, y marca como subidas solo las que el servidor
 *   confirmó. Si la subida falla reintenta con espera creciente (2 s, 4 s, ... hasta 5 min). Si la app muere justo
 *   entre subir y marcar, la lectura se vuelve a subir y el servidor la ignora (idempotencia por clave única).
 * - Se activa sola cuando vuelve internet y cuando llegan lecturas nuevas.
 *
 * Llamar a [run] en un ámbito de larga vida (el de la aplicación); no termina hasta que se cancela.
 */
class SyncManager(
    private val store: SyncStore,
    private val sink: ReadingsSink,
    private val online: Flow<Boolean>,
    private val config: SyncConfig = SyncConfig(),
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun run() = coroutineScope {
        val kick = Channel<Unit>(Channel.CONFLATED)

        launch {
            online.collect { isOnline ->
                _status.update { it.copy(isOnline = isOnline) }
                if (isOnline) kick.trySend(Unit)
            }
        }
        launch {
            store.unsyncedCount().collect { count ->
                _status.update { it.copy(pending = count) }
                if (count > 0) kick.trySend(Unit)
            }
        }

        var backoffMs = config.initialBackoffMs
        while (true) {
            kick.receive()
            while (_status.value.isOnline) {
                // Cualquier fallo del ciclo (red, servidor o la propia base de datos local) se trata igual:
                // se muestra, se espera y se reintenta. Nunca debe terminar el sincronizador (vive en el ámbito de la app).
                val failure: String? = try {
                    val batch = store.unsynced(config.batchSize)
                    if (batch.isEmpty()) break

                    _status.update { it.copy(isSyncing = true) }
                    val result = sink.upload(batch)
                    if (result.isSuccess) {
                        store.markSynced(batch.map { it.id })
                        backoffMs = config.initialBackoffMs
                        _status.update {
                            it.copy(lastSyncMs = wallClockMs(), lastError = null, uploadedTotal = it.uploadedTotal + batch.size)
                        }
                        null
                    } else {
                        result.exceptionOrNull()?.message ?: "Error al subir las lecturas"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "Error al sincronizar"
                }

                if (failure != null) {
                    _status.update { it.copy(isSyncing = false, lastError = failure) }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(config.maxBackoffMs)
                }
            }
            _status.update { it.copy(isSyncing = false) }
        }
    }
}
