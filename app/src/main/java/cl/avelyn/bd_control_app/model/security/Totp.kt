package cl.avelyn.bd_control_app.model.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Contraseña de un solo uso basada en tiempo (TOTP, RFC 6238), el "segundo paso" del login.
 * Compatible con Google Authenticator, Authy, Microsoft Authenticator, etc.: HMAC-SHA1,
 * 6 dígitos, ventana de 30 segundos. Código puro, sin Android, para poder probarlo con
 * los vectores oficiales del RFC.
 */
object Totp {

    const val STEP_SECONDS = 30L
    const val DIGITS = 6

    /** Se aceptan códigos del paso anterior y del siguiente, para tolerar relojes desfasados ~30 s. */
    const val DEFAULT_WINDOW = 1

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun stepFor(epochMillis: Long): Long = epochMillis / 1000L / STEP_SECONDS

    /** Código de [digits] dígitos para el paso de tiempo [step] (RFC 4226, truncamiento dinámico). */
    fun generate(secret: ByteArray, step: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * Verifica [code] en la ventana alrededor de [nowMillis].
     *
     * @param lastUsedStep último paso ya aceptado para este usuario: los pasos iguales o menores
     *   se rechazan, para que un código observado no pueda reutilizarse dentro de su ventana.
     * @return el paso de tiempo que coincidió (guárdalo como nuevo `lastUsedStep`), o null si no coincide.
     */
    fun verify(
        secret: ByteArray,
        code: String,
        nowMillis: Long,
        window: Int = DEFAULT_WINDOW,
        lastUsedStep: Long = -1L
    ): Long? {
        if (code.length != DIGITS || !code.all { it in '0'..'9' }) return null
        val current = stepFor(nowMillis)
        var matched: Long? = null
        // Se recorren siempre todos los pasos, sin salir al primer acierto, para no filtrar por tiempo cuál coincidió.
        for (step in (current - window)..(current + window)) {
            val expected = generate(secret, step).toByteArray()
            if (MessageDigest.isEqual(expected, code.toByteArray()) && step > lastUsedStep) matched = step
        }
        return matched
    }

    /** Decodifica el secreto en Base32 (RFC 4648), el formato que usan las apps de autenticación. */
    fun decodeBase32(text: String): ByteArray {
        val clean = text.filterNot { it == ' ' || it == '-' || it == '=' }.uppercase()
        require(clean.isNotEmpty()) { "Secreto TOTP vacío" }
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in clean) {
            val value = BASE32_ALPHABET.indexOf(c)
            require(value >= 0) { "Secreto TOTP inválido: carácter '$c' no es Base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}
