package cl.avelyn.bd_control_app.model.automation

import cl.avelyn.bd_control_app.model.automation.ControlAction.Heartbeat
import cl.avelyn.bd_control_app.model.automation.ControlAction.SetFan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationControllerTest {

    private val cfg = AutomationConfig(
        humidityOnPct = 70f, humidityOffPct = 60f,
        minOnMs = 120_000, minOffMs = 120_000, staleSensorMs = 300_000, heartbeatEveryMs = 30_000
    )

    private fun sensor(id: String = "s1", hum: Float? = 50f, online: Boolean = true, age: Long = 1_000) = SensorView(id, hum, online, age)
    private fun relay(id: String = "r1", on: Boolean? = false, auto: Boolean = true, online: Boolean = true, control: Boolean = true) =
        RelayView(id, on, online, control, auto)
    private fun snap(now: Long, sensors: List<SensorView>, vararg relays: RelayView) = ControlSnapshot(now, sensors, relays.toList())

    @Test
    fun `enciende al llegar al umbral y apaga al bajar al umbral de apagado`() {
        val c = AutomationController(cfg)
        assertEquals(listOf(SetFan("r1", true)), c.tick(snap(1_000_000, listOf(sensor(hum = 71f)), relay(on = false))).actions)
        c.onFanChanged("r1", 1_000_000)
        // histéresis: entre 60 y 70 no hace nada con el ventilador encendido (solo latidos)
        val mid = c.tick(snap(1_200_000, listOf(sensor(hum = 65f)), relay(on = true))).actions
        assertTrue(mid.none { it is SetFan })
        assertEquals(listOf(SetFan("r1", false)), c.tick(snap(1_300_000, listOf(sensor(hum = 59f)), relay(on = true))).actions.filterIsInstance<SetFan>())
    }

    @Test
    fun `no oscila porque respeta el tiempo minimo encendido y apagado`() {
        val c = AutomationController(cfg)
        c.onFanChanged("r1", 1_000_000)                                  // se encendió hace poco
        // la humedad ya bajó, pero aún no pasó el mínimo encendido (2 min): espera
        assertTrue(c.tick(snap(1_060_000, listOf(sensor(hum = 50f)), relay(on = true))).actions.none { it is SetFan })
        assertEquals(listOf(SetFan("r1", false)), c.tick(snap(1_120_000, listOf(sensor(hum = 50f)), relay(on = true))).actions.filterIsInstance<SetFan>())
        c.onFanChanged("r1", 1_120_000)
        // recién apagado: aunque suba la humedad, espera el mínimo apagado
        assertTrue(c.tick(snap(1_150_000, listOf(sensor(hum = 80f)), relay(on = false))).actions.none { it is SetFan })
        assertEquals(listOf(SetFan("r1", true)), c.tick(snap(1_240_000, listOf(sensor(hum = 80f)), relay(on = false))).actions.filterIsInstance<SetFan>())
    }

    @Test
    fun `decide con el peor caso entre varios sensores`() {
        val c = AutomationController(cfg)
        val sensors = listOf(sensor("a", 55f), sensor("b", 72f), sensor("c", 50f))
        assertEquals(listOf(SetFan("r1", true)), c.tick(snap(1_000_000, sensors, relay(on = false))).actions.filterIsInstance<SetFan>())
        // para apagar deben estar TODOS por debajo del umbral
        val c2 = AutomationController(cfg)
        assertTrue(c2.tick(snap(1_000_000, listOf(sensor("a", 55f), sensor("b", 62f)), relay(on = true))).actions.none { it is SetFan })
        assertEquals(listOf(SetFan("r1", false)), c2.tick(snap(1_000_000, listOf(sensor("a", 55f), sensor("b", 58f)), relay(on = true))).actions.filterIsInstance<SetFan>())
    }

    @Test
    fun `ignora sensores fuera de linea o con datos viejos`() {
        val c = AutomationController(cfg)
        val sensors = listOf(sensor("viejo", 90f, age = 600_000), sensor("caido", 90f, online = false), sensor("ok", 40f))
        assertTrue(c.tick(snap(1_000_000, sensors, relay(on = false))).actions.none { it is SetFan })
    }

    @Test
    fun `sin datos de sensores el automatico NO envia latidos ni ordenes para que el rele entre en modo seguro`() {
        val c = AutomationController(cfg)
        val r = c.tick(snap(1_000_000, listOf(sensor(online = false)), relay(auto = true)))
        assertTrue("sin acciones: $r", r.actions.isEmpty())
        assertEquals(AutomationStatus.PAUSED_NO_DATA, r.statuses["r1"])
        // ni siquiera pasado el tiempo de latido
        assertTrue(c.tick(snap(1_100_000, emptyList(), relay(auto = true))).actions.isEmpty())
    }

    @Test
    fun `en manual manda el usuario y solo se envian latidos periodicos`() {
        val c = AutomationController(cfg)
        assertEquals(listOf(Heartbeat("r1")), c.tick(snap(1_000_000, listOf(sensor(hum = 99f)), relay(auto = false, on = false))).actions)
        assertTrue(c.tick(snap(1_020_000, emptyList(), relay(auto = false))).actions.isEmpty())        // aún no toca
        assertEquals(listOf(Heartbeat("r1")), c.tick(snap(1_030_000, emptyList(), relay(auto = false))).actions)
        assertEquals(AutomationStatus.OFF, c.tick(snap(1_090_000, emptyList(), relay(auto = false))).statuses["r1"])
    }

    @Test
    fun `en manual los latidos siguen aunque no haya sensores`() {
        // el usuario esta al mando: la app esta viva y asi se lo dice al rele
        val c = AutomationController(cfg)
        assertEquals(listOf(Heartbeat("r1")), c.tick(snap(1_000_000, emptyList(), relay(auto = false))).actions)
    }

    @Test
    fun `el latido de un automatico sano sale cada 30 s y una orden cuenta como contacto`() {
        val c = AutomationController(cfg)
        val s = listOf(sensor(hum = 65f))
        assertEquals(listOf(Heartbeat("r1")), c.tick(snap(1_000_000, s, relay(on = false))).actions)
        assertTrue(c.tick(snap(1_010_000, s, relay(on = false))).actions.isEmpty())
        assertEquals(listOf(Heartbeat("r1")), c.tick(snap(1_030_000, s, relay(on = false))).actions)
        // una orden de encendido reinicia el conteo del latido
        val on = c.tick(snap(1_040_000, listOf(sensor(hum = 75f)), relay(on = false)))
        assertEquals(listOf(SetFan("r1", true)), on.actions)
        assertTrue(c.tick(snap(1_060_000, s, relay(on = true))).actions.isEmpty())
    }

    @Test
    fun `no hace nada con reles sin permiso o fuera de linea`() {
        val c = AutomationController(cfg)
        val s = listOf(sensor(hum = 99f))
        assertTrue(c.tick(snap(1_000_000, s, relay(control = false))).actions.isEmpty())
        assertTrue(c.tick(snap(1_000_000, s, relay(online = false))).actions.isEmpty())
    }

    @Test
    fun `cada rele se gobierna por separado`() {
        val c = AutomationController(cfg)
        val r = c.tick(snap(1_000_000, listOf(sensor(hum = 75f)), relay("a", on = false, auto = true), relay("b", on = false, auto = false)))
        assertEquals(setOf<ControlAction>(SetFan("a", true), Heartbeat("b")), r.actions.toSet())
    }

    @Test
    fun `una configuracion sin histeresis se rechaza`() {
        assertTrue(runCatching { AutomationConfig(humidityOnPct = 60f, humidityOffPct = 60f) }.isFailure)
    }
}
