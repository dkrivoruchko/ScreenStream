package io.screenstream.capture.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.screenstream.capture.CaptureOutputInfo
import io.screenstream.capture.ColorMode
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.SourceRegion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class OwnedProjectionFrame(
    val runId: String,
    val jpeg: ByteArray,
    val sequence: Long,
    val timestamp: Long,
    val outputInfo: CaptureOutputInfo,
)

/** Independent public-formula expectation for the real-projection histories; imports no production geometry helpers. */
internal class ProjectionExpectation(val scene: ProjectionScene, val parameters: ScreenCaptureParameters) {
    val source: PixelRect
    val width: Int
    val height: Int

    init {
        check(parameters.rotation == Rotation.Degrees0 && parameters.mirror == Mirror.None && parameters.colorMode == ColorMode.Color)
        val left = when (parameters.sourceRegion) {
            SourceRegion.Full, SourceRegion.LeftHalf -> 0
            SourceRegion.RightHalf -> scene.display.width / 2
        }
        val right = when (parameters.sourceRegion) {
            SourceRegion.Full, SourceRegion.RightHalf -> scene.display.width
            SourceRegion.LeftHalf -> scene.display.width / 2
        }
        source = PixelRect(
            left + parameters.crop.left, parameters.crop.top,
            right - parameters.crop.right, scene.display.height - parameters.crop.bottom
        )
        check(source.width > 0 && source.height > 0)
        when (val size = parameters.outputSize) {
            is OutputSize.ScaleFactor -> {
                width = max(1, floor(source.width * size.factor + 0.5).toInt())
                height = max(1, floor(source.height * size.factor + 0.5).toInt())
            }

            is OutputSize.TargetSize -> {
                check(size.contentMode == OutputSize.ContentMode.Stretch)
                width = size.widthPx
                height = size.heightPx
            }
        }
    }

    fun matches(info: CaptureOutputInfo): Boolean = info.parameters == parameters &&
            info.captureGeometry.widthPx == scene.display.width && info.captureGeometry.heightPx == scene.display.height &&
            info.captureGeometry.densityDpi == scene.display.density &&
            info.appliedSourceRect.leftPx == source.left && info.appliedSourceRect.topPx == source.top &&
            info.appliedSourceRect.rightPx == source.right && info.appliedSourceRect.bottomPx == source.bottom &&
            info.finalImageSize.widthPx == width && info.finalImageSize.heightPx == height

    override fun toString(): String = "source=$source output=${width}x$height display=${scene.display} parameters=$parameters"
}

internal data class ProjectionImageVerdict(val errors: List<String>, val measurements: String) {
    val passed: Boolean get() = errors.isEmpty()
}

internal object ProjectionImageOracle {
    private const val GUARD = 4
    private const val MIN_INTERIOR = 8

    /** Native input qualification is independent of JPEG output and precedes each image verdict. */
    fun qualifyNative(bitmap: Bitmap, scene: ProjectionScene): ProjectionImageVerdict {
        val errors = ArrayList<String>()
        if (bitmap.width != scene.display.width || bitmap.height != scene.display.height) {
            errors += "Native screenshot ${bitmap.width}x${bitmap.height} differs from measured display ${scene.display}"
            return ProjectionImageVerdict(errors, "Native coordinate system is unqualified")
        }
        val measurements = StringBuilder()
        for (patch in scene.patches) {
            val rect = interior(patch.rect)
            if (rect == null) {
                errors += "Native patch too small: ${patch.label} ${patch.rect}"
                continue
            }
            sample(bitmap, rect, patch.color, 2.0, 6.0, patch.label, errors, measurements)
        }
        return ProjectionImageVerdict(errors, measurements.toString())
    }

