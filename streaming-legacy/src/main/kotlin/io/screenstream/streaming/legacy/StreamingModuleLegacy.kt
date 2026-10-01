package io.screenstream.streaming.legacy

import android.app.BackgroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.Flow
import org.koin.core.scope.Scope

@Immutable
public interface StreamingModuleLegacy {

    public sealed class State {
        public data object Initiated : State()
        public data object PendingStart : State()
        public data class Running(public val scope: Scope) : State()
        public data object PendingStop : State()
    }

    public val id: StreamingModule.Id

    public val priority: Int

    public val isRunning: Flow<Boolean>

    public val isStreaming: Flow<Boolean>

    public val hasActiveConsumer: Flow<Boolean>

    public val requiresLocalNetworkPermission: Boolean
        get() = false

    @get:StringRes
    public val nameResource: Int

    @get:StringRes
    public val descriptionResource: Int

    @get:StringRes
    public val detailsResource: Int

    @Composable
    public fun StreamUIContent(windowSizeClass: WindowSizeClass, modifier: Modifier)

    @MainThread
    public fun startModule(context: Context)

    @MainThread
    public suspend fun stopModule()

    @MainThread
    public fun stopStream(reason: String)

    @MainThread
    public fun recoverError(): Unit = Unit

    public class StartBlockedException(
        public val moduleId: StreamingModule.Id,
        public val importance: Int,
        cause: Throwable
    ) : IllegalStateException(
        "Service start blocked for module $moduleId, importance=$importance: ${cause.javaClass.simpleName}: ${cause.message}",
        cause
    )
}

public fun Throwable.isStreamingModuleStartBlocked(): Boolean =
    when {
        this is StreamingModuleLegacy.StartBlockedException -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> this is BackgroundServiceStartNotAllowedException
        this is IllegalStateException -> message?.contains("Not allowed to start service", ignoreCase = true) == true
        else -> false
    }
