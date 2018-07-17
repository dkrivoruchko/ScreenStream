package info.dvkr.screenstream.data.presenter.settings


import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.BasePresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.settings.Settings
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import timber.log.Timber
import java.net.ServerSocket

class SettingsPresenter(eventBus: EventBus, private val settings: Settings) :
    BasePresenter<SettingsView, SettingsView.FromEvent>(eventBus) {

    init {
        viewChannel = actor(CommonPool, Channel.UNLIMITED, parent = baseJob) {
            for (fromEvent in this) when (fromEvent) {
                is SettingsView.FromEvent.MinimizeOnStream ->
                    settings.minimizeOnStream = fromEvent.value

                is SettingsView.FromEvent.StopOnSleep ->
                    settings.stopOnSleep = fromEvent.value

                is SettingsView.FromEvent.StartOnBoot ->
                    settings.startOnBoot = fromEvent.value

                is SettingsView.FromEvent.DisableMjpegCheck -> {
                    settings.disableMJPEGCheck = fromEvent.value
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsView.FromEvent.HtmlBackColor -> {
                    settings.htmlBackColor = fromEvent.value
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsView.FromEvent.ResizeFactor -> {
                    settings.resizeFactor = fromEvent.value
                    notifyView(SettingsView.ToEvent.ResizeFactor(fromEvent.value))
                    eventBus.send(EventBus.GlobalEvent.ResizeFactor(fromEvent.value))
                }

                is SettingsView.FromEvent.JpegQuality -> {
                    settings.jpegQuality = fromEvent.value
                    notifyView(SettingsView.ToEvent.JpegQuality(fromEvent.value))
                    eventBus.send(EventBus.GlobalEvent.JpegQuality(fromEvent.value))
                }

                is SettingsView.FromEvent.EnablePin -> {
                    settings.enablePin = fromEvent.value
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.send(EventBus.GlobalEvent.EnablePin(fromEvent.value))
                }

                is SettingsView.FromEvent.HidePinOnStart ->
                    settings.hidePinOnStart = fromEvent.value

                is SettingsView.FromEvent.NewPinOnAppStart ->
                    settings.newPinOnAppStart = fromEvent.value

                is SettingsView.FromEvent.AutoChangePin ->
                    settings.autoChangePin = fromEvent.value

                is SettingsView.FromEvent.SetPin -> {
                    settings.currentPin = fromEvent.value
                    notifyView(SettingsView.ToEvent.SetPin(fromEvent.value))
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    eventBus.send(EventBus.GlobalEvent.SetPin(fromEvent.value))
                }

                is SettingsView.FromEvent.UseWiFiOnly -> {
                    settings.useWiFiOnly = fromEvent.value
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
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
                            notifyView(SettingsView.ToEvent.ServerPort(fromEvent.value))
                            eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                        } else {
                            notifyView(SettingsView.ToEvent.ErrorServerPortBusy)
                            Timber.e("[${Thread.currentThread().name}] ERROR: Port busy: ${fromEvent.value}")
                        }
                    }
                }
            }
        }
    }

    fun attach(newView: SettingsView) {
        super.attach(newView) {}

        notifyView(SettingsView.ToEvent.MinimizeOnStream(settings.minimizeOnStream))
        notifyView(SettingsView.ToEvent.StopOnSleep(settings.stopOnSleep))
        notifyView(SettingsView.ToEvent.StartOnBoot(settings.startOnBoot))
        notifyView(SettingsView.ToEvent.DisableMjpegCheck(settings.disableMJPEGCheck))
        notifyView(SettingsView.ToEvent.HtmlBackColor(settings.htmlBackColor))
        notifyView(SettingsView.ToEvent.ResizeFactor(settings.resizeFactor))
        notifyView(SettingsView.ToEvent.JpegQuality(settings.jpegQuality))
        notifyView(SettingsView.ToEvent.EnablePin(settings.enablePin))
        notifyView(SettingsView.ToEvent.HidePinOnStart(settings.hidePinOnStart))
        notifyView(SettingsView.ToEvent.NewPinOnAppStart(settings.newPinOnAppStart))
        notifyView(SettingsView.ToEvent.AutoChangePin(settings.autoChangePin))
        notifyView(SettingsView.ToEvent.SetPin(settings.currentPin))
        notifyView(SettingsView.ToEvent.UseWiFiOnly(settings.useWiFiOnly))
        notifyView(SettingsView.ToEvent.ServerPort(settings.severPort))
    }
}