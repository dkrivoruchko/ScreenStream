package info.dvkr.screenstream.common

import android.content.Context
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

public interface StreamingModule {

    public data class AppState(@JvmField public val isBusy: Boolean = true, @JvmField public val isStreaming: Boolean = false)

    public sealed class AppEvent {
        public data object StartStream : AppEvent()
        public data object StopStream : AppEvent()
    }

    public data class Id(@JvmField public val value: String)

    public val id: Id

    public val priority: Int

    public val streamingServiceIsReady: StateFlow<Boolean>

    @MainThread
    public fun getName(context: Context): String

    @MainThread
    public fun getContentDescription(context: Context): String

    @MainThread
    public fun showDescriptionDialog(context: Context, lifecycleOwner: LifecycleOwner)

    @MainThread
    public fun getFragmentClass(): Class<out Fragment>

    @MainThread
    public fun createStreamingService(context: Context)

    @MainThread
    public fun sendEvent(event: AppEvent)

    @MainThread
    public suspend fun destroyStreamingService()
}