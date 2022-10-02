package info.dvkr.screenstream.mjpeg.model

import info.dvkr.screenstream.common.Client

data class MjpegClient(
    val id: Long,
    val clientAddress: String,
    val isSlowConnection: Boolean,
    val isDisconnected: Boolean,
    val isBlocked: Boolean
) : Client