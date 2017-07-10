package info.dvkr.screenstream.service


import android.support.annotation.Keep
import rx.Observable

interface ForegroundServiceView {

    // System network interfaces
    @Keep data class Interface(val name: String, val address: String)

    // Known Errors
//    @Keep data class KnownErrors(var isServerPortBusy: Boolean,
//                                 var isWrongImageFormat: Boolean)

    // From ForegroundService to ForegroundServicePresenter
    @Keep sealed class FromEvent {
        @Keep class Init : FromEvent()
        @Keep data class StartHttpServer(val favicon: ByteArray,
                                         val baseIndexHtml: String,
                                         val basePinRequestHtml: String,
                                         val pinRequestErrorMsg: String,
                                         val jpegByteStream: Observable<ByteArray>) : FromEvent()

        @Keep class StopHttpServer : FromEvent()
        @Keep class StopStreamComplete : FromEvent()
        @Keep class ScreenOff : FromEvent()
        @Keep data class CurrentInterfaces(val interfaceList: List<Interface>) : FromEvent()
//        @Keep data class WrongImageFormat(var isWrongImageFormat: Boolean) : FromEvent()
    }

    // Events from ForegroundService to ForegroundServicePresenter
    fun fromEvent(): Observable<FromEvent>

    // To ForegroundService from ForegroundServicePresenter
    @Keep open class ToEvent { // Open for ForegroundService.LocalEvent
        @Keep class StartHttpServer : ToEvent()
        @Keep data class NotifyImage(val notifyType: String) : ToEvent()
        @Keep data class StopStream(val isNotifyOnComplete: Boolean = true) : ToEvent()
        @Keep class AppExit : ToEvent()
        @Keep class CurrentInterfacesRequest : ToEvent()
//        @Keep data class Errors(val errors: KnownErrors) : ToEvent()
    }

    // Events to ForegroundService from ForegroundServicePresenter
    fun toEvent(event: ToEvent)

    // Events to ForegroundService from ForegroundServicePresenter
    fun toEvent(event: ToEvent, timeout: Long)
}