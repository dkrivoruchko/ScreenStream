package info.dvkr.screenstream.mjpeg.model

import info.dvkr.screenstream.common.TrafficPoint

data class MjpegTrafficPoint(val time: Long, val bytes: Long) : TrafficPoint