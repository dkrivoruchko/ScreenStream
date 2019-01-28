package info.dvkr.screenstream.data.image

import androidx.annotation.CallSuper
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

abstract class AbstractImageHandler(
    protected val onError: (AppError) -> Unit
) : CoroutineScope {

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    abstract fun start()

    @CallSuper
    open fun stop() {
        coroutineContext.cancelChildren()
    }

}