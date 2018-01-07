package info.dvkr.screenstream.data.presenter.settings

import android.support.annotation.Keep
import info.dvkr.screenstream.data.presenter.BaseView

interface SettingsView : BaseView {
    @Keep sealed class FromEvent : BaseView.BaseFromEvent() {
        @Keep class MinimizeOnStream(val value: Boolean) : FromEvent()
        @Keep class StopOnSleep(val value: Boolean) : FromEvent()
        @Keep class StartOnBoot(val value: Boolean) : FromEvent()
        @Keep class DisableMjpegCheck(val value: Boolean) : FromEvent()
        @Keep class HtmlBackColor(val value: Int) : FromEvent()
        @Keep class ResizeFactor(val value: Int) : FromEvent()
        @Keep class JpegQuality(val value: Int) : FromEvent()
        @Keep class EnablePin(val value: Boolean) : FromEvent()
        @Keep class HidePinOnStart(val value: Boolean) : FromEvent()
        @Keep class NewPinOnAppStart(val value: Boolean) : FromEvent()
        @Keep class AutoChangePin(val value: Boolean) : FromEvent()
        @Keep class SetPin(val value: String) : FromEvent()
        @Keep class UseWiFiOnly(val value: Boolean) : FromEvent()
        @Keep class ServerPort(val value: Int) : FromEvent()
    }

    @Keep sealed class ToEvent : BaseView.BaseToEvent() {
        @Keep class MinimizeOnStream(val value: Boolean) : ToEvent()
        @Keep class StopOnSleep(val value: Boolean) : ToEvent()
        @Keep class StartOnBoot(val value: Boolean) : ToEvent()
        @Keep class DisableMjpegCheck(val value: Boolean) : ToEvent()
        @Keep class HtmlBackColor(val value: Int) : ToEvent()
        @Keep class ResizeFactor(val value: Int) : ToEvent()
        @Keep class JpegQuality(val value: Int) : ToEvent()
        @Keep class EnablePin(val value: Boolean) : ToEvent()
        @Keep class HidePinOnStart(val value: Boolean) : ToEvent()
        @Keep class NewPinOnAppStart(val value: Boolean) : ToEvent()
        @Keep class AutoChangePin(val value: Boolean) : ToEvent()
        @Keep class SetPin(val value: String) : ToEvent()
        @Keep class UseWiFiOnly(val value: Boolean) : ToEvent()
        @Keep class ServerPort(val value: Int) : ToEvent()

        @Keep object ErrorServerPortBusy : ToEvent()
    }
}