package info.dvkr.screenstream.presenter

import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.ui.StartActivityView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@PersistentScope
class StartActivityPresenter @Inject internal constructor(private val eventScheduler: Scheduler,
                                                          private val eventBus: EventBus) {
    private val TAG = "StartActivityPresenter"

    private val isStreamRunning: AtomicBoolean = AtomicBoolean(false)
    private val subscriptions = CompositeSubscription()
    private var startActivity: StartActivityView? = null

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(activity: StartActivityView) {
        println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        startActivity?.let { detach() }
        startActivity = activity

        // Events from StartActivity
        subscriptions.add(startActivity?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")
            when (fromEvent) {
                is StartActivityView.FromEvent.TryStartStream -> { // Sending message to StartActivity
                    if (isStreamRunning.get()) throw IllegalStateException("Stream already running")
                    startActivity?.toEvent(StartActivityView.ToEvent.TryToStart())
                }

                is StartActivityView.FromEvent.StopStream -> { // Relaying message to ForegroundServicePresenter
                    if (!isStreamRunning.get()) throw IllegalStateException("Stream not running")
                    eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                }

                is StartActivityView.FromEvent.AppExit -> { // Relaying message to ForegroundServicePresenter
                    eventBus.sendEvent(EventBus.GlobalEvent.AppExit())
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent IGNORED")
            }
        })

        // Global events
        subscriptions.add(eventBus.getEvent().observeOn(eventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")

            when (globalEvent) {
            // From ImageGeneratorImpl & ForegroundServicePresenter TODo Do mot minimise
                is EventBus.GlobalEvent.StreamStatus -> {
                    isStreamRunning.set(globalEvent.isStreamRunning)
                    if (isStreamRunning.get()) startActivity?.toEvent(StartActivityView.ToEvent.StreamStart())
                    else startActivity?.toEvent(StartActivityView.ToEvent.StreamStop())
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.ResizeFactor -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.ResizeFactor(globalEvent.value))
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.EnablePin -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.EnablePin(globalEvent.value))
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.SetPin -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.SetPin(globalEvent.value))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From ForegroundServicePresenter
                is EventBus.GlobalEvent.CurrentInterfaces -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.CurrentInterfaces(globalEvent.interfaceList))
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $globalEvent IGNORED")
            }
        })

        // Requesting current stream status
        eventBus.sendEvent(EventBus.GlobalEvent.StreamStatusRequest())

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())

        // Requesting current interfaces
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfacesRequest())


//        subscriptions.add(mAppEvent.getAppStatus()
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe { statusSet -> startActivity?.onAppStatus(statusSet) }
//        )

    }

    fun detach() {
        println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        subscriptions.clear()
        startActivity = null
    }
}

//        Observable.interval(2000, TimeUnit.MILLISECONDS).skip(1).subscribe(new Action1<Long>() {
//            @Override
//            public void call(final Long aLong) {
//                System.out.println(TAG + " interval");
//                if (mAppEvent.getStreamRunning().getValue()) {
//                    mStartActivity.onStreamStop();
//                } else {
//                    mStartActivity.onTryToStart();
//                }
//            }
//        });