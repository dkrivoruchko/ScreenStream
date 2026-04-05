package info.dvkr.screenstream.common.module

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import org.koin.core.scope.Scope

@Immutable
public interface StreamingModule {

    @Immutable
    public data class Id(public val value: String)

    @Immutable
    public enum class WindowWidthSizeClass {
        COMPACT,
        MEDIUM,
        EXPANDED,
    }

    public sealed class State {
        public data object Initiated : State()
        public data object PendingStart : State()
        public data class Running(public val scope: Scope) : State()
        public data object PendingStop : State()
    }

    public val id: Id

    public val priority: Int

    public val isRunning: Flow<Boolean>

    public val isStreaming: Flow<Boolean>

    public val hasActiveConsumer: Flow<Boolean>

    @get:StringRes
    public val nameResource: Int

    @get:StringRes
    public val descriptionResource: Int

    @get:StringRes
    public val detailsResource: Int

    @Composable
    public fun StreamUIContent(windowWidthSizeClass: WindowWidthSizeClass, modifier: Modifier)

    @MainThread
    public fun startModule(context: Context)

    @MainThread
    public suspend fun stopModule()

    @MainThread
    public fun stopStream(reason: String)
}
