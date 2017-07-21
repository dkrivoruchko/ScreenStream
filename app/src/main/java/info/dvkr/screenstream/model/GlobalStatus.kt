package info.dvkr.screenstream.model


interface GlobalStatus {

    var isStreamRunning: Boolean

    var error: Throwable?
}