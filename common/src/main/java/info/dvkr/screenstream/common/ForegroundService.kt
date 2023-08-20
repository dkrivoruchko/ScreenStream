package info.dvkr.screenstream.common

import android.app.Service

public abstract class ForegroundService: Service() {

    abstract fun showForegroundNotification()
    abstract fun hideForegroundNotification()
}