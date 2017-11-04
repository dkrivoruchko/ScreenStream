package info.dvkr.screenstream.data.presenter.start

import android.arch.lifecycle.ViewModel
import android.util.Log
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import rx.Scheduler
import rx.subscriptions.CompositeSubscription

class StartPresenter internal constructor(private val eventScheduler: Scheduler,
                                          private val eventBus: EventBus,
                                          private val globalStatus: GlobalStatus) : ViewModel() {
    private val TAG = "StartPresenter"

    private val subscriptions = CompositeSubscription()
    private var startView: StartView? = null

    init {
        if (BuildConfig.DEBUG_MODE) Log.i(TAG, "Thread [${Thread.currentThread().name}] Constructor")
        Crashlytics.log(1, TAG, "Constructor")
    }

    fun attach(view: StartView) {
        if (BuildConfig.DEBUG_MODE) Log.i(TAG, "Thread [${Thread.currentThread().name}] Attach")
        Crashlytics.log(1, TAG, "Attach")

        startView?.let { detach() }
        startView = view

        // Events from StartActivity
        startView?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE) Log.i(TAG, "Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")
            when (fromEvent) {
            // Relaying message to ForegroundPresenter
                is StartView.FromEvent.CurrentInterfacesRequest -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfacesRequest())
                }

            // Sending message to StartActivity
                is StartView.FromEvent.TryStartStream -> {
                    if (globalStatus.isStreamRunning.get()) return@subscribe
                    globalStatus.error.set(null)
                    startView?.toEvent(StartView.ToEvent.TryToStart())
                }

            // Relaying message to ForegroundPresenter
                is StartView.FromEvent.StopStream -> {
                    if (!globalStatus.isStreamRunning.get()) return@subscribe
                    eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                }

            // Relaying message to ForegroundPresenter
                is StartView.FromEvent.AppExit -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.AppExit())
                }

            // Getting  Error
                is StartView.FromEvent.Error -> {
                    globalStatus.error.set(fromEvent.error)
                    startView?.toEvent(StartView.ToEvent.Error(fromEvent.error))
                }

            // Getting current Error
                is StartView.FromEvent.GetError -> {
                    startView?.toEvent(StartView.ToEvent.Error(globalStatus.error.get()))
                }

                else -> Log.e(TAG, "Thread [${Thread.currentThread().name}] fromEvent: $fromEvent WARRING: IGNORED")
            }
        }.also { subscriptions.add(it) }

        // Global events
        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.StreamStatus ||
                    it is EventBus.GlobalEvent.ResizeFactor ||
                    it is EventBus.GlobalEvent.EnablePin ||
                    it is EventBus.GlobalEvent.SetPin ||
                    it is EventBus.GlobalEvent.CurrentClients ||
                    it is EventBus.GlobalEvent.CurrentInterfaces ||
                    it is EventBus.GlobalEvent.TrafficPoint
        }.subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) Log.i(TAG, "Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
            // From ImageGeneratorImpl
                is EventBus.GlobalEvent.StreamStatus -> {
                    startView?.toEvent(StartView.ToEvent.OnStreamStartStop(globalStatus.isStreamRunning.get()))
                }

            // From SettingsPresenter
                is EventBus.GlobalEvent.ResizeFactor -> {
                    startView?.toEvent(StartView.ToEvent.ResizeFactor(globalEvent.value))
                }

            // From SettingsPresenter
                is EventBus.GlobalEvent.EnablePin -> {
                    startView?.toEvent(StartView.ToEvent.EnablePin(globalEvent.value))
                }

            // From SettingsPresenter
                is EventBus.GlobalEvent.SetPin -> {
                    startView?.toEvent(StartView.ToEvent.SetPin(globalEvent.value))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    startView?.toEvent(StartView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From ForegroundPresenter
                is EventBus.GlobalEvent.CurrentInterfaces -> {
                    startView?.toEvent(StartView.ToEvent.CurrentInterfaces(globalEvent.interfaceList))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficPoint -> {
                    startView?.toEvent(StartView.ToEvent.TrafficPoint(globalEvent.trafficPoint))
                }
            }
        }.also { subscriptions.add(it) }

        // Sending current stream status
        startView?.toEvent(StartView.ToEvent.StreamRunning(globalStatus.isStreamRunning.get()))

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) Log.i(TAG, "Thread [${Thread.currentThread().name}] Detach")
        Crashlytics.log(1, TAG, "Detach")
        subscriptions.clear()
        startView = null
    }
}