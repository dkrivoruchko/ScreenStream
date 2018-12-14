package info.dvkr.screenstream.data.settings

import androidx.annotation.MainThread


interface SettingsReadOnly {
    val minimizeOnStream: Boolean
    val stopOnSleep: Boolean
    val startOnBoot: Boolean
    val disableMJPEGCheck: Boolean
    val htmlBackColor: Int

    val jpegQuality: Int
    val resizeFactor: Int

    val enablePin: Boolean
    val hidePinOnStart: Boolean
    val newPinOnAppStart: Boolean
    val autoChangePin: Boolean
    val pin: String

    val useWiFiOnly: Boolean
    val severPort: Int

    fun autoChangePinOnStop(): Boolean

    interface OnSettingsChangeListener {
        @MainThread
        fun onSettingsChanged(key: String)
    }

    fun registerChangeListener(listener: OnSettingsChangeListener)

    fun unregisterChangeListener(listener: OnSettingsChangeListener)
}