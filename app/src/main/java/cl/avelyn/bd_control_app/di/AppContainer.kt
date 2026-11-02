package cl.avelyn.bd_control_app.di

import android.content.Context
import cl.avelyn.bd_control_app.BuildConfig
import cl.avelyn.bd_control_app.model.AppDatabase
import cl.avelyn.bd_control_app.model.network.SupabaseIotBridge
import cl.avelyn.bd_control_app.model.network.WifiGuard
import cl.avelyn.bd_control_app.model.repository.AuthRepository
import cl.avelyn.bd_control_app.model.repository.SensorRepository
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Contenedor manual de dependencias: una única instancia de cada clase compartida,
 * creada al iniciar la app en [cl.avelyn.bd_control_app.BdApplication] y consumida
 * por los ViewModels a través de [Factories]. Se evita una librería de DI para
 * mantener el arranque simple en un proyecto de este tamaño.
 *
 * [supabaseIotBridge] es la fuente de datos activa mientras no se cuenta con el ESP32
 * real: implementa el mismo contrato (SensorDataSource, FanCommandSender) que tendrían
 * las clases Wi-Fi/UDP-TCP del ESP32 real. Cuando llegue el hardware, el cambio es
 * reemplazar esta línea por esas clases; ningún ViewModel ni pantalla necesita tocarse
 * porque dependen de la interfaz, no de esta clase.
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

    val authRepository = AuthRepository(supabaseClient)
    private val wifiGuard = WifiGuard(context)
    val supabaseIotBridge = SupabaseIotBridge(supabaseClient, wifiGuard)
}
