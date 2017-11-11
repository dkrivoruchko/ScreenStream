package info.dvkr.screenstream.data.presenter.foreground


import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.image.ImageGenerator
import info.dvkr.screenstream.data.image.ImageGeneratorImpl
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import info.dvkr.screenstream.domain.httpserver.HttpServerImpl
import info.dvkr.screenstream.domain.settings.Settings
import rx.Scheduler
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import timber.log.Timber

class FgPresenter constructor(private val settings: Settings,
                              private val eventScheduler: Scheduler,
                              private val eventBus: EventBus,
                              private val globalStatus: GlobalStatus,
                              private val jpegBytesStream: BehaviorRelay<ByteArray>) {

    private val subscriptions = CompositeSubscription()
    private val random = java.util.Random()

    private var fgView: FgView? = null
    private var httpServer: HttpServer? = null
    private var imageGenerator: ImageGenerator? = null
    private val slowConnections: MutableList<HttpServer.Client> = ArrayList()

    init {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Init")
    }

    fun attach(view: FgView) {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Attach")

        fgView?.let { detach() }
        fgView = view

        // Events from ForegroundService
        fgView?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] fromEvent: $fromEvent")

            when (fromEvent) {
                is FgView.FromEvent.Init -> {
                    if (settings.enablePin && settings.newPinOnAppStart) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.sendEvent(EventBus.GlobalEvent.SetPin(newPin))
                    }

                    fgView?.toEvent(FgView.ToEvent.StartHttpServer)
                }

                is FgView.FromEvent.StartHttpServer -> {
                    val (serverAddress, favicon, logo, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg, jpegByteStream) = fromEvent
                    globalStatus.error.set(null)
                    httpServer = HttpServerImpl(serverAddress,
                            favicon,
                            logo,
                            baseIndexHtml,
                            settings.htmlBackColor,
                            settings.disableMJPEGCheck,
                            settings.enablePin,
                            settings.currentPin,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegByteStream,
                            eventBus,
                            eventScheduler,
                            Action1 { Timber.i(it) })

                    fgView?.toEvent(FgView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is FgView.FromEvent.StopHttpServer -> {
                    httpServer?.stop()
                    httpServer = null
                }

                is FgView.FromEvent.StartImageGenerator -> {
                    imageGenerator = ImageGeneratorImpl.create(fromEvent.display, fromEvent.mediaProjection, eventScheduler) { igEvent ->
                        when (igEvent) {
                            is ImageGenerator.ImageGeneratorEvent.OnError -> {
                                eventBus.sendEvent(EventBus.GlobalEvent.Error(igEvent.error))
                                Timber.e(igEvent.error, "ImageGenerator: ERROR")
                            }

                            is ImageGenerator.ImageGeneratorEvent.OnJpegImage -> {
                                jpegBytesStream.call(igEvent.image)
                            }
                        }
                    }.apply {
                        setImageResizeFactor(settings.resizeFactor)
                        setImageJpegQuality(settings.jpegQuality)
                        start()
                    }

                    globalStatus.isStreamRunning.set(true)
                    eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
                }

                is FgView.FromEvent.StopStreamComplete -> {
                    if (settings.enablePin && settings.autoChangePin) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.sendEvent(EventBus.GlobalEvent.SetPin(newPin))
                        eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    }
                    fgView?.toEvent(FgView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is FgView.FromEvent.HttpServerRestartRequest -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NONE))
                }

                is FgView.FromEvent.ScreenOff -> {
                    if (settings.stopOnSleep && globalStatus.isStreamRunning.get())
                        fgView?.toEvent(FgView.ToEvent.StopStream())
                }

                is FgView.FromEvent.CurrentInterfaces -> {
                    eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfaces(fromEvent.interfaceList))
                }

                else -> throw IllegalArgumentException("Unknown fromEvent")
            }
        }.also { subscriptions.add(it) }

        // Global events
        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.StopStream ||
                    it is EventBus.GlobalEvent.AppExit ||
                    it is EventBus.GlobalEvent.HttpServerRestart ||
                    it is EventBus.GlobalEvent.CurrentInterfacesRequest ||
                    it is EventBus.GlobalEvent.Error ||
                    it is EventBus.GlobalEvent.CurrentClients ||
                    it is EventBus.GlobalEvent.ResizeFactor ||
                    it is EventBus.GlobalEvent.JpegQuality
        }.subscribe { globalEvent ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] globalEvent: $globalEvent")

            when (globalEvent) {
            // From FgPresenter, StartPresenter & ProjectionCallback
                is EventBus.GlobalEvent.StopStream -> {
                    if (!globalStatus.isStreamRunning.get()) return@subscribe
                    imageGenerator?.stop()
                    imageGenerator = null
                    globalStatus.isStreamRunning.set(false)
                    eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
                    fgView?.toEvent(FgView.ToEvent.StopStream())
                }

            // From StartPresenter
                is EventBus.GlobalEvent.AppExit -> {
                    fgView?.toEvent(FgView.ToEvent.AppExit)
                }

            // From SettingsPresenter, FgPresenter
                is EventBus.GlobalEvent.HttpServerRestart -> {
                    globalStatus.error.set(null)
                    val restartReason = globalEvent.reason
                    if (globalStatus.isStreamRunning.get()) {
                        imageGenerator?.stop()
                        imageGenerator = null
                        globalStatus.isStreamRunning.set(false)
                        eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
                        fgView?.toEvent(FgView.ToEvent.StopStream(false))
                    }

                    fgView?.toEvent(FgView.ToEvent.NotifyImage(restartReason))
                    fgView?.toEvent(FgView.ToEvent.StartHttpServer, 1000)
                }

            // From StartPresenter
                is EventBus.GlobalEvent.CurrentInterfacesRequest -> {
                    fgView?.toEvent(FgView.ToEvent.CurrentInterfacesRequest)
                }

            // From HttpServer & ImageGenerator
                is EventBus.GlobalEvent.Error -> {
                    if (globalStatus.isStreamRunning.get()) {
                        imageGenerator?.stop()
                        imageGenerator = null
                        globalStatus.isStreamRunning.set(false)
                        eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
                        fgView?.toEvent(FgView.ToEvent.StopStream(false))
                    }

                    fgView?.toEvent(FgView.ToEvent.Error(globalEvent.error))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    val currentSlowConnections = globalEvent.clientsList.filter { it.hasBackpressure }.toList()
                    if (!slowConnections.containsAll(currentSlowConnections))
                        fgView?.toEvent(FgView.ToEvent.SlowConnectionDetected)
                    slowConnections.clear()
                    slowConnections.addAll(currentSlowConnections)
                }

            // From SettingsPresenter
                is EventBus.GlobalEvent.ResizeFactor -> {
                    imageGenerator?.setImageResizeFactor(globalEvent.value)
                }

            // From SettingsPresenter
                is EventBus.GlobalEvent.JpegQuality -> {
                    imageGenerator?.setImageJpegQuality(globalEvent.value)
                }
            }
        }.also { subscriptions.add(it) }
    }

    fun detach() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Detach")
        subscriptions.clear()
        fgView = null
    }

    private fun randomPin(): String =
            Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10)) +
                    Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10))
}