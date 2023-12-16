package info.dvkr.screenstream.common

import android.content.Context
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

public interface StreamingModule {

    public data class AppState(@JvmField public val isBusy: Boolean = true, @JvmField public val isStreaming: Boolean = false)

    public open class AppEvent {
        public data object StartStream : AppEvent()
        public data object StopStream : AppEvent()
        public data class Exit(@JvmField public val callback: () -> Unit) : AppEvent()
    }

    public data class Id(@JvmField public val value: String) {
        public companion object {
            @JvmStatic
            public val UNDEFINED: Id = Id("_UNDEFINED_")
        }

        public fun isDefined(): Boolean = this != UNDEFINED
    }

    public val id: Id

    public val priority: Int

    public val streamingServiceIsReady: StateFlow<Boolean>

    @MainThread
    @Throws(IllegalStateException::class)
    public fun getName(context: Context): String

    @MainThread
    @Throws(IllegalStateException::class)
    public fun getContentDescription(context: Context): String

    @MainThread
    @Throws(IllegalStateException::class)
    public fun showDescriptionDialog(context: Context, lifecycleOwner: LifecycleOwner)

    @MainThread
    public fun getFragmentClass(): Class<out Fragment>

    @MainThread
    @Throws(IllegalStateException::class)
    public fun createStreamingService(context: Context)

    @MainThread
    @Throws(IllegalStateException::class)
    public fun sendEvent(event: AppEvent)

    @MainThread
    @Throws(IllegalStateException::class)
    public suspend fun destroyStreamingService()
}