    fun inspect(frame: OwnedProjectionFrame, expected: ProjectionExpectation): ProjectionImageVerdict {
        val errors = ArrayList<String>()
        val options = BitmapFactory.Options()
        val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, options)
            ?: return ProjectionImageVerdict(listOf("BitmapFactory rejected JPEG"), "bytes=${frame.jpeg.size}")
        try {
            if (options.outMimeType != "image/jpeg") errors += "Decoded MIME=${options.outMimeType}"
            if (bitmap.width != expected.width || bitmap.height != expected.height) {
                errors += "Decoded ${bitmap.width}x${bitmap.height}; expected ${expected.width}x${expected.height}"
                return ProjectionImageVerdict(errors, "Geometry failure")
            }
            if (bitmap.hasAlpha()) errors += "JPEG decoder reported alpha-bearing output"
            val measurements = StringBuilder()
            var bases = 0
            var epochs = 0
            val rows = HashSet<String>()
            val columns = HashSet<String>()
            val retainedGrayMeans = ArrayList<Pair<Int, Double>>()
            for (patch in expected.scene.patches) {
                val clipped = PixelRect(
                    max(patch.rect.left, expected.source.left), max(patch.rect.top, expected.source.top),
                    min(patch.rect.right, expected.source.right), min(patch.rect.bottom, expected.source.bottom)
                )
                if (clipped.width <= 0 || clipped.height <= 0) continue
                val mapped = PixelRect(
                    ceil((clipped.left - expected.source.left).toDouble() * expected.width / expected.source.width).toInt(),
                    ceil((clipped.top - expected.source.top).toDouble() * expected.height / expected.source.height).toInt(),
                    floor((clipped.right - expected.source.left).toDouble() * expected.width / expected.source.width).toInt(),
                    floor((clipped.bottom - expected.source.top).toDouble() * expected.height / expected.source.height).toInt(),
                )
                val rect = interior(mapped) ?: continue
                if (patch.epoch) epochs++ else bases++
                rows += patch.label.substringAfter("-r").substringBefore("-c")
                columns += patch.label.substringAfter("-c")
                val means = sample(bitmap, rect, patch.color, 24.0, 36.0, patch.label, errors, measurements)
                val red = patch.color shr 16 and 255
                if (!patch.epoch && red == (patch.color shr 8 and 255) && red == (patch.color and 255)) {
                    retainedGrayMeans += red to means.average()
                }
            }
            // Even restricted-source stages must check distributed retained content and scene identity.
            if (bases < 4 || epochs < 4 || rows.size < 2 || columns.size < 2) {
                errors += "Insufficient guarded coverage: bases=$bases epochs=$epochs rows=$rows columns=$columns"
            }
            for ((lower, upper) in retainedGrayMeans.sortedBy { it.first }.zipWithNext()) {
                if (upper.second - lower.second < 32.0) {
                    errors += "Retained neutral levels $lower -> $upper have separation below32"
                }
            }
            measurements.appendLine("coverage: bases=$bases epochs=$epochs rows=$rows columns=$columns")
            return ProjectionImageVerdict(errors, measurements.toString())
        } finally {
            bitmap.recycle()
        }
    }

    private fun interior(rect: PixelRect): PixelRect? {
        val result = PixelRect(rect.left + GUARD, rect.top + GUARD, rect.right - GUARD, rect.bottom - GUARD)
        return result.takeIf { it.width >= MIN_INTERIOR && it.height >= MIN_INTERIOR }
    }

    private fun sample(
        bitmap: Bitmap,
        rect: PixelRect,
        color: Int,
        tileLimit: Double,
        rowLimit: Double,
        label: String,
        errors: MutableList<String>,
        measurements: StringBuilder,
    ): List<Double> {
        check(rect.left >= 0 && rect.top >= 0 && rect.right <= bitmap.width && rect.bottom <= bitmap.height)
        val expected = intArrayOf(color shr 16 and 255, color shr 8 and 255, color and 255)
        val totalErrors = LongArray(3)
        val sums = LongArray(3)
        var badRows = 0
        var badAlpha = false
        for (y in rect.top until rect.bottom) {
            val rowErrors = LongArray(3)
            for (x in rect.left until rect.right) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 != 255) badAlpha = true
                val actual = intArrayOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255)
                for (channel in 0..2) {
                    val error = abs(actual[channel] - expected[channel]).toLong()
                    rowErrors[channel] += error
                    totalErrors[channel] += error
                    sums[channel] += actual[channel]
                }
            }
            if (rowErrors.any { it.toDouble() / rect.width > rowLimit }) badRows++
        }
        val count = rect.width.toLong() * rect.height
        val mae = totalErrors.map { it.toDouble() / count }
        val means = sums.map { it.toDouble() / count }
        if (mae.any { it > tileLimit }) errors += "$label interior MAE=$mae exceeds $tileLimit"
        if (badRows > 0) errors += "$label $badRows interior rows exceed MAE $rowLimit"
        if (badAlpha) errors += "$label nonopaque pixels"
        if (expected[0] == expected[1] && expected[1] == expected[2] && means.max() - means.min() > 8.0) {
            errors += "$label neutral channel spread=$means exceeds 8"
        }
        measurements.appendLine("$label rect=$rect expected=${Integer.toHexString(color)} mae=$mae means=$means badRows=$badRows")
        return means
    }
}
