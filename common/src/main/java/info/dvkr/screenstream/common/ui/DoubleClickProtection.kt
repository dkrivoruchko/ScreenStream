package info.dvkr.screenstream.common.ui

public interface DoubleClickProtection {
    public fun processClick(onClick: () -> Unit)

    public companion object
}

public fun DoubleClickProtection.Companion.get(): DoubleClickProtection = DoubleClickProtectionImpl()

private class DoubleClickProtectionImpl : DoubleClickProtection {
    private val now: Long
        get() = System.currentTimeMillis()

    private var lastClickTimeMs: Long = 0

    override fun processClick(onClick: () -> Unit) {
        if (now - lastClickTimeMs >= 300L) onClick.invoke()
        lastClickTimeMs = now
    }
}