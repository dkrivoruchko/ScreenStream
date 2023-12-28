package info.dvkr.screenstream.common.module

import android.content.Context
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import info.dvkr.screenstream.common.AppEvent
import kotlinx.coroutines.flow.Flow
import org.koin.core.scope.Scope

public interface StreamingModule {

    public data class Id(public val value: String)

    public sealed class State {
        public data object Initiated : State()
        public data object PendingStart : State()
        public data class Running(public val scope: Scope) : State()
        public data object PendingStop : State()
    }

    public val id: Id

    public val priority: Int

    public val isRunning: Flow<Boolean>

    @MainThread
    public fun getName(context: Context): String

    @MainThread
    public fun getContentDescription(context: Context): String

    @MainThread
    public fun showDescriptionDialog(context: Context, lifecycleOwner: LifecycleOwner)

    @MainThread
    public fun getFragmentClass(): Class<out Fragment>

    @MainThread
    public fun startModule(context: Context)

    @MainThread
    public suspend fun stopModule()

    @MainThread
    public fun sendEvent(event: AppEvent)
}