package io.screenstream.core

import android.app.Application

internal class SilentCoreTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureLogWriter { _, _, _, _ -> }
    }
}
