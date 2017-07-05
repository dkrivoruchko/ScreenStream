package info.dvkr.screenstream.presenter


import com.crashlytics.android.Crashlytics
import com.crashlytics.android.answers.Answers
import com.crashlytics.android.answers.CustomEvent
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.ImageNotify
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.ui.SettingsActivityView
import rx.subscriptions.CompositeSubscription
import java.net.ServerSocket
import javax.inject.Inject

@PersistentScope
class SettingsActivityPresenter @Inject internal constructor(val mSettings: Settings, val mAppEvent: AppEvent) {
    private val TAG = "SettingsActivityPresenter"

    private val ANSWERS_TAG = "SETTINGS"
    private val mSubscriptions = CompositeSubscription()
    private var mSettingsActivity: SettingsActivityView? = null

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
        //TODO move to ???
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

        // Interface - Minimize on stream
        mSettingsActivity?.setMinimizeOnStream(mSettings.minimizeOnStream)
        mSubscriptions.add(mSettingsActivity?.onMinimizeOnStream()?.subscribe { value ->
            mSettings.minimizeOnStream = value
        })

        // Interface - Stop on sleep
        mSettingsActivity?.setStopOnSleep(mSettings.stopOnSleep)
        mSubscriptions.add(mSettingsActivity?.onStopOnSleep()?.subscribe { value ->
            mSettings.stopOnSleep = value
        })

        // Interface - Start on Boot
        mSettingsActivity?.setStartOnBoot(mSettings.startOnBoot)
        mSubscriptions.add(mSettingsActivity?.onStartOnBoot()?.subscribe { value ->
            mSettings.startOnBoot = value
        })

        // Interface - HTML MJPEG check
        mSettingsActivity?.setDisableMjpegCheck(mSettings.disableMJPEGCheck)
        mSubscriptions.add(mSettingsActivity?.onDisableMjpegCheck()?.subscribe { value ->
            mSettings.disableMJPEGCheck = value
            mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
        })

        // Interface - HTML Back color
        mSettingsActivity?.setHtmlBackColor(mSettings.htmlBackColor)
        mSubscriptions.add(mSettingsActivity?.onHtmlBackColor()?.subscribe { value ->
            mSettings.htmlBackColor = value
            mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
        })

        // Image - Resize factor
        mSettingsActivity?.setResizeFactor(mSettings.resizeFactor)
        mSubscriptions.add(mSettingsActivity?.onResizeFactor()?.subscribe { value ->
            mSettings.resizeFactor = value
            mSettingsActivity?.setResizeFactor(value)
        })

        // Image - Jpeg quality
        mSettingsActivity?.setJpegQuality(mSettings.jpegQuality)
        mSubscriptions.add(mSettingsActivity?.onJpegQuality()?.subscribe { value ->
            mSettings.jpegQuality = value
            mSettingsActivity?.setJpegQuality(value)
        })

        // Security - Enable pin
        mSettingsActivity?.setEnablePin(mSettings.enablePin)
        mSubscriptions.add(mSettingsActivity?.onEnablePin()?.subscribe { value ->
            mSettings.enablePin = value
            mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
        })

        // Security - Hide pin on start
        mSettingsActivity?.setHidePinOnStart(mSettings.hidePinOnStart)
        mSubscriptions.add(mSettingsActivity?.onHidePinOnStart()?.subscribe { value ->
            mSettings.hidePinOnStart = value
        })

        // Security - New pin on app start
        mSettingsActivity?.setNewPinOnAppStart(mSettings.newPinOnAppStart)
        mSubscriptions.add(mSettingsActivity?.onNewPinOnAppStart()?.subscribe { value ->
            mSettings.newPinOnAppStart = value
        })

        // Security - Auto change pin
        mSettingsActivity?.setAutoChangePin(mSettings.autoChangePin)
        mSubscriptions.add(mSettingsActivity?.onAutoChangePin()?.subscribe { value ->
            mSettings.autoChangePin = value
        })

        // Security - Set pin
        mSettingsActivity?.setSetPin(mSettings.currentPin)
        mSubscriptions.add(mSettingsActivity?.onSetPin()?.subscribe { value ->
            mSettings.currentPin = value
            mSettingsActivity?.setSetPin(value)
            mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
        })

        // Advanced - Server port
        mSettingsActivity?.setServerPort(mSettings.severPort)
        mSubscriptions.add(mSettingsActivity?.onServerPort()?.subscribe { value ->
            var serverSocket: ServerSocket? = null
            var portFree = false
            try {
                serverSocket = ServerSocket(value)
                portFree = true
            } catch (ex: Throwable) {
                // Port is busy
                Crashlytics.log("Settings: Requested port: $value")
                Crashlytics.logException(ex)
            } finally {
                serverSocket?.close()
                if (portFree) {
                    mSettings.severPort = value
                    mSettingsActivity?.setServerPort(value)
                    mAppEvent.sendEvent(AppEvent.Event.HttpServerRestart(ImageNotify.IMAGE_TYPE_NEW_ADDRESS))
                } else {
                    mSettingsActivity?.showMessage(SettingsActivityView.Message.ErrorServerPortBusy())
                    println(TAG + ": Thread [${Thread.currentThread().name}] ERROR: Port busy: $value")
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