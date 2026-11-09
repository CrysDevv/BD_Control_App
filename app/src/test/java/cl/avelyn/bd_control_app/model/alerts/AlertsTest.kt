package cl.avelyn.bd_control_app.model.alerts

import cl.avelyn.bd_control_app.model.automation.AutomationStatus
import cl.avelyn.bd_control_app.model.energy.EnergyReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private fun ids(i: AlertInputs) = buildAlerts(i).map { it.id }

    @Test
    fun `un sistema sano no avisa nada`() {
        val ok = AlertInputs(
            sensors = listOf(SensorAlertInfo("s1", true)),
            relays = listOf(RelayAlertInfo("r1", true, false, AutomationStatus.ACTIVE)),
            usbExpected = true, usbConnected = true,
            energy = EnergyReport(levelPct = 80, charging = false)
        )
        assertTrue(buildAlerts(ok).isEmpty())
    }

    @Test
    fun `rele en modo seguro es critico`() {
        val a = buildAlerts(AlertInputs(relays = listOf(RelayAlertInfo("r1", true, true, AutomationStatus.PAUSED_NO_DATA))))
        assertEquals(listOf("failsafe-r1"), a.map { it.id })      // no se duplica con el aviso de pausa
        assertEquals(AlertSeverity.CRITICAL, a.single().severity)
    }

    @Test
    fun `automatico en pausa avisa cuanto falta para el modo seguro`() {
        val a = buildAlerts(AlertInputs(relays = listOf(RelayAlertInfo("r1", true, false, AutomationStatus.PAUSED_NO_DATA, minutesToFailSafe = 7))))
        assertEquals(AlertSeverity.CRITICAL, a.single().severity)
        assertTrue(a.single().message.contains("7 min"))
    }

    @Test
    fun `sensor caido rele caido y cable desconectado`() {
        val a = ids(AlertInputs(
            sensors = listOf(SensorAlertInfo("s1", false)),
            relays = listOf(RelayAlertInfo("r1", false, false, AutomationStatus.OFF)),
            usbExpected = true, usbConnected = false
        ))
        assertEquals(setOf("sensor-off-s1", "relay-off-r1", "usb-cable"), a.toSet())
    }

    @Test
    fun `permiso USB pendiente reemplaza al aviso de cable`() {
        assertEquals(listOf("usb-permission"), ids(AlertInputs(usbExpected = true, usbPermissionPending = true)))
        assertTrue(ids(AlertInputs(usbExpected = false, usbConnected = false)).isEmpty())   // sin sensor por cable no molesta
    }

    @Test
    fun `descartes por desborde y problemas de subida`() {
        assertEquals(listOf("buffer-dropped"), ids(AlertInputs(sensorBufferDropped = 12)))
        assertEquals(listOf("sync-error"), ids(AlertInputs(syncError = "timeout", syncPending = 10_000, syncOnline = false)))
        assertEquals(listOf("sync-backlog"), ids(AlertInputs(syncOnline = false, syncPending = 600)))
        assertTrue(ids(AlertInputs(syncOnline = false, syncPending = 20)).isEmpty())      // poca cola sin internet: normal
    }

    @Test
    fun `bateria baja solo avisa si no esta cargando`() {
        assertEquals(AlertSeverity.WARNING, buildAlerts(AlertInputs(energy = EnergyReport(levelPct = 18, charging = false))).single().severity)
        assertEquals(AlertSeverity.CRITICAL, buildAlerts(AlertInputs(energy = EnergyReport(levelPct = 8, charging = false))).single().severity)
        assertTrue(buildAlerts(AlertInputs(energy = EnergyReport(levelPct = 5, charging = true))).isEmpty())
    }

    @Test
    fun `los mas graves van primero`() {
        val a = buildAlerts(AlertInputs(
            sensors = listOf(SensorAlertInfo("s1", false)),
            relays = listOf(RelayAlertInfo("r1", true, true, AutomationStatus.OFF)),
            syncOnline = false, syncPending = 900
        ))
        assertEquals(AlertSeverity.CRITICAL, a.first().severity)
        assertEquals(AlertSeverity.INFO, a.last().severity)
    }
}
