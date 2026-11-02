package cl.avelyn.bd_control_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import cl.avelyn.bd_control_app.ui.navigation.AppNavigation
import cl.avelyn.bd_control_app.ui.theme.BdControlAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as BdApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch { container.sensorRepository.purgeOlderThan() }

        setContent {
            BdControlAppTheme {
                AppNavigation()
            }
        }
    }
}
