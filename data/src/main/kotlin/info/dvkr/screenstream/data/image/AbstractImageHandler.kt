package info.dvkr.screenstream.data.image

import androidx.annotation.CallSuper
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getTag
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

abstract class AbstractImageHandler(
    protected val onError: (AppError) -> Unit
) : CoroutineScope {

    private val parentJob: Job = Job()

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Timber.tag(getTag("onCoroutineException")).e(throwable)
            onError(FatalError.CoroutineException)
        }

    abstract fun start()

    @CallSuper
    open fun stop() {
        parentJob.cancel()
    }

}