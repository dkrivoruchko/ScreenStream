package info.dvkr.screenstream.data.presenter.settings


import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.data.dagger.PersistentScope
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.settings.Settings
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.net.ServerSocket
import javax.inject.Inject

@PersistentScope
class SettingsPresenter @Inject internal constructor(private val settings: Settings,
                                                     private val eventScheduler: Scheduler,
                                                     private val eventBus: EventBus) {
    private val TAG = "SettingsPresenter"

    private val subscriptions = CompositeSubscription()
    private var settingsView: SettingsView? = null

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
        Crashlytics.log(1, TAG, "Constructor")
    }

    fun attach(view: SettingsView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")
        Crashlytics.log(1, TAG, "Attach")

        settingsView?.let { detach() }
        settingsView = view

        // Setting current values
        settingsView?.let {
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
        settingsView?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")

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
                    settingsView?.toEvent(SettingsView.ToEvent.ResizeFactor(fromEvent.value))
                    eventBus.sendEvent(EventBus.GlobalEvent.ResizeFactor(fromEvent.value))
                }

                is SettingsView.FromEvent.JpegQuality -> {
                    settings.jpegQuality = fromEvent.value
                    settingsView?.toEvent(SettingsView.ToEvent.JpegQuality(fromEvent.value))
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
                    settingsView?.toEvent(SettingsView.ToEvent.SetPin(fromEvent.value))
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
                            settingsView?.toEvent(SettingsView.ToEvent.ServerPort(fromEvent.value))
                            eventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                        } else {
                            settingsView?.toEvent(SettingsView.ToEvent.ErrorServerPortBusy())
                            println(TAG + ": Thread [${Thread.currentThread().name}] ERROR: Port busy: ${fromEvent.value}")
                        }
                    }
                }

                else -> if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent WARRING: IGNORED")
            }
        }.also { subscriptions.add(it) }
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        Crashlytics.log(1, TAG, "Detach")
        subscriptions.clear()
        settingsView = null
    }
}