package info.dvkr.screenstream.data.httpserver

import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getTag
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

abstract class HttpServerCoroutineScope(
    protected val onError: (AppError) -> Unit
) : CoroutineScope {

    protected val parentJob: Job = Job()

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Timber.tag(getTag("onCoroutineException")).e(throwable)
            onError(FatalError.CoroutineException)
        }

    open fun destroy() {
        Timber.tag(getTag("destroy")).d("Invoked")
        parentJob.cancel()
    }
}