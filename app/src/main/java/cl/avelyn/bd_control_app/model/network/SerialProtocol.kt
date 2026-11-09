package cl.avelyn.bd_control_app.model.network

/**
 * Protocolo serial (USB) entre la app y el ESP32 del sensor. Es la contraparte en Kotlin de
 * `firmware/sensor/bd_serial.h` (ahí está la especificación completa de cada trama). Código puro,
 * sin Android, para poder probarlo contra el firmware real.
 *
 * Una trama es una línea de texto ASCII: `$<TIPO>,<campo>,...*<CRC16>\n`, donde CRC16 es
 * CRC-16/CCITT-FALSE en 4 dígitos hexadecimales MAYÚSCULAS de lo que hay entre `$` y `*`.
 */
object SerialProtocol {

    /** Largo máximo de una línea (sin el salto de línea); las más largas se descartan. */
    const val MAX_LINE = 120

    /** Los contadores y el bootId del sensor son enteros de 32 bits sin signo. */
    const val MAX_U32 = 0xFFFFFFFFL

    const val MIN_INTERVAL_MS = 1_000
    const val MAX_INTERVAL_MS = 600_000

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). Referencia: `"123456789"` -> 0x29B1. */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** Arma `$<body>*CRC\n`, listo para escribir en el puerto. */
    fun encode(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.US_ASCII)
        require(bytes.size + 7 <= MAX_LINE) { "Trama demasiado larga" }
        return "\$$body*${"%04X".format(crc16(bytes))}\n".toByteArray(Charsets.US_ASCII)
    }

    // ---------- Comandos app -> ESP32 (seq: 1..65535, el ESP32 lo devuelve en su respuesta) ----------

    fun ping(seq: Int) = encode("PING,${checkSeq(seq)}")
    fun info(seq: Int) = encode("INFO,${checkSeq(seq)}")
    fun get(seq: Int) = encode("GET,${checkSeq(seq)}")

    /** Pide al sensor que reenvíe las lecturas que guardó y aún no se han confirmado. */
    fun sync(seq: Int) = encode("SYNC,${checkSeq(seq)}")

    /** "Ya guardé todas las lecturas hasta [n]": el sensor las libera de su buffer. No tiene respuesta. */
    fun conf(seq: Int, n: Long): ByteArray {
        require(n in 0..MAX_U32) { "n fuera de rango" }
        return encode("CONF,${checkSeq(seq)},$n")
    }
    fun setInterval(seq: Int, intervalMs: Int): ByteArray {
        require(intervalMs in MIN_INTERVAL_MS..MAX_INTERVAL_MS) { "Intervalo fuera de rango" }
        return encode("SET,${checkSeq(seq)},INTERVAL,$intervalMs")
    }

    private fun checkSeq(seq: Int): Int {
        require(seq in 1..65535) { "La secuencia debe estar entre 1 y 65535" }
        return seq
    }

    // ---------- Lectura de líneas ----------

    /** Resultado de interpretar una línea recibida. */
    sealed interface Parsed {
        /** Trama íntegra (CRC correcto). */
        class Valid(val type: String, val fields: List<String>) : Parsed

        /** Parecía una trama (empieza con `$`) pero está dañada: formato o CRC incorrecto. */
        data object Corrupt : Parsed

        /** No es una trama (por ejemplo el log de arranque del ESP32): se ignora sin contarla como error. */
        data object Noise : Parsed
    }

    /** [line] sin el salto de línea final; un `\r` al final se tolera. */
    fun parseLine(line: String): Parsed {
        val text = line.trimEnd('\r')
        if (!text.startsWith("$")) return Parsed.Noise
        if (text.length < 7 || text[text.length - 5] != '*') return Parsed.Corrupt
        val crcText = text.substring(text.length - 4)
        // Solo mayúsculas, igual que el firmware: así cualquier cambio de un bit se detecta.
        if (!crcText.all { it in '0'..'9' || it in 'A'..'F' }) return Parsed.Corrupt
        val body = text.substring(1, text.length - 5)
        if (!body.all { it.code in 0x20..0x7E }) return Parsed.Corrupt
        if (crc16(body.toByteArray(Charsets.US_ASCII)) != crcText.toInt(16)) return Parsed.Corrupt
        val parts = body.split(',')
        return Parsed.Valid(parts.first(), parts.drop(1))
    }

    // ---------- Mensajes ESP32 -> app ----------

    sealed interface Message {
        /** Saludo (al arrancar con [seq] = 0, o respuesta a `INFO`). [bootId] cambia cada vez que el sensor se reinicia. */
        data class Hello(val seq: Int, val deviceId: String, val version: String, val intervalMs: Int, val bootId: Long) : Message

        /** Lectura en vivo; ([bootId], [n]) la identifica de forma única. */
        data class Telemetry(val deviceId: String, val bootId: Long, val n: Long, val tempC: Float, val humPct: Float) : Message

        /** Lectura recuperada del buffer del sensor; [ageMs] = hace cuánto se midió (el sensor no tiene reloj). */
        data class Buffered(
            val deviceId: String, val bootId: Long, val n: Long, val ageMs: Long, val tempC: Float, val humPct: Float
        ) : Message

        /** Comienzo de una repetición: cuántas lecturas hay [pending], la [capacity] del buffer, las [dropped] por desborde y la [oldest] n. */
        data class SyncStart(
            val seq: Int, val pending: Int, val capacity: Int, val dropped: Long, val bootId: Long, val oldest: Long
        ) : Message

        data class SyncEnd(val seq: Int, val count: Int) : Message
        data class Pong(val seq: Int) : Message
        data class Ack(val seq: Int) : Message
        data class Err(val seq: Int, val code: String) : Message
    }

    /** Convierte una trama íntegra en un mensaje; null si el contenido no tiene el formato esperado. */
    fun decode(frame: Parsed.Valid): Message? {
        val f = frame.fields
        return try {
            when (frame.type) {
                "HELLO" -> if (f.size == 5 && WireProtocol.isValidDeviceId(f[1])) {
                    Message.Hello(f[0].toSeq(allowZero = true), f[1], f[2], f[3].toInt(), f[4].toU32())
                } else null
                "TLM" -> if (f.size == 5 && WireProtocol.isValidDeviceId(f[0])) {
                    val t = f[3].toDouble()
                    val h = f[4].toDouble()
                    if (WireProtocol.isPlausibleReading(t, h)) Message.Telemetry(f[0], f[1].toU32(), f[2].toU32(min = 1), t.toFloat(), h.toFloat()) else null
                } else null
                "BUF" -> if (f.size == 6 && WireProtocol.isValidDeviceId(f[0])) {
                    val t = f[4].toDouble()
                    val h = f[5].toDouble()
                    if (WireProtocol.isPlausibleReading(t, h)) {
                        Message.Buffered(f[0], f[1].toU32(), f[2].toU32(min = 1), f[3].toU32(), t.toFloat(), h.toFloat())
                    } else null
                } else null
                "SYNCSTART" -> if (f.size == 6) {
                    Message.SyncStart(f[0].toSeq(), f[1].toInt(), f[2].toInt(), f[3].toU32(), f[4].toU32(), f[5].toU32(min = 1))
                } else null
                "SYNCEND" -> if (f.size == 2) Message.SyncEnd(f[0].toSeq(), f[1].toInt()) else null
                "PONG" -> if (f.size == 1) Message.Pong(f[0].toSeq()) else null
                "ACK" -> if (f.size == 1) Message.Ack(f[0].toSeq()) else null
                "ERR" -> if (f.size == 2) Message.Err(f[0].toSeq(allowZero = true), f[1]) else null
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Entero de 32 bits sin signo en decimal estricto (sin signo, sin espacios). */
    private fun String.toU32(min: Long = 0): Long {
        if (isEmpty() || length > 10 || !all { it in '0'..'9' }) throw NumberFormatException("no es un entero sin signo")
        val v = toLong()
        if (v !in min..MAX_U32) throw NumberFormatException("fuera de rango")
        return v
    }

    private fun String.toSeq(allowZero: Boolean = false): Int {
        val v = toInt()
        if (v !in (if (allowZero) 0 else 1)..65535) throw NumberFormatException("seq fuera de rango")
        return v
    }
}
