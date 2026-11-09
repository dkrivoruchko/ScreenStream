package info.dvkr.screenstream.rtsp.internal

internal object MasterClock {
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
        val nowNs = System.nanoTime()
        return (nowNs - startTimeNs) / 1000L
    }

    fun relativeTimeMs(): Long {
        ensureStarted()
        val nowNs = System.nanoTime()
        return (nowNs - startTimeNs) / 1_000_000L
    }

    fun reset() {
        started = false
    }
}