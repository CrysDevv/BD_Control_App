package cl.avelyn.bd_control_app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import cl.avelyn.bd_control_app.ui.navigation.AppNavigation
import cl.avelyn.bd_control_app.ui.theme.BdControlAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as BdApplication).container
    private var deepLinkUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deepLinkUri = intent?.dataString

        lifecycleScope.launch { container.sensorRepository.purgeOlderThan() }

        setContent {
            BdControlAppTheme {
                AppNavigation(deepLinkUri = deepLinkUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkUri = intent.dataString
    }
}
