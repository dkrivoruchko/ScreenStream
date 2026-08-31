package io.screenstream.capture.internal.session.production

import io.screenstream.capture.ScreenCaptureParameters

internal class SessionProductionRecord(
    private val owner: SessionProduction,
    internal val configRevision: Long,
    internal val jpegQuality: Int,
) {
    init {
        require(configRevision > 0L)
        require(jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE)
    }

    internal fun belongsTo(expectedOwner: SessionProduction): Boolean = owner === expectedOwner
}
