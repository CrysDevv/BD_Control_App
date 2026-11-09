package cl.avelyn.bd_control_app.model.alerts

import cl.avelyn.bd_control_app.model.automation.AutomationStatus
import cl.avelyn.bd_control_app.model.energy.EnergyReport

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class Alert(val id: String, val severity: AlertSeverity, val message: String)

/** Lo que hace falta mirar para decidir qué avisarle al operador. */
data class AlertInputs(
    val sensors: List<SensorAlertInfo> = emptyList(),
    val relays: List<RelayAlertInfo> = emptyList(),
    val usbExpected: Boolean = false,
    val usbConnected: Boolean = false,
    val usbPermissionPending: Boolean = false,
    val sensorBufferDropped: Long = 0,
    val syncPending: Int = 0,
    val syncOnline: Boolean = true,
    val syncError: String? = null,
    val energy: EnergyReport = EnergyReport()
)

data class SensorAlertInfo(val id: String, val isOnline: Boolean)

data class RelayAlertInfo(
    val id: String,
    val isOnline: Boolean,
    val failSafe: Boolean,
    val automation: AutomationStatus,
    /** Minutos que faltan para que el relé entre en modo seguro si la app sigue sin latidos. */
    val minutesToFailSafe: Int = 10
)

/**
 * Convierte el estado del sistema en avisos para el operador (parte visible del plan de contingencia: ver
 * docs/plan_contingencia.md). Los más graves primero. Función pura.
 */
fun buildAlerts(i: AlertInputs): List<Alert> {
    val out = mutableListOf<Alert>()

    for (r in i.relays) {
        when {
            r.failSafe -> out += Alert(
                "failsafe-${r.id}", AlertSeverity.CRITICAL,
                "El relé ${r.id} está en MODO SEGURO: dejó de oír a la app y ventila por ciclos por su cuenta. Revisa los sensores y el enlace."
            )
            r.automation == AutomationStatus.PAUSED_NO_DATA -> out += Alert(
                "paused-${r.id}", AlertSeverity.CRITICAL,
                "Automático de ${r.id} en pausa: no hay datos recientes de ningún sensor. En ~${r.minutesToFailSafe} min el relé pasará solo a modo seguro."
            )
            !r.isOnline -> out += Alert("relay-off-${r.id}", AlertSeverity.WARNING, "El relé ${r.id} no responde (sin conexión).")
        }
    }
    for (s in i.sensors) {
        if (!s.isOnline) out += Alert("sensor-off-${s.id}", AlertSeverity.WARNING, "El sensor ${s.id} no envía datos.")
    }
    if (i.usbExpected && i.usbPermissionPending) {
        out += Alert("usb-permission", AlertSeverity.WARNING, "Falta aceptar el permiso USB para leer el sensor por cable.")
    } else if (i.usbExpected && !i.usbConnected) {
        out += Alert("usb-cable", AlertSeverity.WARNING, "Cable del sensor desconectado. El sensor guarda lecturas mientras tenga energía y las recupera al volver.")
    }
    if (i.sensorBufferDropped > 0) {
        out += Alert("buffer-dropped", AlertSeverity.WARNING, "El sensor perdió ${i.sensorBufferDropped} lecturas por llenar su buffer mientras la app no escuchaba.")
    }
    if (i.syncError != null) {
        out += Alert("sync-error", AlertSeverity.WARNING, "No se pudieron subir las lecturas a la nube: ${i.syncError}")
    } else if (!i.syncOnline && i.syncPending >= SYNC_BACKLOG_NOTICE) {
        out += Alert("sync-backlog", AlertSeverity.INFO, "Sin internet: ${i.syncPending} lecturas esperan en el teléfono. Se subirán solas.")
    }
    val level = i.energy.levelPct
    if (level != null && !i.energy.charging) {
        if (level <= BATTERY_CRITICAL_PCT) out += Alert("battery", AlertSeverity.CRITICAL, "Batería del teléfono al $level %: conéctalo a un cargador o el monitoreo se detendrá.")
        else if (level <= BATTERY_LOW_PCT) out += Alert("battery", AlertSeverity.WARNING, "Batería del teléfono al $level %. Conviene conectarlo a un cargador.")
    }
    return out.sortedByDescending { it.severity.ordinal }
}

const val SYNC_BACKLOG_NOTICE = 500
const val BATTERY_LOW_PCT = 20
const val BATTERY_CRITICAL_PCT = 10
