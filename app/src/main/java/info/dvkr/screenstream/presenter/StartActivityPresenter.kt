package info.dvkr.screenstream.presenter

import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.ui.StartActivityView
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription
import javax.inject.Inject

@PersistentScope
class StartActivityPresenter @Inject internal constructor(val mAppEvent: AppEvent) {
    private val TAG = "StartActivityPresenter"
    private val mSubscriptions = CompositeSubscription()
    private var mStartActivity: StartActivityView? = null

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(startActivity: StartActivityView) {
        println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        if (null != mStartActivity) detach()
        mStartActivity = startActivity

        mSubscriptions.add(mStartActivity?.onEvent()?.subscribe { event ->
            println(TAG + ": Thread [${Thread.currentThread().name}] onEvent: " + event.javaClass.simpleName)
            when (event) {
                is StartActivityView.Event.TryStartStream -> { // Sending message to StartActivity
                    if (mAppEvent.getStreamRunning().value) throw IllegalStateException("Stream already running")
                    mStartActivity?.onTryToStart()
                }

                is StartActivityView.Event.StopStream -> { // Relaying message to ForegroundServicePresenter
                    if (!mAppEvent.getStreamRunning().value) throw IllegalStateException("Stream not running")
                    mAppEvent.sendEvent(AppEvent.Event.StopStream())
                }

                is StartActivityView.Event.AppExit ->  // Relaying message to ForegroundServicePresenter
                    mAppEvent.sendEvent(AppEvent.Event.AppExit())
            }
        })

        mSubscriptions.add(mAppEvent.getAppStatus()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { statusSet -> mStartActivity?.onAppStatus(statusSet) }
        )

        mSubscriptions.add(mAppEvent.getStreamRunning()
                .skip(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { isStreamRunning ->
                    if (isStreamRunning) mStartActivity?.onStreamStart() else mStartActivity?.onStreamStop()
                }
        )

        mSubscriptions.add(mAppEvent.onClientEvent()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { clientList -> mStartActivity?.onConnectedClients(clientList) }
        )
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