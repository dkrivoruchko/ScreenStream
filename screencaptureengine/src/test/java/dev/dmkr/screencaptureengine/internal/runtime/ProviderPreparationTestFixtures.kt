package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest

internal class FakeImageEncoderProvider(
    override val id: String = "fake-provider",
    override val outputFormat: EncodedImageFormat = EncodedImageFormats.Jpeg,
) : ImageEncoderProvider {
    val createRequests = mutableListOf<ImageEncoderRequest>()
    val createThreadNames = mutableListOf<String>()
    var createFailure: Throwable? = null
    var encoderFactory: (ImageEncoderRequest) -> ImageEncoder = {
        FakeImageEncoder(
            info = ImageEncoderInfo(
                providerId = id,
                outputFormat = outputFormat,
                backendName = "fake",
            ),
        )
    }
    var beforeCreate: (() -> Unit)? = null

    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder {
        createRequests += request
        createThreadNames += Thread.currentThread().name
        beforeCreate?.invoke()
        createFailure?.let { throw it }
        return encoderFactory(request)
    }
}

internal class FakeImageEncoder(
    override val info: ImageEncoderInfo = ImageEncoderInfo(
        providerId = "fake-provider",
        outputFormat = EncodedImageFormats.Jpeg,
        backendName = "fake",
    ),
) : ImageEncoder {
    var encodeCount = 0
    var closeCount = 0
    var encodeResult: ImageEncodeResult = ImageEncodeResult.Success

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult {
        encodeCount++
        return encodeResult
    }

    override fun close() {
        closeCount++
    }
}

internal object ImmediateProviderEncoderCleanup : ProviderEncoderCleanup {
    override fun closeEncoderAsync(encoder: ImageEncoder) {
        encoder.close()
    }
}

internal class ProviderPreparationThreadGuard(
    private val forbiddenThreadNameFragments: Set<String>,
) {
    fun assertAllowedThread(threadName: String = Thread.currentThread().name) {
        val forbiddenFragment = forbiddenThreadNameFragments.firstOrNull(threadName::contains)
        check(forbiddenFragment == null) {
            "Provider preparation ran on forbidden thread: $threadName"
        }
    }
}
