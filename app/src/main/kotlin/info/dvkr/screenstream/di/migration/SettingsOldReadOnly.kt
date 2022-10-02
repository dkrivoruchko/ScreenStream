package info.dvkr.screenstream.di.migration


interface SettingsOldReadOnly {
    val nightMode: Int
    val keepAwake: Boolean
    val stopOnSleep: Boolean
    val startOnBoot: Boolean
    val autoStartStop: Boolean
    val notifySlowConnections: Boolean

    val htmlEnableButtons: Boolean
    val htmlShowPressStart: Boolean
    val htmlBackColor: Int

    val vrMode: Int
    val imageCrop: Boolean
    val imageCropTop: Int
    val imageCropBottom: Int
    val imageCropLeft: Int
    val imageCropRight: Int
    val imageGrayscale: Boolean
    val jpegQuality: Int
    val resizeFactor: Int
    val rotation: Int
    val maxFPS: Int

    val enablePin: Boolean
    val hidePinOnStart: Boolean
    val newPinOnAppStart: Boolean
    val autoChangePin: Boolean
    val pin: String
    val blockAddress: Boolean

    val useWiFiOnly: Boolean
    val enableIPv6: Boolean
    val enableLocalHost: Boolean
    val localHostOnly: Boolean
    val severPort: Int
    val loggingVisible: Boolean
    val loggingOn: Boolean

    val lastIAURequestTimeStamp: Long
}