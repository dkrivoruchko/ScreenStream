package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind

/**
 * Prepares and validates an encoder instance without invoking image encoding.
 *
 * Provider construction runs through [ProviderPreparationContext]. The returned encoder is accepted
 * only while the owning [PlanPreparationToken] is still current; stale successes are closed through
 * isolated provider cleanup and reported as unavailable preparation.
 */
internal class ImageEncoderPreparer internal constructor(
    private val providerContext: ProviderPreparationContext,
    private val timeoutMs: Long = ENCODER_CREATE_TIMEOUT_MS,
) {
    internal suspend fun prepare(
        token: PlanPreparationToken,
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
    ): ImageEncoderPreparationResult {
        val providerToken = token.newProviderPreparationToken()
        return try {
            when (val result = prepare(token = providerToken, provider = provider, request = request)) {
                is ImageEncoderPreparationResult.Failure -> result
                is ImageEncoderPreparationResult.Success -> {
                    if (token.isCurrent) {
                        result
                    } else {
                        result.preparedEncoder.close()
                        ImageEncoderPreparationResult.Failure(
                            kind = ScreenCaptureProblemKind.EncoderUnavailable,
                            message = "ImageEncoderProvider.createEncoder transaction is stale.",
                            cause = null,
                        )
                    }
                }
            }
        } finally {
            token.detachProviderPreparationToken(providerToken)
        }
    }

    internal suspend fun prepare(
        token: ProviderPreparationToken,
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
    ): ImageEncoderPreparationResult =
        when (
            val created = providerContext.createEncoder(
                token = token,
                provider = provider,
                request = request,
                timeoutMs = timeoutMs,
                validateEncoder = { encoder ->
                    validateCreatedEncoder(
                        provider = provider,
                        request = request,
                        encoder = encoder,
                    )
                },
            )
        ) {
            is ProviderCreateEncoderResult.Failure -> ImageEncoderPreparationResult.Failure(
                kind = created.kind,
                message = created.message,
                cause = created.cause,
            )

            is ProviderCreateEncoderResult.Success -> ImageEncoderPreparationResult.Success(
                PreparedImageEncoderResources(
                    encoder = created.encoder,
                    info = created.info,
                    request = request,
                    cleanup = providerContext,
                ),
            )
        }

    private fun validateCreatedEncoder(
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
        encoder: ImageEncoder,
    ): ProviderCreateEncoderResult {
        val info = try {
            encoder.info
        } catch (throwable: Throwable) {
            return providerValidationFailed("ImageEncoder.info could not be read.", throwable)
        }

        val validationFailure = validateInfoAndRequest(
            providerId = provider.id,
            providerOutputFormat = provider.outputFormat,
            request = request,
            info = info,
        )
        return if (validationFailure == null) {
            ProviderCreateEncoderResult.Success(
                encoder = encoder,
                info = info,
            )
        } else {
            providerValidationFailed(validationFailure, null)
        }
    }

    private fun validateInfoAndRequest(
        providerId: String,
        providerOutputFormat: EncodedImageFormat,
        request: ImageEncoderRequest,
        info: ImageEncoderInfo,
    ): String? {
        if (info.providerId != providerId) {
            return "ImageEncoder.info.providerId did not match ImageEncoderProvider.id."
        }
        if (info.outputFormat != providerOutputFormat) {
            return "ImageEncoder.info.outputFormat did not match ImageEncoderProvider.outputFormat."
        }
        if (request.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque) {
            return "ImageEncoderRequest.inputFormat is not supported."
        }
        if (request.rowStrideBytes.toLong() < request.width.toLong() * RGBA_8888_BYTES_PER_PIXEL) {
            return "ImageEncoderRequest.rowStrideBytes is incompatible with width and input format."
        }
        if (request.maxEncodedBytes !in MAX_ENCODED_BYTES_RANGE) {
            return "ImageEncoderRequest.maxEncodedBytes is outside supported limits."
        }
        return null
    }

    private fun providerValidationFailed(message: String, cause: Throwable?): ProviderCreateEncoderResult.Failure =
        ProviderCreateEncoderResult.Failure(
            kind = ScreenCaptureProblemKind.EncoderValidationFailed,
            message = message,
            cause = cause,
        )
}

internal class PreparedImageEncoderResources internal constructor(
    internal val encoder: ImageEncoder,
    internal val info: ImageEncoderInfo,
    internal val request: ImageEncoderRequest,
    private val cleanup: ProviderEncoderCleanup,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        cleanup.closeEncoderAsync(encoder)
    }
}

internal sealed class ImageEncoderPreparationResult private constructor() {
    internal class Success internal constructor(
        internal val preparedEncoder: PreparedImageEncoderResources,
    ) : ImageEncoderPreparationResult()

    internal class Failure internal constructor(
        internal val kind: ScreenCaptureProblemKind,
        internal val message: String,
        internal val cause: Throwable?,
    ) : ImageEncoderPreparationResult()
}

private const val RGBA_8888_BYTES_PER_PIXEL: Long = 4L
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456
