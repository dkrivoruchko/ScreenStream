package io.screenstream.core

import android.util.Log
import com.elvishew.xlog.XLog

// TODO May be this must be app core not streaming core?

@Volatile
private var logWriter: (priority: Int, tag: String, message: String, cause: Throwable?) -> Unit = { priority, _, message, cause ->
    if (cause == null) {
        XLog.log(priority, message)
    } else {
        XLog.log(priority, message, cause)
    }
}

/**
 * Replaces the process-wide writer behind [logV], [logD], [logI], [logW] and [logE]. Configure it during
 * application startup, before components log. [writer] receives the Android [Log] priority, the call-site
 * tag, the formatted message `Class#hashCode.tag@thread: msg`, and the optional cause. It runs synchronously
 * on the logging thread and can be called concurrently. The default writer forwards the message and cause
 * to XLog, which the application must initialize first.
 */
public fun configureLogWriter(writer: (priority: Int, tag: String, message: String, cause: Throwable?) -> Unit) {
    logWriter = writer
}

/** Logs [msg] at verbose level through the configured writer; see [configureLogWriter]. */
public fun Any.logV(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.VERBOSE, tag, msg, cause)

/** Logs [msg] at debug level through the configured writer; see [configureLogWriter]. */
public fun Any.logD(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.DEBUG, tag, msg, cause)

/** Logs [msg] at info level through the configured writer; see [configureLogWriter]. */
public fun Any.logI(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.INFO, tag, msg, cause)

/**
 * Logs [msg] at warning level through the configured writer. Supply [cause] only for an unexpected
 * condition that should be reported as an exception.
 */
public fun Any.logW(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.WARN, tag, msg, cause)

/**
 * Logs [msg] at error level through the configured writer. Supply [cause] for a concrete bug that needs
 * urgent attention.
 */
public fun Any.logE(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.ERROR, tag, msg, cause)

private fun Any.log(level: Int, tag: String, msg: String, cause: Throwable?) {
    val message = "${javaClass.simpleName}#${hashCode()}.$tag@${Thread.currentThread().name}: $msg"
    logWriter(level, tag, message, cause)
}
