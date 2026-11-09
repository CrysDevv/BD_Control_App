package cl.avelyn.bd_control_app.model

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cuenta las actividades "iniciadas" (visibles). Con 0 la app está en segundo plano. Al girar la pantalla Android crea la
 * nueva actividad ANTES de detener la vieja, así que la cuenta no pasa por 0 y no se interrumpe el enlace con el sensor.
 */
class ActivityAppVisibility(application: Application) : AppVisibility {

    private val started = AtomicInteger(0)
    private val _isForeground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started.incrementAndGet() == 1) _isForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (started.decrementAndGet() == 0) _isForeground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
