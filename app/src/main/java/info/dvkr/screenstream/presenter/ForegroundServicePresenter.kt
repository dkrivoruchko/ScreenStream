package info.dvkr.screenstream.presenter


import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.model.httpserver.HttpServerImpl
import info.dvkr.screenstream.service.ForegroundServiceView
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.net.InetSocketAddress
import java.util.*
import javax.inject.Inject

@PersistentScope
class ForegroundServicePresenter @Inject internal constructor(val mSettingsHelper: Settings, val mAppEvent: AppEvent) {
    private val TAG = "ForegroundServicePresenter"
    private val mSubscriptions = CompositeSubscription()
    private val mClientSubscriptions = CompositeSubscription()
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
        mSubscriptions.add(mForegroundService?.onEvent()?.subscribe { event ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] onFGSEvent: ${event.javaClass.simpleName}")

            when (event) {
                is ForegroundServiceView.Event.Init -> {
                    if (mSettingsHelper.enablePin && mSettingsHelper.newPinOnAppStart)
                        mSettingsHelper.currentPin = randomPin()

                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StartHttpServerRequest())
                }

                is ForegroundServiceView.Event.StartHttpServer -> {
                    mClientSubscriptions.clear()
                    mAppEvent.clearClients()
                    mHttpServer?.let { if (it.isRunning()) it.stop() }
                    mHttpServer = null

                    val (favicon, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg) = event

                    mHttpServer = HttpServerImpl(InetSocketAddress(mSettingsHelper.severPort),
                            favicon,
                            baseIndexHtml,
                            mSettingsHelper.htmlBackColor,
                            mSettingsHelper.disableMJPEGCheck,
                            mSettingsHelper.enablePin,
                            mSettingsHelper.currentPin,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            mAppEvent.getJpegBytesStream().asObservable(),
                            Action1 { status ->
                                mAppEvent.sendEvent(AppEvent.Event.AppStatus(status))
                            })

                    mClientSubscriptions.add(
                            mHttpServer?.onClientStatusChange()?.subscribe { clientStatus -> mAppEvent.sendClientEvent(clientStatus) }
                    )

                    mForegroundService?.sendEvent(ForegroundServiceView.Event.Notify(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.Event.StopHttpServer -> {
                    mClientSubscriptions.clear()
                    mAppEvent.clearClients()
                    mHttpServer?.let { if (it.isRunning()) it.stop() }
                    mHttpServer = null
                }

                is ForegroundServiceView.Event.StopStreamComplete -> {
                    if (mSettingsHelper.enablePin && mSettingsHelper.autoChangePin) {
                        mSettingsHelper.currentPin = randomPin()
                        mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    }
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.Notify(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.Event.ScreenOff -> {
                    if (mSettingsHelper.stopOnSleep && mAppEvent.getStreamRunning().value)
                        mForegroundService?.sendEvent(ForegroundServiceView.Event.StopStream(true))
                }
            }
        })

        // Events from App
        mSubscriptions.add(mAppEvent.onEvent().subscribe { event ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] onAppEvent: ${event.javaClass.simpleName}")

            when (event) {
                is AppEvent.Event.StopStream -> { // From StartActivityPresenter
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StopStream(true))
                }

                is AppEvent.Event.HttpServerRestart -> { // From SettingsActivityPresenter, ForegroundServicePresenter
                    val restartReason = event.restartReason
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StopStream(false))
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.Notify(restartReason))
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StopHttpServer(), 1000)
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StartHttpServerRequest(), 1100)
                }

                is AppEvent.Event.AppExit -> { // From StartActivityPresenter
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.AppExit())
                }

                is AppEvent.Event.AppStatus -> { // From ImageGeneratorImpl, HttpServerImpl
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.AppStatus())
                }

                is AppEvent.Event.AppError -> { // From ImageGeneratorImpl
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.StopStream(true))
                    val (exception) = event
                    mForegroundService?.sendEvent(ForegroundServiceView.Event.AppError(exception))
                }
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