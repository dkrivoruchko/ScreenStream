package info.dvkr.screenstream.presenter


import com.crashlytics.android.Crashlytics
import com.crashlytics.android.answers.Answers
import com.crashlytics.android.answers.CustomEvent
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.ui.SettingsActivityView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.net.ServerSocket
import javax.inject.Inject

@PersistentScope
class SettingsActivityPresenter @Inject internal constructor(private val mSettings: Settings,
                                                             private val mEventScheduler: Scheduler,
                                                             private val mEventBus: EventBus) {
    private val TAG = "SettingsActivityPresenter"

    private val mSubscriptions = CompositeSubscription()
    private var mSettingsActivity: SettingsActivityView? = null

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
        //TODO move to ???
        val ANSWERS_TAG = "SETTINGS"
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("MinimizeOnStream", mSettings.minimizeOnStream.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("StopOnSleep", mSettings.stopOnSleep.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("StartOnBoot", mSettings.startOnBoot.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("DisableMjpegCheck", mSettings.disableMJPEGCheck.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("HtmlBackColor", mSettings.htmlBackColor.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("ResizeFactor", mSettings.resizeFactor.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("JpegQuality", mSettings.jpegQuality.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("EnablePin", mSettings.enablePin.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("HidePinOnStart", mSettings.hidePinOnStart.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("NewPinOnAppStart", mSettings.newPinOnAppStart.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("AutoChangePin", mSettings.autoChangePin.toString()))
        Answers.getInstance().logCustom(CustomEvent(ANSWERS_TAG).putCustomAttribute("ServerPort", mSettings.severPort.toString()))
    }

    fun attach(startActivity: SettingsActivityView) {
        println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        if (null != mSettingsActivity) detach()
        mSettingsActivity = startActivity

        // Setting current values
        mSettingsActivity?.let {
            it.toEvent(SettingsActivityView.ToEvent.MinimizeOnStream(mSettings.minimizeOnStream))
            it.toEvent(SettingsActivityView.ToEvent.StopOnSleep(mSettings.stopOnSleep))
            it.toEvent(SettingsActivityView.ToEvent.StartOnBoot(mSettings.startOnBoot))
            it.toEvent(SettingsActivityView.ToEvent.DisableMjpegCheck(mSettings.disableMJPEGCheck))
            it.toEvent(SettingsActivityView.ToEvent.HtmlBackColor(mSettings.htmlBackColor))
            it.toEvent(SettingsActivityView.ToEvent.ResizeFactor(mSettings.resizeFactor))
            it.toEvent(SettingsActivityView.ToEvent.JpegQuality(mSettings.jpegQuality))
            it.toEvent(SettingsActivityView.ToEvent.EnablePin(mSettings.enablePin))
            it.toEvent(SettingsActivityView.ToEvent.HidePinOnStart(mSettings.hidePinOnStart))
            it.toEvent(SettingsActivityView.ToEvent.NewPinOnAppStart(mSettings.newPinOnAppStart))
            it.toEvent(SettingsActivityView.ToEvent.AutoChangePin(mSettings.autoChangePin))
            it.toEvent(SettingsActivityView.ToEvent.SetPin(mSettings.currentPin))
            it.toEvent(SettingsActivityView.ToEvent.ServerPort(mSettings.severPort))
        }

        // Getting values from Activity
        mSubscriptions.add(mSettingsActivity?.fromEvent()?.observeOn(mEventScheduler)?.subscribe { fromEvent ->
            println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: " + fromEvent.javaClass.simpleName)

            when (fromEvent) {
                is SettingsActivityView.FromEvent.MinimizeOnStream -> mSettings.minimizeOnStream = fromEvent.value
                is SettingsActivityView.FromEvent.StopOnSleep -> mSettings.stopOnSleep = fromEvent.value
                is SettingsActivityView.FromEvent.StartOnBoot -> mSettings.startOnBoot = fromEvent.value

                is SettingsActivityView.FromEvent.DisableMjpegCheck -> {
                    mSettings.disableMJPEGCheck = fromEvent.value
                    mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsActivityView.FromEvent.HtmlBackColor -> {
                    mSettings.htmlBackColor = fromEvent.value
                    mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                }

                is SettingsActivityView.FromEvent.ResizeFactor -> {
                    mSettings.resizeFactor = fromEvent.value
                    mSettingsActivity?.toEvent(SettingsActivityView.ToEvent.ResizeFactor(fromEvent.value))
                    mEventBus.sendEvent(EventBus.GlobalEvent.ResizeFactor(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.JpegQuality -> {
                    mSettings.jpegQuality = fromEvent.value
                    mSettingsActivity?.toEvent(SettingsActivityView.ToEvent.JpegQuality(fromEvent.value))
                    mEventBus.sendEvent(EventBus.GlobalEvent.JpegQuality(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.EnablePin -> {
                    mSettings.enablePin = fromEvent.value
                    mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    mEventBus.sendEvent(EventBus.GlobalEvent.EnablePin(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.HidePinOnStart -> mSettings.hidePinOnStart = fromEvent.value
                is SettingsActivityView.FromEvent.NewPinOnAppStart -> mSettings.newPinOnAppStart = fromEvent.value
                is SettingsActivityView.FromEvent.AutoChangePin -> mSettings.autoChangePin = fromEvent.value

                is SettingsActivityView.FromEvent.SetPin -> {
                    mSettings.currentPin = fromEvent.value
                    mSettingsActivity?.toEvent(SettingsActivityView.ToEvent.SetPin(fromEvent.value))
                    mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    mEventBus.sendEvent(EventBus.GlobalEvent.SetPin(fromEvent.value))
                }

                is SettingsActivityView.FromEvent.ServerPort -> {
                    var serverSocket: ServerSocket? = null
                    var portFree = false
                    try {
                        serverSocket = ServerSocket(fromEvent.value)
                        portFree = true
                    } catch (ex: Throwable) {
                        // Port is busy
                        Crashlytics.log("Settings: Requested port: ${fromEvent.value}")
                        Crashlytics.logException(ex)
                    } finally {
                        serverSocket?.close()
                        if (portFree) {
                            mSettings.severPort = fromEvent.value
                            mSettingsActivity?.toEvent(SettingsActivityView.ToEvent.ServerPort(fromEvent.value))
                            mEventBus.sendEvent(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                        } else {
                            mSettingsActivity?.toEvent(SettingsActivityView.ToEvent.ErrorServerPortBusy())
                            println(TAG + ": Thread [${Thread.currentThread().name}] ERROR: Port busy: ${fromEvent.value}")
                        }
                    }
                }
            }
        })
    }

    fun detach() {
        println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        mSubscriptions.clear()
        mSettingsActivity = null
    }
}