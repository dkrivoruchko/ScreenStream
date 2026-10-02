package io.screenstream.streaming

import android.util.Log
import com.elvishew.xlog.XLog

@Volatile
private var logWriter: (priority: Int, tag: String, message: String, cause: Throwable?) -> Unit = { priority, _, message, cause ->
    if (cause == null) {
        XLog.log(priority, message)
    } else {
        XLog.log(priority, message, cause)
    }
}

/**
 * Configures the application-wide writer at startup, before components begin logging.
 * [writer] receives Android [Log] priorities, the call-site tag, and a message containing
 * receiver identity and the current thread name. The default writer uses XLog.
 * Calls run on the caller's thread and may occur concurrently.
 */
public fun configureLogWriter(writer: (priority: Int, tag: String, message: String, cause: Throwable?) -> Unit) {
    logWriter = writer
}

/**
 * Logs [msg] at verbose level with receiver identity, [tag], and the current thread name.
 * Includes [cause] when supplied. Requires the application's logger to be initialized.
 */
public fun Any.logV(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.VERBOSE, tag, msg, cause)

/**
 * Logs [msg] at debug level with receiver identity, [tag], and the current thread name.
 * Includes [cause] when supplied. Requires the application's logger to be initialized.
 */
public fun Any.logD(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.DEBUG, tag, msg, cause)

/**
 * Logs [msg] at info level with receiver identity, [tag], and the current thread name.
 * Includes [cause] when supplied. Requires the application's logger to be initialized.
 */
public fun Any.logI(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.INFO, tag, msg, cause)

/**
 * Logs [msg] at warning level with receiver identity, [tag], and the current thread name.
 * Supply [cause] to report an unexpected condition as an exception; without it, only the message is logged.
 * Requires the application's logger to be initialized.
 */
public fun Any.logW(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.WARN, tag, msg, cause)

/**
 * Logs [msg] at error level with receiver identity, [tag], and the current thread name.
 * Supply [cause] for a concrete bug requiring urgent attention.
 * Requires the application's logger to be initialized.
 */
public fun Any.logE(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
    log(Log.ERROR, tag, msg, cause)

private fun Any.log(level: Int, tag: String, msg: String, cause: Throwable?) {
    val message = "${javaClass.simpleName}#${hashCode()}.$tag@${Thread.currentThread().name}: $msg"
    logWriter(level, tag, message, cause)
}
