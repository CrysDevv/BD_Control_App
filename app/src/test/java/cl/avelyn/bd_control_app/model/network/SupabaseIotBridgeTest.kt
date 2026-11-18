package cl.avelyn.bd_control_app.model.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseIotBridgeTest {

    @Test
    fun `BodegaStatusDto detecta modo automatico por defecto o cuando mode es auto`() {
        val dtoDefault = BodegaStatusDto()
        assertTrue(dtoDefault.isAuto)
        assertEquals("auto", dtoDefault.normalizedMode)

        val dtoAuto = BodegaStatusDto(mode = "auto")
        assertTrue(dtoAuto.isAuto)
        assertEquals("auto", dtoAuto.normalizedMode)

        val dtoAutoUpper = BodegaStatusDto(mode = "AUTO")
        assertTrue(dtoAutoUpper.isAuto)
        assertEquals("auto", dtoAutoUpper.normalizedMode)
    }

    @Test
    fun `BodegaStatusDto detecta modo manual correctamente`() {
        val dtoManual = BodegaStatusDto(mode = "manual")
        assertFalse(dtoManual.isAuto)
        assertEquals("manual", dtoManual.normalizedMode)

        val dtoManualUpper = BodegaStatusDto(mode = "MANUAL")
        assertFalse(dtoManualUpper.isAuto)
        assertEquals("manual", dtoManualUpper.normalizedMode)
    }
}
