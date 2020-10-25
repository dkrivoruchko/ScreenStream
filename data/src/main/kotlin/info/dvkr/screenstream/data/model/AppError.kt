package info.dvkr.screenstream.data.model


sealed class AppError : Throwable()

sealed class FatalError : AppError() {
    object CoroutineException : FatalError()
    object ChannelException : FatalError()
    object NettyServerException : FatalError()
    object BitmapFormatException : FatalError()
    object BitmapCaptureException : FatalError()
}

sealed class FixableError : AppError() {
    object AddressInUseException : FixableError()
    object CastSecurityException : FixableError()
    object AddressNotFoundException : FixableError()
}