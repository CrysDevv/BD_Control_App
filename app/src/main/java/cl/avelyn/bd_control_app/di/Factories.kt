package cl.avelyn.bd_control_app.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cl.avelyn.bd_control_app.BdApplication
import cl.avelyn.bd_control_app.viewmodel.AuthViewModel
import cl.avelyn.bd_control_app.viewmodel.DashboardViewModel
import cl.avelyn.bd_control_app.viewmodel.HistoryViewModel
import cl.avelyn.bd_control_app.viewmodel.SimulatorViewModel

/**
 * Fábricas de ViewModel que toman sus dependencias del [AppContainer] de [BdApplication].
 * Uso en un Composable: `viewModel(factory = Factories)`.
 */
val Factories = viewModelFactory {
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        AuthViewModel(container.authRepository)
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        DashboardViewModel(
            sensorDataSource = container.supabaseIotBridge,
            fanCommandSender = container.supabaseIotBridge,
            sensorRepository = container.sensorRepository
        )
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        HistoryViewModel(container.sensorRepository)
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        SimulatorViewModel(container.supabaseIotBridge)
    }
}
