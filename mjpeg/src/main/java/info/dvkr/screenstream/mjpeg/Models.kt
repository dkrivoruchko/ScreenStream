package info.dvkr.screenstream.mjpeg

import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.Client
import info.dvkr.screenstream.common.TrafficPoint
import java.net.InetAddress

object CoroutineException : AppError.FatalError()
object ChannelException : AppError.FatalError()
object HttpServerException : AppError.FatalError()
object BitmapFormatException : AppError.FatalError(R.string.error_wrong_image_format)
object BitmapCaptureException : AppError.FatalError()

object AddressInUseException : AppError.FixableError(R.string.error_port_in_use)
object CastSecurityException : AppError.FixableError(R.string.error_invalid_media_projection)
object AddressNotFoundException : AppError.FixableError(R.string.error_ip_address_not_found)

data class MjpegClient(
    override val id: Long,
    override val clientAddress: String,
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