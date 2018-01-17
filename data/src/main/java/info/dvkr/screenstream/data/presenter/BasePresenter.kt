package info.dvkr.screenstream.data.presenter

import android.arch.lifecycle.ViewModel
import android.support.annotation.CallSuper
import android.support.annotation.MainThread
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.SubscriptionReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import timber.log.Timber
import kotlin.coroutines.experimental.CoroutineContext

abstract class BasePresenter<in T : BaseView, R : BaseView.BaseFromEvent>(
        protected val crtContext: CoroutineContext,
        protected val eventBus: EventBus) : ViewModel() {

    protected lateinit var viewChannel: SendChannel<R>
    private lateinit var subscription: SubscriptionReceiveChannel<EventBus.GlobalEvent>
    private var view: T? = null

    init {
        Timber.i("[${Utils.getLogPrefix(this)}] Init")
    }

    @MainThread
    fun offer(fromEvent: R) {
        Timber.d("[${Utils.getLogPrefix(this)}] fromEvent: ${fromEvent.javaClass.simpleName}")
        viewChannel.offer(fromEvent)
    }

    @CallSuper
    open fun attach(newView: T, block: suspend (globalEvent: EventBus.GlobalEvent) -> Unit) {
        Timber.i("[${Utils.getLogPrefix(this)}] Attach")
        view?.let { detach() }
        view = newView

        subscription = eventBus.openSubscription()
        launch(crtContext) {
            subscription.consumeEach { globalEvent ->
                Timber.d("[${Utils.getLogPrefix(this)}] globalEvent: ${globalEvent.javaClass.simpleName}")
                block(globalEvent)
            }
        }
    }

    @CallSuper
    fun detach() {
        Timber.i("[${Utils.getLogPrefix(this)}] Detach")
        subscription.close()
        view = null
    }

    @CallSuper
    override fun onCleared() {
        viewChannel.close()
        super.onCleared()
    }

    protected fun <E : BaseView.BaseToEvent> notifyView(baseToEvent: E) = view?.toEvent(baseToEvent)

}