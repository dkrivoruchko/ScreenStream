package info.dvkr.screenstream.update

import info.dvkr.screenstream.SingleActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

@Factory
internal class AppUpdate(@InjectedParam activity: SingleActivity) {
    internal enum class State { Hidden, Available, Preparing, Downloading, Downloaded, ConfirmInstall, Installing }

    val state: StateFlow<State> = MutableStateFlow(State.Hidden)
    val canInstallInModuleState: Flow<Boolean> = flowOf(false)

    fun requestDownload() {}

    fun requestInstall() {}

    fun confirmInstall() {}

    fun dismissInstallConfirmation() {}
}
