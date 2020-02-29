package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*

abstract class HttpServerCoroutineScope(
    protected val onError: (AppError) -> Unit
) {

    protected val lock = Unit

    protected val coroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }
    )

    open fun destroy() {
        synchronized(lock) {
            XLog.d(getLog("destroy", "Invoked"))
            coroutineScope.cancel()
        }
    }
}