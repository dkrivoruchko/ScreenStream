package info.dvkr.screenstream.data.presenter

import android.arch.lifecycle.ViewModel
import android.support.annotation.CallSuper
import android.support.annotation.MainThread
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import timber.log.Timber

abstract class BasePresenter<in T : BaseView, R : BaseView.BaseFromEvent>(
    protected val eventBus: EventBus
) : ViewModel() {

    protected lateinit var viewChannel: SendChannel<R>
    private lateinit var subscription: ReceiveChannel<EventBus.GlobalEvent>
    @Volatile private var view: T? = null
    protected val baseJob = Job()

    init {
        Timber.i("[${Utils.getLogPrefix(this)}] Init")
    }

    @MainThread
    fun offer(fromEvent: R) {
        Timber.d("[${Utils.getLogPrefix(this)}] fromEvent: ${fromEvent.javaClass.simpleName}")
        try {
            if (viewChannel.isClosedForSend.not()) viewChannel.offer(fromEvent)
        } catch (t: Throwable) {
            Timber.e(t)
        }
    }

    @MainThread
    @CallSuper
    open fun attach(newView: T, block: suspend (globalEvent: EventBus.GlobalEvent) -> Unit) {
        Timber.i("[${Utils.getLogPrefix(this)}] Attach")
        view?.let { detach() }
        view = newView

        subscription = eventBus.openSubscription()
        launch(CommonPool, parent = baseJob) {
            subscription.consumeEach { globalEvent ->
                Timber.d("[${Utils.getLogPrefix(this)}] globalEvent: ${globalEvent.javaClass.simpleName}")
                block(globalEvent)
            }
        }
    }

    @MainThread
    @CallSuper
    fun detach() {
        Timber.i("[${Utils.getLogPrefix(this)}] Detach")
        subscription.cancel()
        view = null
    }

    @CallSuper
    override fun onCleared() {
        baseJob.cancel()
        viewChannel.close()
        super.onCleared()
    }

    protected fun <E : BaseView.BaseToEvent> notifyView(baseToEvent: E) = view?.toEvent(baseToEvent)

}