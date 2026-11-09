package cl.avelyn.bd_control_app.di

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import cl.avelyn.bd_control_app.BuildConfig
import cl.avelyn.bd_control_app.model.AppDatabase
import cl.avelyn.bd_control_app.model.network.AuthorizedFanCommandSender
import cl.avelyn.bd_control_app.model.network.AuthorizedSerialSensorPort
import cl.avelyn.bd_control_app.model.network.CompositeSensorDataSource
import cl.avelyn.bd_control_app.model.network.NoSerialPort
import cl.avelyn.bd_control_app.model.network.SerialLink
import cl.avelyn.bd_control_app.model.network.SerialSensorPort
import cl.avelyn.bd_control_app.model.network.UsbSerialPortProvider
import cl.avelyn.bd_control_app.model.network.AuthorizedSensorDataSource
import cl.avelyn.bd_control_app.model.network.FanCommandSender
import cl.avelyn.bd_control_app.model.network.SensorDataSource
import cl.avelyn.bd_control_app.model.network.SupabaseIotBridge
import cl.avelyn.bd_control_app.model.network.WifiGuard
import cl.avelyn.bd_control_app.model.network.WifiIotBridge
import cl.avelyn.bd_control_app.model.network.WifiNetworkProvider
import cl.avelyn.bd_control_app.model.repository.AuthRepository
import cl.avelyn.bd_control_app.model.repository.DeviceAccessRepository
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import cl.avelyn.bd_control_app.model.ActivityAppVisibility
import cl.avelyn.bd_control_app.model.AppVisibility
import cl.avelyn.bd_control_app.model.automation.ControlSettings
import cl.avelyn.bd_control_app.model.automation.SharedPrefsControlSettings
import cl.avelyn.bd_control_app.model.energy.AndroidBatteryReader
import cl.avelyn.bd_control_app.model.energy.EnergyMonitor
import cl.avelyn.bd_control_app.model.security.SessionManager
import cl.avelyn.bd_control_app.model.sync.InternetMonitor
import cl.avelyn.bd_control_app.model.sync.RoomSyncStore
import cl.avelyn.bd_control_app.model.sync.SupabaseReadingsSink
import cl.avelyn.bd_control_app.model.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Contenedor manual de dependencias: una única instancia de cada clase compartida,
 * creada al iniciar la app en [cl.avelyn.bd_control_app.BdApplication] y consumida
 * por los ViewModels a través de [Factories]. Se evita una librería de DI para
 * mantener el arranque simple en un proyecto de este tamaño.
 *
 * El transporte de datos se elige con `iot.transport` en `local.properties`
 * (ver [BuildConfig.IOT_TRANSPORT]):
 * - `wifi` (por defecto): ESP32 reales por UDP/TCP cifrado, vía [WifiIotBridge].
 * - `supabase`: simulador sin hardware, vía [SupabaseIotBridge].
 * Los ViewModels dependen de [SensorDataSource] y [FanCommandSender], nunca de una clase concreta.
 */
class AppContainer(context: Context) {

    private val database = AppDatabase.build(context)
    val sensorRepository = SensorRepository(database.sensorLogDao())

    private val supabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Postgrest)
        install(Realtime)
    }

    /**
     * Sube a Supabase las lecturas guardadas en el teléfono, cuando hay internet. Vive tanto como el proceso de
     * la app: así sigue subiendo aunque cambie la pantalla.
     */
    val syncManager = SyncManager(
        store = RoomSyncStore(database.sensorLogDao()),
        sink = SupabaseReadingsSink(supabaseClient),
        online = InternetMonitor(context).online
    )
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** ¿La app está a la vista? Fuera de ella se deja de escuchar sensores y de decidir (ahorra batería). */
    val visibility: AppVisibility = ActivityAppVisibility(context.applicationContext as Application)

    /** Preferencias del automático y del ahorro de energía; sobreviven al cierre de la app. */
    val controlSettings: ControlSettings = SharedPrefsControlSettings(context)

    /** Mide el consumo de batería del teléfono (una muestra por minuto, solo con la app a la vista). */
    val energyMonitor = EnergyMonitor(AndroidBatteryReader(context), visibility)

    val authRepository = AuthRepository(supabaseClient)
    val deviceAccessRepository = DeviceAccessRepository(supabaseClient)

    /** Usuario con sesión iniciada y sus permisos por dispositivo. Vacío (deniega todo) hasta completar el login. */
    val session = SessionManager()
    private val wifiGuard = WifiGuard(context)

    /** Siempre disponible: lo usa la pantalla del simulador aunque el transporte activo sea Wi-Fi. */
    val supabaseIotBridge = SupabaseIotBridge(supabaseClient, wifiGuard)

    private val wifiIotBridge by lazy {
        WifiIotBridge(WifiNetworkProvider(context, BuildConfig.WIFI_MULTICAST_LOCK), BuildConfig.AES_KEY_HEX)
    }

    private val useSupabase = BuildConfig.IOT_TRANSPORT == "supabase"

    /**
     * Sensor conectado por el cable USB-C (serial). Solo existe en modo Wi-Fi, si `iot.serial` no está
     * en false y el teléfono soporta USB host. Un único enlace compartido por toda la app.
     */
    private val serialLink: SerialLink? by lazy {
        val usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        if (!useSupabase && BuildConfig.SERIAL_ENABLED && usbHost) SerialLink(UsbSerialPortProvider(context)) else null
    }

    private val rawSensorSource: SensorDataSource
        get() = when {
            useSupabase -> supabaseIotBridge
            else -> serialLink?.let { CompositeSensorDataSource(wifiIotBridge, it) } ?: wifiIotBridge
        }

    /** Estado del enlace USB y configuración remota del sensor, con el mismo control de permisos. */
    val serialPort: SerialSensorPort get() = AuthorizedSerialSensorPort(serialLink ?: NoSerialPort, session)

    // Los ViewModels reciben el transporte envuelto en el control de acceso por dispositivo: nunca
    // ven ni comandan un dispositivo para el que el usuario no tenga permiso (ver AuthorizedIot.kt).
    val sensorDataSource: SensorDataSource
        get() = AuthorizedSensorDataSource(rawSensorSource, session)
    val fanCommandSender: FanCommandSender
        get() = AuthorizedFanCommandSender(if (useSupabase) supabaseIotBridge else wifiIotBridge, session)

    init {
        appScope.launch { syncManager.run() }
        appScope.launch { energyMonitor.run() }
    }
}
