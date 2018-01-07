package info.dvkr.screenstream.data.presenter

import android.support.annotation.Keep

interface BaseView {

    @Keep open class BaseFromEvent

    @Keep open class BaseToEvent {
        @Keep class Error(val error: Throwable?) : BaseToEvent()
    }

    fun toEvent(toEvent: BaseToEvent)
}