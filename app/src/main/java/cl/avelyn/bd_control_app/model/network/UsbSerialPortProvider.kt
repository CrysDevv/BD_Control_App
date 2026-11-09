package cl.avelyn.bd_control_app.model.network

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Abre el puerto serie de la placa ESP32 conectada por el cable USB-C (USB host / OTG) usando la
 * librería `usb-serial-for-android`, que reconoce los conversores USB-serie que traen las placas
 * ESP32 (CP210x, CH340/CH341, CH9102/CH343) y el USB nativo de los ESP32-S2/S3/C3 (CDC-ACM).
 *
 * Parámetros fijos: 115200 baudios, 8 bits, sin paridad, 1 bit de parada (8N1), igual que el
 * firmware. Maneja el permiso USB de Android: la primera vez aparece un diálogo; mientras el
 * usuario no lo acepte se devuelve [PortOpenResult.NeedsPermission] y [SerialLink] vuelve a
 * preguntar cada pocos segundos, así que al aceptarlo conecta solo.
 */
class UsbSerialPortProvider(context: Context) : SerialPortProvider {

    private val appContext = context.applicationContext
    private val usb: UsbManager = appContext.getSystemService(UsbManager::class.java)

    /** Dispositivo para el que ya se mostró el diálogo de permiso, para no repetirlo en cada intento. */
    private var permissionRequestedFor: String? = null

    override fun open(): PortOpenResult {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        if (driver == null) {
            permissionRequestedFor = null
            return PortOpenResult.NoDevice
        }
        val device = driver.device
        val label = describe(device)

        if (!usb.hasPermission(device)) {
            if (permissionRequestedFor != device.deviceName) {
                permissionRequestedFor = device.deviceName
                usb.requestPermission(device, permissionIntent())
            }
            return PortOpenResult.NeedsPermission(label)
        }

        val connection = usb.openDevice(device) ?: return PortOpenResult.Failed("No se pudo abrir el dispositivo USB")
        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            return PortOpenResult.Failed("El dispositivo USB no tiene puerto serie")
        }
        return try {
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // En las placas ESP32 las líneas DTR y RTS controlan EN (reset) e IO0 (modo de programación).
            // Se dejan en reposo (falsas) para que el ESP32 arranque normal y no entre al modo de descarga.
            port.setDTR(false)
            port.setRTS(false)
            PortOpenResult.Opened(UsbPort(usb, device, port), label)
        } catch (e: IOException) {
            runCatching { port.close() }
            PortOpenResult.Failed(e.message ?: "No se pudo configurar el puerto serie")
        }
    }

    private fun describe(device: UsbDevice): String {
        // Desde Android 10 el nombre del producto exige tener ya el permiso USB; antes de eso se muestra VID:PID.
        val name = runCatching { device.productName }.getOrNull()
        val ids = "%04X:%04X".format(device.vendorId, device.productId)
        return if (name.isNullOrBlank()) ids else "$name ($ids)"
    }

    private fun permissionIntent(): PendingIntent {
        // Android 12+ exige declarar la mutabilidad y el sistema necesita poder rellenar el resultado
        // (FLAG_MUTABLE). Android 14+ exige que ese intent sea explícito (setPackage).
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    private class UsbPort(
        private val usb: UsbManager,
        private val device: UsbDevice,
        private val port: UsbSerialPort
    ) : SerialPortIo {
        override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)
        override fun write(data: ByteArray, timeoutMs: Int) = port.write(data, timeoutMs)
        override fun isAlive(): Boolean = usb.deviceList.containsKey(device.deviceName)
        override fun close() { runCatching { port.close() } }
    }

    private companion object {
        const val BAUD_RATE = 115_200
        const val ACTION_USB_PERMISSION = "cl.avelyn.bd_control_app.USB_PERMISSION"
    }
}
