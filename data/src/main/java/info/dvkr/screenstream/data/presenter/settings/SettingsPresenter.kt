package info.dvkr.screenstream.data.presenter.settings


import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.BasePresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.settings.Settings
import rx.Scheduler
import timber.log.Timber
import java.net.ServerSocket

class SettingsPresenter internal constructor(private val settings: Settings,
                                             private val eventScheduler: Scheduler,
                                             private val eventBus: EventBus) : BasePresenter<SettingsView>() {

    override fun attach(newView: SettingsView) {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Attach")

        view?.let { detach() }
        view = newView

        // Setting current values
        view?.let {
            it.toEvent(SettingsView.ToEvent.MinimizeOnStream(settings.minimizeOnStream))
            it.toEvent(SettingsView.ToEvent.StopOnSleep(settings.stopOnSleep))
            it.toEvent(SettingsView.ToEvent.StartOnBoot(settings.startOnBoot))
            it.toEvent(SettingsView.ToEvent.DisableMjpegCheck(settings.disableMJPEGCheck))
            it.toEvent(SettingsView.ToEvent.HtmlBackColor(settings.htmlBackColor))
            it.toEvent(SettingsView.ToEvent.ResizeFactor(settings.resizeFactor))
            it.toEvent(SettingsView.ToEvent.JpegQuality(settings.jpegQuality))
            it.toEvent(SettingsView.ToEvent.EnablePin(settings.enablePin))
            it.toEvent(SettingsView.ToEvent.HidePinOnStart(settings.hidePinOnStart))
            it.toEvent(SettingsView.ToEvent.NewPinOnAppStart(settings.newPinOnAppStart))
            it.toEvent(SettingsView.ToEvent.AutoChangePin(settings.autoChangePin))
            it.toEvent(SettingsView.ToEvent.SetPin(settings.currentPin))
            it.toEvent(SettingsView.ToEvent.UseWiFiOnly(settings.useWiFiOnly))
            it.toEvent(SettingsView.ToEvent.ServerPort(settings.severPort))
        }

        // Getting values from Activity
        view?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] fromEvent: $fromEvent")

            when (fromEvent) {
                is SettingsView.FromEvent.MinimizeOnStream -> settings.minimizeOnStream = fromEvent.value
                is SettingsView.FromEvent.StopOnSleep -> settings.stopOnSleep = fromEvent.value
                is SettingsView.FromEvent.StartOnBoot -> settings.startOnBoot = fromEvent.value

                is SettingsView.FromEvent.DisableMjpegCheck -> {
                    settings.disableMJPEGCheck = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsView.FromEvent.HtmlBackColor -> {
                    settings.htmlBackColor = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsView.FromEvent.ResizeFactor -> {
                    settings.resizeFactor = fromEvent.value
                    view?.toEvent(SettingsView.ToEvent.ResizeFactor(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.ResizeFactor(fromEvent.value))
                }

                is SettingsView.FromEvent.JpegQuality -> {
                    settings.jpegQuality = fromEvent.value
                    view?.toEvent(SettingsView.ToEvent.JpegQuality(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.JpegQuality(fromEvent.value))
                }

                is SettingsView.FromEvent.EnablePin -> {
                    settings.enablePin = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.sendEvent(EventBus.GlobalEvent.EnablePin(fromEvent.value))
                }

                is SettingsView.FromEvent.HidePinOnStart -> settings.hidePinOnStart = fromEvent.value
                is SettingsView.FromEvent.NewPinOnAppStart -> settings.newPinOnAppStart = fromEvent.value
                is SettingsView.FromEvent.AutoChangePin -> settings.autoChangePin = fromEvent.value

                is SettingsView.FromEvent.SetPin -> {
                    settings.currentPin = fromEvent.value
                    view?.toEvent(SettingsView.ToEvent.SetPin(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.sendEvent(EventBus.GlobalEvent.SetPin(fromEvent.value))
                }

                is SettingsView.FromEvent.UseWiFiOnly -> {
                    settings.useWiFiOnly = fromEvent.value
                    eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                }

                is SettingsView.FromEvent.ServerPort -> {
                    var serverSocket: ServerSocket? = null
                    var portFree = false
                    try {
                        serverSocket = ServerSocket(fromEvent.value)
                        portFree = true
                    } catch (ignore: Throwable) {
                        // Port is busy
                    } finally {
                        serverSocket?.close()
                        if (portFree) {
                            settings.severPort = fromEvent.value
                            view?.toEvent(SettingsView.ToEvent.ServerPort(fromEvent.value))
                            eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                        } else {
                            view?.toEvent(SettingsView.ToEvent.ErrorServerPortBusy())
                            Timber.e("[${Thread.currentThread().name}] ERROR: Port busy: ${fromEvent.value}")
                        }
                    }
                }

                else -> throw IllegalArgumentException("Unknown fromEvent")
            }
        }.also { subscriptions.add(it) }
    }
}