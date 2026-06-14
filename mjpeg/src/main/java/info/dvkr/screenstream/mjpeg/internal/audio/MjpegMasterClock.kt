package info.dvkr.screenstream.mjpeg.internal.audio

internal object MjpegMasterClock {
    @Volatile
    private var started = false

    @Volatile
    private var startTimeNs: Long = 0L

    @Synchronized
    fun ensureStarted() {
        if (!started) {
            startTimeNs = System.nanoTime()
            started = true
        }
    }

    fun relativeTimeUs(): Long {
        ensureStarted()
        return (System.nanoTime() - startTimeNs) / 1000L
    }

    fun relativeTimeMs(): Long {
        ensureStarted()
        return (System.nanoTime() - startTimeNs) / 1_000_000L
    }

    fun reset() {
        started = false
    }
}
