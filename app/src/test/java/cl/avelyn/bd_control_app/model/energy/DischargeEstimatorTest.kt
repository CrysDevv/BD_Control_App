package cl.avelyn.bd_control_app.model.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DischargeEstimatorTest {
    private val e = DischargeEstimator()
    private val min = 60_000L
    private fun s(minute: Int, level: Int, charging: Boolean = false, ma: Int? = null) = BatterySample(minute * min, level, charging, ma)

    @Test
    fun `calcula el ritmo de descarga con una recta`() {
        // 90 % -> 84 % en 60 min, bajando de a 1 % cada 10 min: 6 %/h
        val samples = (0..6).map { s(it * 10, 90 - it) }
        val r = e.report(samples)
        assertEquals(84, r.levelPct)
        assertEquals(6f, r.dischargePctPerHour!!, 0.05f)
        assertEquals(14f, r.hoursRemaining!!, 0.3f)        // 84 / 6
    }

    @Test
    fun `la cuantizacion de a 1 por ciento no engana a la recta`() {
        // 6 %/h real, pero el nivel solo informa enteros y con ruido de un escalón
        val samples = listOf(s(0, 80), s(10, 79), s(20, 79), s(30, 77), s(40, 76), s(50, 76), s(60, 74))
        assertEquals(6f, e.report(samples).dischargePctPerHour!!, 0.9f)
    }

    @Test
    fun `no estima con pocos datos`() {
        assertNull(e.report(listOf(s(0, 90), s(5, 90), s(9, 89))).dischargePctPerHour)    // < 10 min
        assertNull(e.report(listOf(s(0, 90), s(20, 88))).dischargePctPerHour)              // < 3 puntos
        assertEquals(EnergyReport(), e.report(emptyList()))
    }

    @Test
    fun `mientras carga no hay ritmo de descarga pero si el nivel`() {
        val r = e.report((0..6).map { s(it * 10, 50 + it, charging = true) })
        assertEquals(56, r.levelPct)
        assertTrue(r.charging)
        assertNull(r.dischargePctPerHour)
        assertNull(r.hoursRemaining)
    }

    @Test
    fun `solo usa el ultimo tramo sin cargar`() {
        val samples = (0..5).map { s(it * 10, 40 + it * 5, charging = true) } +      // cargó hasta 65
            (6..12).map { s(it * 10, 65 - (it - 6) * 2) }                              // luego descarga 12 %/h
        assertEquals(12f, e.report(samples).dischargePctPerHour!!, 0.3f)
    }

    @Test
    fun `una bateria que no baja no da ritmo`() {
        val r = e.report((0..6).map { s(it * 10, 80) })
        assertNull(r.dischargePctPerHour)
        assertNull(r.hoursRemaining)
    }

    @Test
    fun `promedia la corriente ignorando faltantes y el signo`() {
        val samples = (0..6).map { s(it * 10, 90 - it, ma = when (it) { 0 -> -300; 1 -> 400; 2 -> null; else -> -500 }) }
        assertEquals(((300 + 400 + 500 * 4) / 6.0).toInt(), e.report(samples).avgCurrentMa)
        assertNotNull(e.report(samples).dischargePctPerHour)
    }
}
