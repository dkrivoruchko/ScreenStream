package info.dvkr.screenstream.rtsp.internal.video

internal class FpsCalculator(private val fpsCallback: (Int) -> Unit) {
    private var frameCount = 0
    private var lastUpdate = System.nanoTime()

    fun recordFrame() {
        frameCount++
        val now = System.nanoTime()
        if (now - lastUpdate >= 1_000_000_000L) {
            fpsCallback(frameCount)
            frameCount = 0
            lastUpdate = now
        }
    }
}