package cl.avelyn.bd_control_app.model.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modelo DTO para la fila id=1 de la tabla `bodega_status` en Supabase.
 *
 * Esquema:
 * - id: Int (siempre 1)
 * - mode: 'auto' o 'manual' (dueño: APP)
 * - fan_command: Boolean (dueño: APP)
 * - hum_on: Double (dueño: APP)
 * - hum_off: Double (dueño: APP)
 * - fan_state: Boolean (dueño: ESP32)
 * - temp_c: Double (dueño: ESP32)
 * - hum_pct: Double (dueño: ESP32)
 * - uptime_s: Long (dueño: ESP32)
 * - last_seen: String ISO-8601 (trigger BD)
 * - updated_at: String ISO-8601 (trigger BD)
 */
@Serializable
data class BodegaStatusDto(
    @SerialName("id") val id: Int = 1,
    @SerialName("mode") val mode: String? = null,
    @SerialName("fan_command") val fan_command: Boolean? = null,
    @SerialName("hum_on") val hum_on: Double? = null,
    @SerialName("hum_off") val hum_off: Double? = null,
    @SerialName("fan_state") val fan_state: Boolean? = null,
    @SerialName("temp_c") val temp_c: Double? = null,
    @SerialName("hum_pct") val hum_pct: Double? = null,
    @SerialName("uptime_s") val uptime_s: Long? = null,
    @SerialName("last_seen") val last_seen: String? = null,
    @SerialName("updated_at") val updated_at: String? = null,
) {
    /**
     * Si 'mode' es nulo o desconocido, se trata como 'auto'.
     */
    val normalizedMode: String
        get() = if (mode?.equals("manual", ignoreCase = true) == true) "manual" else "auto"

    val isAuto: Boolean
        get() = normalizedMode == "auto"
}
