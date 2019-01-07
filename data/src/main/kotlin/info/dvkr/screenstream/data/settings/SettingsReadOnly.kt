package info.dvkr.screenstream.data.settings

import androidx.annotation.MainThread


interface SettingsReadOnly {
    val nightMode: Int
    val minimizeOnStream: Boolean
    val stopOnSleep: Boolean
    val startOnBoot: Boolean
    val htmlBackColor: Int

    val jpegQuality: Int
    val resizeFactor: Int
    val rotation: Int

    val enablePin: Boolean
    val hidePinOnStart: Boolean
    val newPinOnAppStart: Boolean
    val autoChangePin: Boolean
    val pin: String

    val useWiFiOnly: Boolean
    val enableIPv6: Boolean
    val severPort: Int
    val loggingOn: Boolean

    fun autoChangePinOnStart()

    fun checkAndChangeAutoChangePinOnStop(): Boolean

    interface OnSettingsChangeListener {
        @MainThread
        fun onSettingsChanged(key: String)
    }

    fun registerChangeListener(listener: OnSettingsChangeListener)

    fun unregisterChangeListener(listener: OnSettingsChangeListener)
}