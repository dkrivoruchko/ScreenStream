package info.dvkr.screenstream.model


interface Settings {

    var minimizeOnStream: Boolean

    var stopOnSleep: Boolean

    var startOnBoot: Boolean

    var disableMJPEGCheck: Boolean

    var htmlBackColor: Int

    var jpegQuality: Int

    var resizeFactor: Int

    var enablePin: Boolean

    var hidePinOnStart: Boolean

    var newPinOnAppStart: Boolean

    var autoChangePin: Boolean

    var currentPin: String

    var severPort: Int
}