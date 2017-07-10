package info.dvkr.screenstream.presenter


import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.model.httpserver.HttpServerImpl
import info.dvkr.screenstream.service.ForegroundServiceView
import rx.Scheduler
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@PersistentScope
class ForegroundServicePresenter @Inject internal constructor(val mEventScheduler: Scheduler,
                                                              val mEventBus: EventBus,
                                                              val mSettings: Settings) {
    private val TAG = "ForegroundServicePresenter"

    private val isStreamRunning: AtomicBoolean = AtomicBoolean(false)
//    private val mKnownErrors: BehaviorSubject<ForegroundServiceView.KnownErrors> =
//            BehaviorSubject.create(ForegroundServiceView.KnownErrors(false, false))

    private val mSubscriptions = CompositeSubscription()
    private val mRandom = Random()

    private var mForegroundService: ForegroundServiceView? = null
    private var mHttpServer: HttpServer? = null

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(foregroundService: ForegroundServiceView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        mForegroundService?.let { detach() }
        mForegroundService = foregroundService

        // Events from ForegroundService
        mSubscriptions.add(mForegroundService?.fromEvent()?.observeOn(mEventScheduler)?.subscribe { event ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: ${event.javaClass.simpleName}")

            when (event) {
                is ForegroundServiceView.FromEvent.Init -> {
                    if (mSettings.enablePin && mSettings.newPinOnAppStart)
                        mSettings.currentPin = randomPin()

                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StartHttpServer())
                }

                is ForegroundServiceView.FromEvent.StartHttpServer -> {
                    mHttpServer?.stop()
                    mHttpServer = null

                    val (favicon, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg, jpegByteStream) = event

                    mHttpServer = HttpServerImpl(InetSocketAddress(mSettings.severPort),
                            favicon,
                            baseIndexHtml,
                            mSettings.htmlBackColor,
                            mSettings.disableMJPEGCheck,
                            mSettings.enablePin,
                            mSettings.currentPin,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegByteStream,
                            mEventBus,
                            Action1 { status ->
                                //                                mAppEvent.sendEvent(AppStatus.Event.AppStatus(status))
                            })

                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.FromEvent.StopHttpServer -> {
                    mHttpServer?.stop()
                    mHttpServer = null
                }

                is ForegroundServiceView.FromEvent.StopStreamComplete -> {
                    if (mSettings.enablePin && mSettings.autoChangePin) {
                        mSettings.currentPin = randomPin()
                        mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    }
                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.FromEvent.ScreenOff -> {
                    if (mSettings.stopOnSleep && isStreamRunning.get())
                        mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream())
                }

                is ForegroundServiceView.FromEvent.CurrentInterfaces -> {
                    mEventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfaces(event.interfaceList))
                }
            }
        })

        // Global events
        mSubscriptions.add(mEventBus.getEvent().observeOn(mEventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: ${globalEvent.javaClass.simpleName}")

            when (globalEvent) {
            // From StartActivityPresenter
                is EventBus.GlobalEvent.StreamStatusRequest -> {
                    mEventBus.sendEvent(EventBus.GlobalEvent.StreamStatus(isStreamRunning.get()))
                }

            // From ImageGeneratorImpl
                is EventBus.GlobalEvent.StreamStatus -> {
                    isStreamRunning.set(globalEvent.isStreamRunning)
                }

            // From StartActivityPresenter & ProjectionCallback
                is EventBus.GlobalEvent.StopStream -> {
                    if (!isStreamRunning.get()) throw IllegalStateException("WARRING: Stream in not running")
                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream())
                }

            // From StartActivityPresenter
                is EventBus.GlobalEvent.AppExit -> {
                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.AppExit())
                }

            // From SettingsActivityPresenter, ForegroundServicePresenter
                is EventBus.GlobalEvent.HttpServerRestart -> {
                    val restartReason = globalEvent.reason
                    if (isStreamRunning.get())
                        mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream(false))

                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(restartReason))
// TODO                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StopHttpServer(), 1000)
                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.StartHttpServer(), 1000)
                }


                is EventBus.GlobalEvent.CurrentInterfacesRequest -> {
                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.CurrentInterfacesRequest())
                }

            // From ImageGeneratorImpl, HttpServerImpl
//                is AppStatus.Event.AppStatus -> {
//                    mForegroundService?.toEvent(ForegroundServiceView.ToEvent.AppStatus())
//                }

            }
        })
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        mSubscriptions.clear()
        mForegroundService = null
    }

    private fun randomPin(): String =
            Integer.toString(mRandom.nextInt(10)) + Integer.toString(mRandom.nextInt(10)) +
                    Integer.toString(mRandom.nextInt(10)) + Integer.toString(mRandom.nextInt(10))
}