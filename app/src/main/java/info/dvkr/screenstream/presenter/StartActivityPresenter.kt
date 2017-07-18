package info.dvkr.screenstream.presenter

import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.GlobalStatus
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.ui.StartActivityView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject

@PersistentScope
class StartActivityPresenter @Inject internal constructor(private val eventScheduler: Scheduler,
                                                          private val eventBus: EventBus,
                                                          private val globalStatus: GlobalStatus) {
    private val TAG = "StartActivityPresenter"

    private val subscriptions = CompositeSubscription()
    private var startActivity: StartActivityView? = null

    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()

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
            // Sending message to StartActivity
                is StartActivityView.FromEvent.TryStartStream -> {
                    if (globalStatus.isStreamRunning) throw IllegalStateException("Stream already running")
                    startActivity?.toEvent(StartActivityView.ToEvent.TryToStart())
                }

            // Relaying message to ForegroundServicePresenter
                is StartActivityView.FromEvent.StopStream -> {
                    if (!globalStatus.isStreamRunning) throw IllegalStateException("Stream not running")
                    eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                }

            // Relaying message to ForegroundServicePresenter
                is StartActivityView.FromEvent.AppExit -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.AppExit())
                }

            // Getting current Error
                is StartActivityView.FromEvent.GetError -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.Error(globalStatus.error))
                }

            // Getting current traffic history
                is StartActivityView.FromEvent.TrafficHistoryRequest -> {
                    // Requesting current traffic history
                    if (trafficHistory.isEmpty()) {
                        eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistoryRequest())
                    } else {
                        startActivity?.toEvent(StartActivityView.ToEvent.TrafficHistory(trafficHistory.toList()))
                    }
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent IGNORED")
            }
        })

        // Global events
        subscriptions.add(eventBus.getEvent().observeOn(eventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")

            when (globalEvent) {
            // From ImageGeneratorImpl
                is EventBus.GlobalEvent.StreamStatus -> {
                    startActivity?.toEvent(StartActivityView.ToEvent.StreamStartStop(globalStatus.isStreamRunning))
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

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficHistory -> {
                    trafficHistory.clear()
                    trafficHistory.addAll(globalEvent.trafficHistory)
                    startActivity?.toEvent(StartActivityView.ToEvent.TrafficHistory(trafficHistory.toList()))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficPoint -> {
                    if (trafficHistory.isNotEmpty()) trafficHistory.removeFirst()
                    trafficHistory.addLast(globalEvent.trafficPoint)
                    startActivity?.toEvent(StartActivityView.ToEvent.TrafficPoint(globalEvent.trafficPoint))
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $globalEvent IGNORED")
            }
        })

        // Sending current stream status
        startActivity?.toEvent(StartActivityView.ToEvent.StreamRunning(globalStatus.isStreamRunning))

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())

        // Requesting current interfaces
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfacesRequest())
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