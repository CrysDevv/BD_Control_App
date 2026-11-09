package cl.avelyn.bd_control_app.model.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTest {

    // Secreto y valores del Apéndice B del RFC 6238 (SHA-1, secreto ASCII "12345678901234567890").
    private val rfcSecret = "12345678901234567890".toByteArray()
    private val rfcVectors = listOf(
        59L to "94287082",
        1111111109L to "07081804",
        1111111111L to "14050471",
        1234567890L to "89005924",
        2000000000L to "69279037",
        20000000000L to "65353130"
    )

    @Test
    fun `coincide con los vectores oficiales del RFC 6238`() {
        for ((seconds, expected8) in rfcVectors) {
            val step = Totp.stepFor(seconds * 1000)
            assertEquals("8 dígitos t=$seconds", expected8, Totp.generate(rfcSecret, step, digits = 8))
            // con 6 dígitos son los últimos 6 del valor de 8 (el truncamiento es el mismo)
            assertEquals("6 dígitos t=$seconds", expected8.takeLast(6), Totp.generate(rfcSecret, step))
        }
    }

    @Test
    fun `decodifica Base32 como las apps de autenticacion`() {
        // RFC 4648: BASE32("12345678901234567890")
        assertArrayEquals(rfcSecret, Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        // minúsculas, espacios y guiones (como los muestran algunas apps) no importan
        assertArrayEquals(rfcSecret, Totp.decodeBase32("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq"))
        assertTrue(runCatching { Totp.decodeBase32("ABC1") }.isFailure) // '1' no es Base32
        assertTrue(runCatching { Totp.decodeBase32("") }.isFailure)
    }

    @Test
    fun `acepta el codigo actual y rechaza uno incorrecto`() {
        val now = 1111111111_000L
        assertEquals(Totp.stepFor(now), Totp.verify(rfcSecret, "050471", now))
        assertNull(Totp.verify(rfcSecret, "050472", now))
    }

    @Test
    fun `tolera un paso de desfase pero no dos`() {
        val now = 1111111111_000L
        val prev = Totp.generate(rfcSecret, Totp.stepFor(now) - 1)
        val next = Totp.generate(rfcSecret, Totp.stepFor(now) + 1)
        val tooOld = Totp.generate(rfcSecret, Totp.stepFor(now) - 2)
        assertEquals(Totp.stepFor(now) - 1, Totp.verify(rfcSecret, prev, now))
        assertEquals(Totp.stepFor(now) + 1, Totp.verify(rfcSecret, next, now))
        assertNull(Totp.verify(rfcSecret, tooOld, now))
    }

    @Test
    fun `un codigo ya usado no se puede reutilizar`() {
        val now = 1111111111_000L
        val code = Totp.generate(rfcSecret, Totp.stepFor(now))
        val used = Totp.verify(rfcSecret, code, now)!!
        assertNull(Totp.verify(rfcSecret, code, now, lastUsedStep = used))
        // el código del paso siguiente sigue siendo válido
        val nextCode = Totp.generate(rfcSecret, used + 1)
        assertEquals(used + 1, Totp.verify(rfcSecret, nextCode, now, lastUsedStep = used))
    }

    @Test
    fun `formatos invalidos se rechazan sin excepcion`() {
        val now = 1111111111_000L
        assertNull(Totp.verify(rfcSecret, "", now))
        assertNull(Totp.verify(rfcSecret, "12345", now))
        assertNull(Totp.verify(rfcSecret, "1234567", now))
        assertNull(Totp.verify(rfcSecret, "12a456", now))
        assertNull(Totp.verify(rfcSecret, "٠٥٠٤٧١", now)) // dígitos arábigos no son ASCII
    }
}
