package io.screenstream.capture.internal.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

internal interface HandlerTaskPoster {
    fun post(handler: Handler, task: Runnable): Boolean

    fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean

    fun removeCallbacks(handler: Handler, task: Runnable)
}

internal interface HandlerThreadPlatform {
    fun newThread(name: String): HandlerThread

    fun start(thread: HandlerThread)

    fun looper(thread: HandlerThread): Looper?

    fun handler(looper: Looper): Handler
}
