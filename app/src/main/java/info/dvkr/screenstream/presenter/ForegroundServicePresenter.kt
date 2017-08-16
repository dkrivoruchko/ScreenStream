package info.dvkr.screenstream.presenter


import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.*
import info.dvkr.screenstream.model.httpserver.HttpServerImpl
import info.dvkr.screenstream.service.ForegroundServiceView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.net.InetSocketAddress
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList

@PersistentScope
class ForegroundServicePresenter @Inject internal constructor(private val settings: Settings,
                                                              private val eventScheduler: Scheduler,
                                                              private val eventBus: EventBus,
                                                              private val globalStatus: GlobalStatus) {
    private val TAG = "ForegroundServicePresenter"

    private val subscriptions = CompositeSubscription()
    private val random = Random()

    private var foregroundService: ForegroundServiceView? = null
    private var httpServer: HttpServer? = null
    private val slowConnections: MutableList<HttpServer.Client> = ArrayList()

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(service: ForegroundServiceView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        foregroundService?.let { detach() }
        foregroundService = service

        // Events from ForegroundService
        subscriptions.add(foregroundService?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: ${fromEvent.javaClass.simpleName}")

            when (fromEvent) {
                is ForegroundServiceView.FromEvent.Init -> {
                    if (settings.enablePin && settings.newPinOnAppStart) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.sendEvent(EventBus.GlobalEvent.SetPin(newPin))
                    }

                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.StartHttpServer())
                }

                is ForegroundServiceView.FromEvent.StartHttpServer -> {
                    httpServer?.stop()
                    httpServer = null

                    val (favicon, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg, jpegByteStream) = fromEvent

                    globalStatus.error = null
                    httpServer = HttpServerImpl(InetSocketAddress(settings.severPort),
                            favicon,
                            baseIndexHtml,
                            settings.htmlBackColor,
                            settings.disableMJPEGCheck,
                            settings.enablePin,
                            settings.currentPin,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegByteStream,
                            eventBus,
                            eventScheduler)

                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.FromEvent.StopHttpServer -> {
                    httpServer?.stop()
                    httpServer = null
                }

                is ForegroundServiceView.FromEvent.StopStreamComplete -> {
                    if (settings.enablePin && settings.autoChangePin) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.sendEvent(EventBus.GlobalEvent.SetPin(newPin))
                        eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    }
                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is ForegroundServiceView.FromEvent.ScreenOff -> {
                    if (settings.stopOnSleep && globalStatus.isStreamRunning)
                        foregroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream())
                }

                is ForegroundServiceView.FromEvent.CurrentInterfaces -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfaces(fromEvent.interfaceList))
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent WARRING: IGNORED")
            }
        })

        // Global events
        subscriptions.add(eventBus.getEvent().observeOn(eventScheduler)
                .filter {
                    it is EventBus.GlobalEvent.StopStream ||
                            it is EventBus.GlobalEvent.AppExit ||
                            it is EventBus.GlobalEvent.HttpServerRestart ||
                            it is EventBus.GlobalEvent.CurrentInterfacesRequest ||
                            it is EventBus.GlobalEvent.Error ||
                            it is EventBus.GlobalEvent.CurrentClients
                }.subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
            // From StartActivityPresenter & ProjectionCallback
                is EventBus.GlobalEvent.StopStream -> {
                    if (!globalStatus.isStreamRunning) throw IllegalStateException("WARRING: Stream in not running")
                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream())
                }

            // From StartActivityPresenter
                is EventBus.GlobalEvent.AppExit -> {
                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.AppExit())
                }

            // From SettingsActivityPresenter, ForegroundServicePresenter
                is EventBus.GlobalEvent.HttpServerRestart -> {
                    val restartReason = globalEvent.reason
                    if (globalStatus.isStreamRunning)
                        foregroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream(false))

                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.NotifyImage(restartReason))
                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.StartHttpServer(), 1000)
                }

            // From StartActivityPresenter
                is EventBus.GlobalEvent.CurrentInterfacesRequest -> {
                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.CurrentInterfacesRequest())
                }

            // From HttpServer & ImageGenerator
                is EventBus.GlobalEvent.Error -> {
                    if (globalStatus.isStreamRunning)
                        foregroundService?.toEvent(ForegroundServiceView.ToEvent.StopStream(false))

                    foregroundService?.toEvent(ForegroundServiceView.ToEvent.Error(globalEvent.error))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    val currentSlowConnections = globalEvent.clientsList.filter { it.hasBackpressure }.toList()
                    if (!slowConnections.containsAll(currentSlowConnections))
                        foregroundService?.toEvent(ForegroundServiceView.ToEvent.SlowConnectionDetected())
                    slowConnections.clear()
                    slowConnections.addAll(currentSlowConnections)
                }
            }
        })
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        subscriptions.clear()
        foregroundService = null
    }

    private fun randomPin(): String =
            Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10)) +
                    Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10))
}