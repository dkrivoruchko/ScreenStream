package info.dvkr.screenstream.ui


import android.support.annotation.Keep
import rx.Observable

interface SettingsActivityView {

    sealed class Message {
        @Keep class ErrorServerPortBusy : Message()
    }

    fun onMinimizeOnStream(): Observable<Boolean>

    fun setMinimizeOnStream(value: Boolean)

    fun onStopOnSleep(): Observable<Boolean>

    fun setStopOnSleep(value: Boolean)

    fun onStartOnBoot(): Observable<Boolean>

    fun setStartOnBoot(value: Boolean)

    fun onDisableMjpegCheck(): Observable<Boolean>

    fun setDisableMjpegCheck(value: Boolean)

    fun onHtmlBackColor(): Observable<Int>

    fun setHtmlBackColor(value: Int)

    fun onResizeFactor(): Observable<Int>

    fun setResizeFactor(value: Int)

    fun onJpegQuality(): Observable<Int>

    fun setJpegQuality(value: Int)

    fun onEnablePin(): Observable<Boolean>

    fun setEnablePin(value: Boolean)

    fun onHidePinOnStart(): Observable<Boolean>

    fun setHidePinOnStart(value: Boolean)

    fun onNewPinOnAppStart(): Observable<Boolean>

    fun setNewPinOnAppStart(value: Boolean)

    fun onAutoChangePin(): Observable<Boolean>

    fun setAutoChangePin(value: Boolean)

    fun onSetPin(): Observable<String>

    fun setSetPin(value: String)

    fun onServerPort(): Observable<Int>

    fun setServerPort(value: Int)

    fun showMessage(message: Message)
}