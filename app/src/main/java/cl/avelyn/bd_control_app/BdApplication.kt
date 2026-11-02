package cl.avelyn.bd_control_app

import android.app.Application
import cl.avelyn.bd_control_app.di.AppContainer

/**
 * `Application` de la app. Crea el [AppContainer] una sola vez, al arrancar el proceso,
 * antes de que se cree cualquier Activity.
 */
class BdApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
