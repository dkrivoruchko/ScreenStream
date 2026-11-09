package io.screenstream.capture.device

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.uiautomator.ResultsReporter
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File

/** Stores and exports snapshots without deciding stage qualification or image verdicts. */
internal class ProjectionEvidence(context: Context, private val device: UiDevice, private val runId: String) {
    val directory: File = File(context.cacheDir, "engine-projection-evidence/$runId").apply { check(mkdirs()) }
    private val reporter = ResultsReporter("Engine projection $runId")

    fun frame(name: String, value: OwnedProjectionFrame) {
        write(name, "jpg") { it.writeBytes(value.jpeg) }
        text(
            "$name-metadata",
            "run=${value.runId}\nsequence=${value.sequence}\ntimestamp=${value.timestamp}\nbytes=${value.jpeg.size}\noutputInfo=${value.outputInfo}\n"
        )
    }

    fun text(name: String, value: String) = write(name, "txt") { it.writeText(value) }

    fun ui(name: String) {
        write(name, "xml") { device.dumpWindowHierarchy(it) }
        write(name, "png") { check(device.takeScreenshot(it)) }
    }

    /**
     * Saves capped raw SurfaceFlinger snapshots after an image failure as inconclusive evidence: they are not atomic
     * with each other or the JPEG and carry no oracle verdict. Command and write failures are returned, not thrown.
     */
    fun captureGeometryDiagnostics(
        automation: UiAutomation,
        history: String,
        stage: String,
        frame: OwnedProjectionFrame,
    ): List<Throwable> {
        val failures = ArrayList<Throwable>()
        val correlation = "run=$runId\nframeRun=${frame.runId}\nhistory=$history\nstage=$stage\nimageVerdict=FAIL\n" +
                "lastMatchedSequence=${frame.sequence}\noutputTimestampElapsedRealtimeNanos=${frame.timestamp}\n" +
                "outputInfo=${frame.outputInfo}\n" +
                "interpretation=INCONCLUSIVE raw snapshots; not atomic with each other or the JPEG; no oracle verdict\n"
        for ((label, command) in listOf(
            "frontend" to "dumpsys -t 5 SurfaceFlinger --frontend",
            "comp-displays" to "dumpsys -t 5 SurfaceFlinger --comp-displays",
        )) {
            val output = ByteArrayOutputStream()
            var truncated = false
            var commandFailure: Throwable? = null
            val beforeNanos = System.nanoTime()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
                    val buffer = ByteArray(8192)
                    while (output.size() < MAX_COMPOSITOR_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_COMPOSITOR_BYTES - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    if (output.size() == MAX_COMPOSITOR_BYTES) truncated = input.read() != -1
                }
            } catch (failure: Throwable) {
                commandFailure = failure
                failures += failure
            }
            val afterNanos = System.nanoTime()
            val name = "$stage-compositor-$label"
            try {
                write(name, "txt") { it.writeBytes(output.toByteArray()) }
            } catch (failure: Throwable) {
                failures += failure
            }
            try {
                text(
                    "$name-receipt", correlation + "command=$command\n" +
                            "commandSystemNanoTimeBefore=$beforeNanos\ncommandSystemNanoTimeAfter=$afterNanos\n" +
                            "rawBytes=${output.size()}\nrawLimitBytes=$MAX_COMPOSITOR_BYTES\ntruncated=$truncated\n" +
                            "commandFailure=${commandFailure?.stackTraceToString()}\n" +
                            "evidenceFailures=${failures.map { it.stackTraceToString() }}\n"
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        return failures
    }

    fun write(name: String, extension: String, write: (File) -> Unit) {
        check(name.matches(Regex("[a-z0-9-]+")))
        val target = File(directory, "$name.$extension")
        write(target)
        val exported = reporter.addNewFile("$runId-$name-$extension.$extension", name)
        check(exported.parentFile!!.mkdirs() || exported.parentFile!!.isDirectory)
        target.copyTo(exported, overwrite = true)
        reporter.reportToInstrumentation()
        Log.i("EngineProjectionTest", "Evidence: ${target.absolutePath}")
    }

    private companion object {
        private const val MAX_COMPOSITOR_BYTES = 1_048_576
    }
}
