package cl.avelyn.bd_control_app.model.energy

import cl.avelyn.bd_control_app.model.AppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EnergyMonitorTest {

    private class Visibility(initial: Boolean) : AppVisibility {
        val fg = MutableStateFlow(initial)
        override val isForeground: StateFlow<Boolean> = fg
    }

    @Test
    fun `acumula muestras y estima el ritmo de descarga`() {
        var now = 0L
        var level = 90
        val monitor = EnergyMonitor(
            reader = { t -> BatterySample(t, level, charging = false) },
            visibility = Visibility(true),
            clockMs = { now }
        )
        // 12 %/h: baja 1 % cada 5 min durante 1 h
        repeat(13) { i -> now = i * 5 * 60_000L; level = 90 - i; monitor.sampleNow() }
        val r = monitor.report.value
        assertEquals(78, r.levelPct)
        assertEquals(12f, r.dischargePctPerHour!!, 0.3f)
    }

    @Test
    fun `descarta muestras mas viejas que la ventana`() {
        var now = 0L
        val monitor = EnergyMonitor({ t -> BatterySample(t, 50, charging = true) }, Visibility(true), keepMs = 10 * 60_000L, clockMs = { now })
        repeat(30) { i -> now = i * 60_000L; monitor.sampleNow() }
        assertTrue(monitor.report.value.charging)
        assertNull(monitor.report.value.dischargePctPerHour)
    }

    @Test
    fun `solo muestrea con la app a la vista`() = runBlocking {
        val vis = Visibility(true)
        val reads = AtomicInteger(0)
        val monitor = EnergyMonitor({ t -> reads.incrementAndGet(); BatterySample(t, 80, false) }, vis, everyMs = 40)
        val job = CoroutineScope(Dispatchers.Default).launch { monitor.run() }
        delay(300)
        assertTrue("muestreó a la vista: ${reads.get()}", reads.get() >= 4)

        vis.fg.value = false
        delay(100)
        val frozen = reads.get()
        delay(300)
        assertEquals("en segundo plano no mide (medir también gasta batería)", frozen, reads.get())

        vis.fg.value = true
        delay(200)
        assertTrue("retoma al volver", reads.get() > frozen)
        job.cancel()
    }
}
