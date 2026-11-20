package info.dvkr.screenstream.update

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AppUpdate(activity: ComponentActivity) {
    val restartAvailable: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun completeUpdate() {}

    fun dismissUpdate() {}
}
