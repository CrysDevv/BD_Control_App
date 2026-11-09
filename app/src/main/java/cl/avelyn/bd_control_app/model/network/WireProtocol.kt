package cl.avelyn.bd_control_app.model.network

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo de bajo nivel entre la app y los ESP32 (ver `firmware/`). No toca la red ni
 * Android, para poder probarlo con tests unitarios.
 *
 * **UDP (ESP32 -> app, broadcast, puerto [UDP_PORT]), JSON en texto plano:**
 * - Sensor: `{"id":"sensor-1","t":24.5,"h":60.0}`
 * - Relé:   `{"src":"relay","id":"rele-1","fan":1}`
 * El campo `id` es opcional por compatibilidad con firmware antiguo (ver [DeviceIds]).
 *
 * **TCP (app -> relé, puerto [TCP_PORT]), cifrado con AES-256-GCM:**
 * `[len:2 big-endian][IV:12][ciphertext:n][tag:16]`, donde `len` = 12 + n + 16.
 * Petición `{"cmd":"FAN","val":1,"id":"rele-1"}` o latido `{"cmd":"HB","id":"rele-1"}`; respuesta `{"ok":1,"fan":1,"fs":0}`
 * (`fs` = el relé está en modo seguro), ambas con este mismo formato. El latido no cambia nada: solo le dice al
 * relé que la app sigue viva (si no lo oye en un buen rato entra en modo seguro).
 * El `id` es el destinatario: el relé ignora el comando si no es el suyo (validación del dispositivo).
 */
object WireProtocol {
    const val UDP_PORT = 4210
    const val TCP_PORT = 5000

    const val IV_LEN = 12
    const val TAG_LEN = 16
    const val MIN_BODY_LEN = IV_LEN + TAG_LEN
    /** El firmware descarta cuerpos de más de 200 bytes. */
    const val MAX_BODY_LEN = 200

    private const val TEMP_MIN_C = -40.0
    private const val TEMP_MAX_C = 125.0

    private val random = SecureRandom()

    /**
     * Un id válido: 1 a 32 caracteres de `[A-Za-z0-9_.-]`. Los ids llegan por UDP, donde cualquiera
     * en la red puede enviar datagramas, así que se restringen antes de mostrarlos, guardarlos en
     * el historial o ponerlos dentro de un comando.
     */
    private val ID_REGEX = Regex("^[A-Za-z0-9_.-]{1,32}$")

    fun isValidDeviceId(id: String): Boolean = ID_REGEX.matches(id)

    /** Descarta lecturas físicamente imposibles (cable suelto, ruido): temperatura -40..125 °C, humedad 0..100 %. */
    fun isPlausibleReading(tempC: Double, humPct: Double): Boolean =
        tempC in TEMP_MIN_C..TEMP_MAX_C && humPct in 0.0..100.0

    // ---------- UDP ----------

    /** Interpreta un datagrama UDP; devuelve null si no es un mensaje válido (se ignora). */
    fun parseDatagram(text: String): UdpMsg? = try {
        val json = JSONObject(text)
        if (json.optString("src") == "relay") {
            val id = deviceId(json, DeviceIds.DEFAULT_RELAY)
            if (json.has("fan") && id != null) UdpMsg.Relay(id, json.getInt("fan") == 1, failSafe = json.optInt("fs", 0) == 1) else null
        } else if (json.has("t") && json.has("h")) {
            val t = json.getDouble("t")
            val h = json.getDouble("h")
            val id = deviceId(json, DeviceIds.DEFAULT_SENSOR)
            if (id != null && isPlausibleReading(t, h)) {
                UdpMsg.Sensor(id, t.toFloat(), h.toFloat())
            } else null
        } else null
    } catch (_: JSONException) {
        null
    }

    /** El id del mensaje, [fallback] si no trae (firmware antiguo), o null si trae uno inválido. */
    private fun deviceId(json: JSONObject, fallback: String): String? {
        if (!json.has("id")) return fallback
        val id = json.optString("id")
        return if (isValidDeviceId(id)) id else null
    }

    // ---------- TCP cifrado ----------

    /** Comando de ventilador dirigido al relé [deviceId]; el id se escapa por si no fuera un id válido. */
    fun fanCommandJson(deviceId: String, on: Boolean): String =
        """{"cmd":"FAN","val":${if (on) 1 else 0},"id":${JSONObject.quote(deviceId)}}"""

    /** Latido dirigido al relé [deviceId]: "sigo aquí". No cambia el estado del ventilador. */
    fun heartbeatJson(deviceId: String): String = """{"cmd":"HB","id":${JSONObject.quote(deviceId)}}"""

    /** Interpreta `{"ok":1,"fan":1,"fs":0}` (`fs` es opcional en firmware antiguo); null si no tiene el formato esperado. */
    fun parseRelayReply(text: String): RelayReply? = try {
        val json = JSONObject(text)
        RelayReply(ok = json.getInt("ok") == 1, fanOn = json.getInt("fan") == 1, failSafe = json.optInt("fs", 0) == 1)
    } catch (_: JSONException) {
        null
    }

    /** Cifra [plain] y arma el frame completo `[len][IV][ct][tag]`. [iv] solo se fija en tests. */
    fun encryptFrame(key: ByteArray, plain: ByteArray, iv: ByteArray = newIv()): ByteArray {
        require(iv.size == IV_LEN) { "El IV debe tener $IV_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
        val ctAndTag = cipher.doFinal(plain) // la JCA devuelve ciphertext || tag, igual que el firmware
        val bodyLen = IV_LEN + ctAndTag.size
        require(bodyLen <= MAX_BODY_LEN) { "Mensaje demasiado largo" }
        return ByteBuffer.allocate(2 + bodyLen)
            .putShort(bodyLen.toShort())
            .put(iv)
            .put(ctAndTag)
            .array()
    }

    /** Descifra el cuerpo `[IV][ct][tag]` (sin los 2 bytes de largo); null si la autenticación falla. */
    fun decryptBody(key: ByteArray, body: ByteArray): ByteArray? {
        if (body.size < MIN_BODY_LEN) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, body, 0, IV_LEN))
            cipher.doFinal(body, IV_LEN, body.size - IV_LEN)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun newIv(): ByteArray = ByteArray(IV_LEN).also(random::nextBytes)

    /** Convierte la llave de `local.properties` (64 caracteres hex = 256 bits) a bytes. */
    fun hexToKey(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == 64 && clean.all { it in "0123456789abcdefABCDEF" }) {
            "aes.key en local.properties debe tener 64 caracteres hexadecimales"
        }
        return ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
