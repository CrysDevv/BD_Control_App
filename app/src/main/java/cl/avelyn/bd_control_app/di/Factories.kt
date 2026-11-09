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
        AuthViewModel(container.authRepository, container.deviceAccessRepository, container.session)
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        DashboardViewModel(
            sensorDataSource = container.sensorDataSource,
            fanCommandSender = container.fanCommandSender,
            sensorRepository = container.sensorRepository,
            session = container.session,
            serialPort = container.serialPort,
            syncStatus = container.syncManager.status,
            settings = container.controlSettings,
            visibility = container.visibility,
            energy = container.energyMonitor.report
        )
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        HistoryViewModel(container.sensorRepository, container.session)
    }
    initializer {
        val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BdApplication).container
        SimulatorViewModel(container.supabaseIotBridge)
    }
}
