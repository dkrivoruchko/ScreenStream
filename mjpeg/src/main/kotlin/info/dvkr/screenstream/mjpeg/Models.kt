package info.dvkr.screenstream.mjpeg

import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.Client
import info.dvkr.screenstream.common.TrafficPoint
import java.net.InetAddress

object CoroutineException : AppError.FatalError()
object ChannelException : AppError.FatalError()
object HttpServerException : AppError.FatalError()
object BitmapFormatException : AppError.FatalError()
object BitmapCaptureException : AppError.FatalError()

object AddressInUseException : AppError.FixableError()
object CastSecurityException : AppError.FixableError()
object AddressNotFoundException : AppError.FixableError()

data class MjpegClient(
    val id: Long,
    val clientAddress: String,
    val isSlowConnection: Boolean,
    val isDisconnected: Boolean,
    val isBlocked: Boolean
) : Client

data class MjpegPublicState(
    val isStreaming: Boolean,
    val isBusy: Boolean,
    val waitingForCastPermission: Boolean,
    val netInterfaces: List<NetInterface>,
    val appError: AppError?
) : AppStateMachine.Effect.PublicState()

data class MjpegTrafficPoint(val time: Long, val bytes: Long) : TrafficPoint

data class NetInterface(val name: String, val address: InetAddress)