package info.dvkr.screenstream.data.model

import androidx.annotation.Keep


@Keep sealed class AppError : Throwable()

@Keep sealed class FatalError : AppError() {
    @Keep object CoroutineException : FatalError()
    @Keep object ActorException : FatalError()
    @Keep object ChannelException : FatalError()
    @Keep object NettyServerException : FatalError()
    @Keep object BitmapFormatException : FatalError()
    @Keep object BitmapCaptureException : FatalError()
    @Keep object NetInterfaceException : FatalError()
}

@Keep sealed class FixableError : AppError() {
    @Keep object AddressInUseException : FixableError()
    @Keep object CastSecurityException : FixableError()
    @Keep object AddressNotFoundException : FixableError()
}