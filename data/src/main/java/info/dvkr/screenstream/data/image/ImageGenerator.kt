package info.dvkr.screenstream.data.image

import android.support.annotation.IntRange
import android.support.annotation.Keep


interface ImageGenerator {

    @Keep sealed class ImageGeneratorEvent {
        @Keep class OnJpegImage(val image: ByteArray) : ImageGeneratorEvent()
        @Keep class OnError(val error: Throwable) : ImageGeneratorEvent()
    }

    fun setImageResizeFactor(@IntRange(from = 1, to = 150) factor: Int)

    fun setImageJpegQuality(@IntRange(from = 1, to = 100) quality: Int)

    fun start()

    fun stop()
}