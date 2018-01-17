package info.dvkr.screenstream.data.presenter.foreground

import android.media.projection.MediaProjection
import android.support.annotation.Keep
import android.view.Display
import info.dvkr.screenstream.domain.eventbus.EventBus
import java.net.InetSocketAddress


interface FgView {
    // From ForegroundService to FgPresenter
    @Keep sealed class FromEvent {
        @Keep object Init : FromEvent()
        @Keep data class StartHttpServer(val serverAddress: InetSocketAddress,
                                         val favicon: ByteArray,
                                         val logo: ByteArray,
                                         val baseIndexHtml: String,
                                         val basePinRequestHtml: String,
                                         val pinRequestErrorMsg: String) : FromEvent()

        @Keep object StopHttpServer : FromEvent()
        @Keep class StartImageGenerator(val display: Display,
                                        val mediaProjection: MediaProjection) : FromEvent()

        @Keep object StopStreamComplete : FromEvent()
        @Keep object HttpServerRestartRequest : FromEvent()
        @Keep object ScreenOff : FromEvent()
        @Keep class CurrentInterfaces(val interfaceList: List<EventBus.Interface>) : FromEvent()
    }

    // To ForegroundService from FgPresenter
    @Keep open class ToEvent { // Open for ForegroundService.LocalEvent
        @Keep object StartHttpServer : ToEvent()
        @Keep object ConnectionEvent : ToEvent()
        @Keep class NotifyImage(val notifyType: String) : ToEvent()
        @Keep class StopStream(val isNotifyOnComplete: Boolean = true) : ToEvent()
        @Keep object AppExit : ToEvent()
        @Keep object CurrentInterfacesRequest : ToEvent()
        @Keep class Error(val error: Throwable) : ToEvent()
        @Keep object SlowConnectionDetected : ToEvent()
    }

    fun toEvent(event: ToEvent, timeout: Long = 0)
}