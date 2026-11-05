package io.screenstream.capture.device

import android.app.UiAutomation
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings

internal data class LabSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    override fun toString(): String = "${width}x$height"
}

/** Physical panel mode and effective logical override are separate from projection/native producer buffers. */
internal data class LabSizeState(val physical: LabSize, val overrideSize: LabSize?) {
    val effective: LabSize get() = overrideSize ?: physical
}

internal data class LabDensityState(val physical: Int, val overrideDensity: Int?) {
    val effective: Int get() = overrideDensity ?: physical
}

internal data class LabEnvironment(
    val size: LabSizeState,
    val density: LabDensityState,
    val rotationPolicy: Pair<Int, Int>,
    val configuredNight: Int,
    val appliedNight: Int,
)

/** Only the explicit emulator lab creates this owner; every setter is confined to the default display's size. */
internal class ProjectionSourceResizeLab(private val context: Context, private val automation: UiAutomation) {
    val receipts: MutableList<String> = ArrayList()
    val original: LabEnvironment = readEnvironment()
    var sizeTouched: Boolean = false
        private set

    fun readEnvironment(): LabEnvironment = LabEnvironment(
        readSize(),
        readDensity(),
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) to
                Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION),
        checkNotNull(context.getSystemService(UiModeManager::class.java)).nightMode,
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
    )

    fun resize(size: LabSize) {
        sizeTouched = true // Retain the obligation even if command entry or return is ambiguous.
        check(shell("wm size $size -d 0").isBlank()) { "Display size setter returned unexpected output" }
    }

    fun restoreExact() {
        if (!sizeTouched) return
        val command = original.size.overrideSize?.let { "wm size $it -d 0" } ?: "wm size reset -d 0"
        check(shell(command).isBlank()) { "Display size restoration returned unexpected output" }
    }

    private fun readSize(): LabSizeState {
        val lines = shell("wm size -d 0").lineSequence().filter { it.isNotBlank() }.toList()
        check(lines.size in 1..2) { "Unqualified display-size response: $lines" }
        fun parse(line: String, prefix: String): LabSize {
            val match = Regex("$prefix: ([1-9][0-9]*)x([1-9][0-9]*)").matchEntire(line)
                ?: error("Unqualified display-size line: $line")
            return LabSize(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }

        val physical = parse(lines[0], "Physical size")
        val overrideSize = lines.getOrNull(1)?.let { parse(it, "Override size") }
        check(overrideSize != physical) { "Inconsistent effective override equal to physical size" }
        return LabSizeState(physical, overrideSize)
    }

    private fun readDensity(): LabDensityState {
        val lines = shell("wm density -d 0").lineSequence().filter { it.isNotBlank() }.toList()
        check(lines.size in 1..2) { "Unqualified display-density response: $lines" }
        fun parse(line: String, prefix: String): Int = checkNotNull(Regex("$prefix: ([1-9][0-9]*)").matchEntire(line)) {
            "Unqualified display-density line: $line"
        }.groupValues[1].toInt()

        val physical = parse(lines[0], "Physical density")
        val overrideDensity = lines.getOrNull(1)?.let { parse(it, "Override density") }
        check(overrideDensity != physical) { "Inconsistent effective density override equal to physical density" }
        return LabDensityState(physical, overrideDensity)
    }

    private fun shell(command: String): String {
        receipts += "$command entered"
        val result = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
            input.bufferedReader().readText()
        }
        receipts += "$command\n$result"
        return result
    }
}
