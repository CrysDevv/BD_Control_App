package cl.avelyn.bd_control_app.model.network

import cl.avelyn.bd_control_app.model.network.SerialProtocol.Message
import cl.avelyn.bd_control_app.model.network.SerialProtocol.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialProtocolTest {

    // Tramas producidas por el código C++ REAL del firmware (firmware/sensor/bd_serial.h compilado en un
    // computador). Si Kotlin y C++ discrepan en el CRC o en el formato, estas pruebas fallan.
    // bootId = 4 000 000 000: mayor que Int.MAX_VALUE, el caso borde de los enteros de 32 bits sin signo.
    private val fromFirmware = mapOf(
        "HELLO,0,sensor-1,1.0,2000,4000000000" to "\$HELLO,0,sensor-1,1.0,2000,4000000000*6BE5",
        "TLM,sensor-1,4000000000,1,24.6,60.0" to "\$TLM,sensor-1,4000000000,1,24.6,60.0*AA70",
        "TLM,sensor-1,4000000000,2,-3.2,45.0" to "\$TLM,sensor-1,4000000000,2,-3.2,45.0*DE01",
        "SYNCSTART,9,2,1800,0,4000000000,1" to "\$SYNCSTART,9,2,1800,0,4000000000,1*C135",
        "BUF,sensor-1,4000000000,1,3000,24.6,60.0" to "\$BUF,sensor-1,4000000000,1,3000,24.6,60.0*AA4C",
        "BUF,sensor-1,4000000000,2,1000,-3.2,45.0" to "\$BUF,sensor-1,4000000000,2,1000,-3.2,45.0*B848",
        "SYNCEND,9,2" to "\$SYNCEND,9,2*CCD0",
        "PONG,42" to "\$PONG,42*79DC",
        "ACK,5" to "\$ACK,5*9754",
        "ERR,6,RANGE" to "\$ERR,6,RANGE*8401",
        "SYNC,9" to "\$SYNC,9*61E0",
        "CONF,10,4294967295" to "\$CONF,10,4294967295*7BDF"
    )

    private fun line(bytes: ByteArray) = String(bytes, Charsets.US_ASCII).trimEnd('\n')
    private fun decode(text: String) = SerialProtocol.decode(SerialProtocol.parseLine(text) as Parsed.Valid)

    @Test
    fun `el CRC coincide con el vector estandar CRC-16 CCITT-FALSE`() {
        assertEquals(0x29B1, SerialProtocol.crc16("123456789".toByteArray()))
    }

    @Test
    fun `Kotlin arma byte a byte las mismas tramas que el firmware`() {
        for ((body, expected) in fromFirmware) assertEquals(expected, line(SerialProtocol.encode(body)))
        assertEquals(fromFirmware["SYNC,9"], line(SerialProtocol.sync(9)))
        assertEquals(fromFirmware["CONF,10,4294967295"], line(SerialProtocol.conf(10, 4_294_967_295L)))
    }

    @Test
    fun `Kotlin lee las tramas del firmware`() {
        val boot = 4_000_000_000L
        assertEquals(Message.Hello(0, "sensor-1", "1.0", 2000, boot), decode(fromFirmware["HELLO,0,sensor-1,1.0,2000,4000000000"]!!))
        assertEquals(Message.Telemetry("sensor-1", boot, 1, 24.6f, 60.0f), decode(fromFirmware["TLM,sensor-1,4000000000,1,24.6,60.0"]!!))
        assertEquals(Message.Telemetry("sensor-1", boot, 2, -3.2f, 45.0f), decode(fromFirmware["TLM,sensor-1,4000000000,2,-3.2,45.0"]!!))
        assertEquals(Message.SyncStart(9, 2, 1800, 0, boot, 1), decode(fromFirmware["SYNCSTART,9,2,1800,0,4000000000,1"]!!))
        assertEquals(Message.Buffered("sensor-1", boot, 1, 3000, 24.6f, 60.0f), decode(fromFirmware["BUF,sensor-1,4000000000,1,3000,24.6,60.0"]!!))
        assertEquals(Message.SyncEnd(9, 2), decode(fromFirmware["SYNCEND,9,2"]!!))
        assertEquals(Message.Pong(42), decode(fromFirmware["PONG,42"]!!))
        assertEquals(Message.Ack(5), decode(fromFirmware["ACK,5"]!!))
        assertEquals(Message.Err(6, "RANGE"), decode(fromFirmware["ERR,6,RANGE"]!!))
    }

    @Test
    fun `ningun cambio de un solo bit produce una trama valida`() {
        for (good in fromFirmware.values) {
            for (i in good.indices) for (bit in 0..7) {
                val flipped = (good[i].code xor (1 shl bit)).toChar()
                if (flipped == '\n' || flipped == '\r') continue // cambiaría el fin de línea, no el contenido
                val bad = good.substring(0, i) + flipped + good.substring(i + 1)
                assertTrue("aceptó '$bad'", SerialProtocol.parseLine(bad) !is Parsed.Valid)
            }
        }
    }

    @Test
    fun `el CRC en minusculas se rechaza`() {
        assertTrue(SerialProtocol.parseLine("\$PING,1*f692") is Parsed.Corrupt)
    }

    @Test
    fun `distingue ruido de tramas danadas`() {
        assertEquals(Parsed.Noise, SerialProtocol.parseLine("rst:0x1 (POWERON_RESET),boot:0x13"))
        assertEquals(Parsed.Noise, SerialProtocol.parseLine(""))
        assertEquals(Parsed.Corrupt, SerialProtocol.parseLine("\$PING,1*0000"))
        assertEquals(Parsed.Corrupt, SerialProtocol.parseLine("\$PING,1"))
        assertEquals(Parsed.Corrupt, SerialProtocol.parseLine("\$*F692"))
        assertTrue(SerialProtocol.parseLine("\$PING,1*F692\r") is Parsed.Valid) // tolera \r
    }

    @Test
    fun `descarta mensajes con contenido invalido aunque el CRC sea correcto`() {
        fun dec(body: String) = decode(line(SerialProtocol.encode(body)))
        assertNotNull(dec("TLM,sensor-1,5,1,20.0,50.0"))
        assertNull(dec("TLM,id malo,5,1,20.0,50.0"))        // id con espacio
        assertNull(dec("TLM,sensor-1,5,0,20.0,50.0"))       // n = 0 no existe
        assertNull(dec("TLM,sensor-1,5,1,20.0,150.0"))      // humedad imposible
        assertNull(dec("TLM,sensor-1,5,1,abc,50.0"))        // no numérico
        assertNull(dec("TLM,sensor-1,5,1,20.0"))            // faltan campos
        assertNull(dec("TLM,sensor-1,-5,1,20.0,50.0"))      // bootId negativo
        assertNull(dec("TLM,sensor-1,4294967296,1,20.0,50.0")) // 2^32: no cabe en 32 bits
        assertNull(dec("TLM,sensor-1,5,+1,20.0,50.0"))      // signo no permitido
        assertNull(dec("BUF,sensor-1,5,1,-100,20.0,50.0"))  // edad negativa
        assertNull(dec("HELLO,0,sensor-1,1.0,2000"))        // falta el bootId
        assertNull(dec("SYNCSTART,9,2,1800,0,5,0"))         // la n más antigua no puede ser 0
        assertNull(dec("NUEVO,1"))
    }

    @Test
    fun `no deja armar comandos invalidos`() {
        assertTrue(runCatching { SerialProtocol.ping(0) }.isFailure)
        assertTrue(runCatching { SerialProtocol.ping(65536) }.isFailure)
        assertTrue(runCatching { SerialProtocol.setInterval(1, 10) }.isFailure)
        assertTrue(runCatching { SerialProtocol.setInterval(1, 9_999_999) }.isFailure)
        assertTrue(runCatching { SerialProtocol.conf(1, -1) }.isFailure)
        assertTrue(runCatching { SerialProtocol.conf(1, 4_294_967_296L) }.isFailure)
    }

    @Test
    fun `la identidad de una lectura es estable`() {
        assertEquals("4000000000-12", SampleRef(4_000_000_000L, 12).key)
    }
}
