package io.screenstream.mjpeg

import android.app.Application
import io.screenstream.core.configureLogWriter

internal class SilentMjpegTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureLogWriter { _, _, _, _ -> }
    }
}
