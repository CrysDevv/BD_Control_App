package cl.avelyn.bd_control_app.model.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveSamplerTest {
    private val s = AdaptiveSampler(onThresholdPct = 70f, offThresholdPct = 60f)
    private val now = 10_000_000L
    private fun flat(hum: Float, minutes: Int) = (0..minutes).map { HumidityPoint(now - (minutes - it) * 60_000L, hum) }

    @Test
    fun `aire quieto y lejos de los umbrales mide cada 60 s`() {
        assertEquals(60_000, s.target(flat(45f, 5), now))
    }

    @Test
    fun `sin historial suficiente no se apresura a espaciar`() {
        assertEquals(30_000, s.target(flat(45f, 1), now))
        assertEquals(30_000, s.target(emptyList(), now))
    }

    @Test
    fun `si la humedad cambia rapido mide cada 10 s`() {
        val rising = (0..5).map { HumidityPoint(now - (5 - it) * 60_000L, 40f + it * 2f) }   // +10 puntos en 5 min
        assertEquals(10_000, s.target(rising, now))
    }

    @Test
    fun `cerca de los umbrales mide seguido aunque este quieta`() {
        assertEquals(10_000, s.target(flat(66f, 5), now))   // a 4 puntos del umbral de encendido (70)
        assertEquals(10_000, s.target(flat(75f, 5), now))   // por encima
        assertEquals(10_000, s.target(flat(58f, 5), now))   // cerca del umbral de apagado (60)
        assertEquals(60_000, s.target(flat(50f, 5), now))   // lejos de ambos
    }

    @Test
    fun `una variacion pequena queda en el intervalo intermedio`() {
        val wobble = (0..5).map { HumidityPoint(now - (5 - it) * 60_000L, 45f + (it % 2) * 1.5f) }   // rango 1,5: entre calma y actividad
        assertEquals(30_000, s.target(wobble, now))
    }

    @Test
    fun `los datos fuera de la ventana no cuentan`() {
        val old = listOf(HumidityPoint(now - 20 * 60_000L, 90f)) + flat(45f, 5)
        assertEquals(60_000, s.target(old, now))
    }

    @Test
    fun `no recomienda cambiar si ya es el intervalo o se cambio hace poco`() {
        assertNull(s.recommend("s1", flat(45f, 5), currentMs = 60_000, nowMs = now))
        assertEquals(60_000, s.recommend("s1", flat(45f, 5), currentMs = 2_000, nowMs = now))
        s.applied("s1", now)
        assertNull("recién cambiado", s.recommend("s1", flat(75f, 5), currentMs = 60_000, nowMs = now + 30_000))
        assertEquals(10_000, s.recommend("s1", flat(75f, 5), currentMs = 60_000, nowMs = now + 130_000))
    }

    @Test
    fun `el limite de cambios es por sensor`() {
        s.applied("a", now)
        assertEquals(60_000, s.recommend("b", flat(45f, 5), currentMs = 2_000, nowMs = now + 1_000))
    }
}
