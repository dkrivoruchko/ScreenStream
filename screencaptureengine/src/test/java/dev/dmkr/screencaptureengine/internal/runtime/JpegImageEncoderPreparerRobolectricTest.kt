package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.JpegEncoderBackend
import dev.dmkr.screencaptureengine.JpegEncoderBackendPolicy
import dev.dmkr.screencaptureengine.JpegImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JpegImageEncoderPreparerRobolectricTest {
    @Test
    fun defaultJpegProviderPreparesThroughImageEncoderPreparerWithoutSyntheticEncode() = runBlocking {
        val provider = ScreenCaptureParameters.defaults().encoderProvider

        assertTrue(provider is JpegImageEncoderProvider)
        val jpegProvider = provider as JpegImageEncoderProvider
        assertEquals(80, jpegProvider.quality)
        assertEquals(JpegEncoderBackendPolicy.Auto, jpegProvider.backendPolicy)
        assertProviderPreparesThroughImageEncoderPreparer(provider)
    }

    @Test
    fun frameworkOnlyJpegProviderPreparesThroughImageEncoderPreparerWithoutSyntheticEncode() = runBlocking {
        assertProviderPreparesThroughImageEncoderPreparer(
            JpegImageEncoderProvider(backendPolicy = JpegEncoderBackendPolicy.FrameworkOnly),
        )
    }

    private suspend fun assertProviderPreparesThroughImageEncoderPreparer(provider: ImageEncoderProvider) {
        val providerContext = ProviderPreparationContext()
        val request = testEncoderRequest()

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = request,
            )

            assertTrue(result is ImageEncoderPreparationResult.Success)
            val success = result as ImageEncoderPreparationResult.Success
            assertEquals(request, success.preparedEncoder.request)
            assertEquals(
                ImageEncoderInfo(
                    providerId = "jpeg",
                    outputFormat = EncodedImageFormats.Jpeg,
                    backendName = JpegEncoderBackend.FrameworkBitmapCompress.name,
                ),
                success.preparedEncoder.info,
            )
            assertEquals(success.preparedEncoder.info, success.preparedEncoder.encoder.info)
            success.preparedEncoder.close()
        } finally {
            providerContext.close()
        }
    }

    private fun testEncoderRequest(): ImageEncoderRequest =
        ImageEncoderRequest(
            width = 16,
            height = 8,
            rowStrideBytes = 64,
            maxEncodedBytes = 1_024,
            inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
        )
}
