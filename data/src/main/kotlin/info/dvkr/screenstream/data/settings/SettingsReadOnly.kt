package info.dvkr.screenstream.data.settings

import androidx.annotation.MainThread


interface SettingsReadOnly {
    val nightMode: Int
    val stopOnSleep: Boolean
    val startOnBoot: Boolean
    val autoStartStop: Boolean
    val notifySlowConnections: Boolean

    val htmlEnableButtons: Boolean
    val htmlBackColor: Int

    val vrMode: Int
    val imageCrop: Boolean
    val imageCropTop: Int
    val imageCropBottom: Int
    val imageCropLeft: Int
    val imageCropRight: Int
    val jpegQuality: Int
    val resizeFactor: Int
    val rotation: Int
    val maxFPS: Int

    val enablePin: Boolean
    val hidePinOnStart: Boolean
    val newPinOnAppStart: Boolean
    val autoChangePin: Boolean
    val pin: String

    val useWiFiOnly: Boolean
    val enableIPv6: Boolean
    val enableLocalHost: Boolean
    val localHostOnly: Boolean
    val severPort: Int
    val loggingVisible: Boolean
    val loggingOn: Boolean

    val lastIAURequestTimeStamp: Long

    fun autoChangePinOnStart()

    fun checkAndChangeAutoChangePinOnStop(): Boolean

    interface OnSettingsChangeListener {
        @MainThread
        fun onSettingsChanged(key: String)
    }

    fun registerChangeListener(listener: OnSettingsChangeListener)

    fun unregisterChangeListener(listener: OnSettingsChangeListener)
}