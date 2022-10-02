package info.dvkr.screenstream.mjpeg.model

import info.dvkr.screenstream.common.AppStateMachine

data class MjpegPublicState(
    val isStreaming: Boolean,
    val isBusy: Boolean,
    val waitingForCastPermission: Boolean,
    val netInterfaces: List<NetInterface>,
    val appError: AppError?
) : AppStateMachine.Effect.PublicState()