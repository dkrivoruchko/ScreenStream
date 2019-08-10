package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

abstract class HttpServerCoroutineScope(
    protected val onError: (AppError) -> Unit
) : CoroutineScope {

    protected val supervisorJob = SupervisorJob()
    protected val lock = Unit

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    open fun destroy() {
        synchronized(lock) {
            XLog.d(getLog("destroy", "Invoked"))
            supervisorJob.cancelChildren()
        }
    }
}