package cl.avelyn.bd_control_app.model.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireProtocolTest {

    // Vectores generados con una implementación independiente (Python `cryptography`, AES-256-GCM),
    // con el mismo formato de frame que usa relay.ino: [len:2][IV:12][ciphertext][tag:16].
    private val key = WireProtocol.hexToKey("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val iv = ByteArray(12) { (100 + it).toByte() }
    private val knownFrameHex =
        "003f6465666768696a6b6c6d6e6f3339bd0b1dcb6cbc782311caf6471c9c2ee03c3ba74e9a1685eb8e3a9ecfc065a5cb3d11f654c0402e97e33fd3619ca17df3bb"
    private val knownReplyBodyHex =
        "c8c9cacbcccdcecfd0d1d2d36a5a0eb0a4f6ca0c565a7ed03c3cda6971dcdee86b8b465b218de15127486005"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `el frame del comando coincide byte a byte con la referencia`() {
        val frame = WireProtocol.encryptFrame(key, WireProtocol.fanCommandJson("rele-1", true).toByteArray(), iv)
        assertEquals(knownFrameHex, hex(frame))
    }

    @Test
    fun `se descifra la respuesta del rele`() {
        val plain = WireProtocol.decryptBody(key, unhex(knownReplyBodyHex))
        assertNotNull(plain)
        val reply = WireProtocol.parseRelayReply(String(plain!!))
        assertEquals(RelayReply(ok = true, fanOn = true), reply)
    }

    @Test
    fun `ida y vuelta con IV aleatorio`() {
        val frame = WireProtocol.encryptFrame(key, "hola".toByteArray())
        val len = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        assertEquals(frame.size - 2, len)
        assertArrayEquals("hola".toByteArray(), WireProtocol.decryptBody(key, frame.copyOfRange(2, frame.size)))
    }

    @Test
    fun `un frame alterado o con otra llave se rechaza`() {
        val body = unhex(knownFrameHex).copyOfRange(2, knownFrameHex.length / 2)
        val tampered = body.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertNull(WireProtocol.decryptBody(key, tampered))
        assertNull(WireProtocol.decryptBody(ByteArray(32), body))
        assertNull(WireProtocol.decryptBody(key, ByteArray(10)))
    }

    @Test
    fun `llave hex invalida falla con mensaje claro`() {
        assertTrue(runCatching { WireProtocol.hexToKey("") }.isFailure)
        assertTrue(runCatching { WireProtocol.hexToKey("zz".repeat(32)) }.isFailure)
    }

    @Test
    fun `sensor con id`() {
        val msg = WireProtocol.parseDatagram("""{"id":"sensor-bodega","t":24.5,"h":60.0}""")
        assertEquals(UdpMsg.Sensor("sensor-bodega", 24.5f, 60.0f), msg)
    }

    @Test
    fun `sensor de firmware antiguo sin id usa el id por defecto`() {
        val msg = WireProtocol.parseDatagram("""{"t":20.0,"h":55.5}""")
        assertEquals(UdpMsg.Sensor(DeviceIds.DEFAULT_SENSOR, 20.0f, 55.5f), msg)
    }

    @Test
    fun `anuncio de rele con y sin id`() {
        assertEquals(UdpMsg.Relay("rele-2", true), WireProtocol.parseDatagram("""{"src":"relay","id":"rele-2","fan":1}"""))
        assertEquals(UdpMsg.Relay(DeviceIds.DEFAULT_RELAY, false), WireProtocol.parseDatagram("""{"src":"relay","fan":0}"""))
    }

    @Test
    fun `datagramas invalidos se ignoran`() {
        assertNull(WireProtocol.parseDatagram("no es json"))
        assertNull(WireProtocol.parseDatagram("""{"t":24.5}"""))
        assertNull(WireProtocol.parseDatagram("""{"t":24.5,"h":140}"""))   // humedad imposible
        assertNull(WireProtocol.parseDatagram("""{"t":900,"h":50}"""))      // temperatura imposible
        assertNull(WireProtocol.parseDatagram("""{"src":"relay"}"""))
    }

    @Test
    fun `el comando lleva el destinatario y escapa ids raros`() {
        assertEquals("""{"cmd":"FAN","val":0,"id":"rele-2"}""", WireProtocol.fanCommandJson("rele-2", false))
        // un id con comillas no puede inyectar campos nuevos dentro del comando cifrado
        val json = org.json.JSONObject(WireProtocol.fanCommandJson("x\",\"cmd\":\"OTRO", true))
        assertEquals("FAN", json.getString("cmd"))
        assertEquals("x\",\"cmd\":\"OTRO", json.getString("id"))
    }

    @Test
    fun `ids invalidos en la red se descartan`() {
        assertTrue(WireProtocol.isValidDeviceId("sensor-bodega_1.A"))
        assertNull(WireProtocol.parseDatagram("""{"id":"con espacio","t":20.0,"h":50.0}"""))
        assertNull(WireProtocol.parseDatagram("""{"id":"<script>","t":20.0,"h":50.0}"""))
        assertNull(WireProtocol.parseDatagram("""{"id":"${"a".repeat(33)}","t":20.0,"h":50.0}"""))
        assertNull(WireProtocol.parseDatagram("""{"src":"relay","id":"","fan":1}"""))
    }

    @Test
    fun `el latido y el estado del modo seguro viajan por el protocolo`() {
        assertEquals("""{"cmd":"HB","id":"rele-1"}""", WireProtocol.heartbeatJson("rele-1"))
        assertEquals(RelayReply(ok = true, fanOn = true, failSafe = true), WireProtocol.parseRelayReply("""{"ok":1,"fan":1,"fs":1}"""))
        assertEquals(RelayReply(ok = true, fanOn = false, failSafe = false), WireProtocol.parseRelayReply("""{"ok":1,"fan":0}"""))   // firmware antiguo sin fs
        assertEquals(UdpMsg.Relay("rele-1", true, failSafe = true), WireProtocol.parseDatagram("""{"src":"relay","id":"rele-1","fan":1,"fs":1}"""))
        assertEquals(UdpMsg.Relay("rele-1", false, failSafe = false), WireProtocol.parseDatagram("""{"src":"relay","id":"rele-1","fan":0}"""))
    }

    @Test
    fun `el latido tambien viaja cifrado y autenticado`() {
        val frame = WireProtocol.encryptFrame(key, WireProtocol.heartbeatJson("rele-1").toByteArray())
        val body = frame.copyOfRange(2, frame.size)
        assertEquals(WireProtocol.heartbeatJson("rele-1"), String(WireProtocol.decryptBody(key, body)!!))
        assertNull(WireProtocol.decryptBody(ByteArray(32), body))
    }
}
