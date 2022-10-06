package info.dvkr.screenstream.service

import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.Client
import info.dvkr.screenstream.common.TrafficPoint
import info.dvkr.screenstream.mjpeg.NetInterface

sealed class ServiceMessage {
    object FinishActivity : ServiceMessage()

    data class ServiceState(
        val isStreaming: Boolean, val isBusy: Boolean, val waitingForCastPermission: Boolean,
        val netInterfaces: List<NetInterface>,
        val appError: AppError?
    ) : ServiceMessage()

    data class Clients(val clients: List<Client>) : ServiceMessage()
    data class TrafficHistory(val trafficHistory: List<TrafficPoint>) : ServiceMessage() {
        override fun toString(): String = javaClass.simpleName
    }

    override fun toString(): String = javaClass.simpleName
}