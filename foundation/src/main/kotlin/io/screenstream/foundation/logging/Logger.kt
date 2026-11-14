package io.screenstream.foundation.logging

public interface Logger {
    public fun log(level: Level, tag: String, msg: String, cause: Throwable?)

    public fun v(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
        log(Level.Verbose, tag, msg, cause)

    public fun d(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
        log(Level.Debug, tag, msg, cause)

    public fun i(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
        log(Level.Info, tag, msg, cause)

    public fun w(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
        log(Level.Warn, tag, msg, cause)

    public fun e(tag: String = "", msg: String = "Invoked", cause: Throwable? = null): Unit =
        log(Level.Error, tag, msg, cause)

    public enum class Level { Verbose, Debug, Info, Warn, Error, }
}
