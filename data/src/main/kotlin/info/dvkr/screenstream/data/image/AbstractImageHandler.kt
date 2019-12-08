package info.dvkr.screenstream.data.image

import androidx.annotation.CallSuper
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*

abstract class AbstractImageHandler(
    protected val onError: (AppError) -> Unit
) {

    protected val coroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }
    )

    private var isDestroyed: Boolean = false

    @CallSuper
    open fun start() {
        if (isDestroyed) throw IllegalStateException("Handler was destroyed")
    }

    @CallSuper
    open fun destroy() {
        isDestroyed = true
        coroutineScope.coroutineContext.cancel()
    }
}