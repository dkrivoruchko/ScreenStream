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
class StartActivityPresenter @Inject internal constructor(private val mEventScheduler: Scheduler,
                                                          private val mEventBus: EventBus) {
    private val TAG = "StartActivityPresenter"

    private val isStreamRunning: AtomicBoolean = AtomicBoolean(false) // TODO Can data from FGSP used ?
    private val mSubscriptions = CompositeSubscription()
    private var mStartActivity: StartActivityView? = null

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(startActivity: StartActivityView) {
        println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        if (null != mStartActivity) detach()
        mStartActivity = startActivity

        // Events from StartActivity
        mSubscriptions.add(mStartActivity?.fromEvent()?.observeOn(mEventScheduler)?.subscribe { fromEvent ->
            println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: ${fromEvent.javaClass.simpleName}")
            when (fromEvent) {
                is StartActivityView.FromEvent.TryStartStream -> { // Sending message to StartActivity
                    if (isStreamRunning.get()) throw IllegalStateException("Stream already running")
                    mStartActivity?.toEvent(StartActivityView.ToEvent.TryToStart())
                }

                is StartActivityView.FromEvent.StopStream -> { // Relaying message to ForegroundServicePresenter
                    if (!isStreamRunning.get()) throw IllegalStateException("Stream not running")
                    mEventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                }

                is StartActivityView.FromEvent.AppExit -> { // Relaying message to ForegroundServicePresenter
                    mEventBus.sendEvent(EventBus.GlobalEvent.AppExit())
                }
            }
        })

        // Global events
        mSubscriptions.add(mEventBus.getEvent().observeOn(mEventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: ${globalEvent.javaClass.simpleName}")

            when (globalEvent) {
            // From ImageGeneratorImpl & ForegroundServicePresenter TODo Do mot minimise
                is EventBus.GlobalEvent.StreamStatus -> {
                    isStreamRunning.set(globalEvent.isStreamRunning)
                    if (isStreamRunning.get()) mStartActivity?.toEvent(StartActivityView.ToEvent.StreamStart())
                    else mStartActivity?.toEvent(StartActivityView.ToEvent.StreamStop())
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.ResizeFactor -> {
                    mStartActivity?.toEvent(StartActivityView.ToEvent.ResizeFactor(globalEvent.value))
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.EnablePin -> {
                    mStartActivity?.toEvent(StartActivityView.ToEvent.EnablePin(globalEvent.value))
                }

            // From SettingsActivityPresenter
                is EventBus.GlobalEvent.SetPin -> {
                    mStartActivity?.toEvent(StartActivityView.ToEvent.SetPin(globalEvent.value))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    mStartActivity?.toEvent(StartActivityView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From ForegroundServicePresenter
                is EventBus.GlobalEvent.CurrentInterfaces -> {
                    mStartActivity?.toEvent(StartActivityView.ToEvent.CurrentInterfaces(globalEvent.interfaceList))
                }
            }
        })

        // Requesting current stream status
        mEventBus.sendEvent(EventBus.GlobalEvent.StreamStatusRequest())

        // Requesting current clients
        mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())

        // Requesting current interfaces
        mEventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfacesRequest())


//        mSubscriptions.add(mAppEvent.getAppStatus()
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe { statusSet -> mStartActivity?.onAppStatus(statusSet) }
//        )

    }

    fun detach() {
        println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        mSubscriptions.clear()
        mStartActivity = null
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