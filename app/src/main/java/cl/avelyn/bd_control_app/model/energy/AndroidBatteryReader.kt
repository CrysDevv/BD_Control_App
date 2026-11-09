package cl.avelyn.bd_control_app.model.energy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Lee la batería con `BatteryManager`. El nivel, si carga y la temperatura son confiables. La corriente instantánea
 * (`BATTERY_PROPERTY_CURRENT_NOW`) NO: cada fabricante la informa distinto (µA o mA, con un signo u otro, o no la
 * informa), así que se muestra solo como dato indicativo y el ritmo de descarga se calcula del nivel.
 */
class AndroidBatteryReader(context: Context) : BatteryReader {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BatteryManager::class.java)

    override fun read(nowMs: Long): BatterySample {
        // El broadcast de la batería es "pegajoso": con receptor null devuelve el último valor sin registrar nada.
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val raw = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = when {
            raw == Int.MIN_VALUE || raw == 0 -> null
            abs(raw) >= 20_000 -> raw / 1000   // lo normal: microamperios
            else -> raw                        // algunos teléfonos ya la informan en miliamperios
        }
        return BatterySample(
            timeMs = nowMs,
            levelPct = pct,
            charging = charging,
            currentMa = currentMa,
            temperatureC = if (tempTenths == Int.MIN_VALUE) null else tempTenths / 10f
        )
    }
}
