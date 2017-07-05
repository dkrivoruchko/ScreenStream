package info.dvkr.screenstream.model


import rx.Observable


interface Settings {

    var minimizeOnStream: Boolean

    var stopOnSleep: Boolean

    var startOnBoot: Boolean

    var disableMJPEGCheck: Boolean

    var htmlBackColor: Int

    var jpegQuality: Int

    val jpegQualityObservable: Observable<Int>

    var resizeFactor: Int

    val resizeFactorObservable: Observable<Int>

    var enablePin: Boolean

    val enablePinObservable: Observable<Boolean>

    var hidePinOnStart: Boolean

    var newPinOnAppStart: Boolean

    var autoChangePin: Boolean

    var currentPin: String

    val currentPinObservable: Observable<String>

    var severPort: Int

    val severPortObservable: Observable<Int>
}