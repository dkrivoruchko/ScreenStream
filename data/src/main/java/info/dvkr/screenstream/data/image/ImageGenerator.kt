package info.dvkr.screenstream.data.image

import android.media.projection.MediaProjection
import android.support.annotation.IntRange
import android.support.annotation.Keep
import android.view.Display
import rx.Scheduler


interface ImageGenerator {

    @Keep sealed class ImageGeneratorEvent {
        @Keep data class OnJpegImage(val image: ByteArray) : ImageGeneratorEvent()
        @Keep data class OnError(val error: Throwable) : ImageGeneratorEvent()
    }

    interface ImageGeneratorBuilder {
        fun create(display: Display,
                   mediaProjection: MediaProjection,
                   scheduler: Scheduler,
                   event: ImageGeneratorEvent.() -> Unit): ImageGenerator
    }

    fun setImageResizeFactor(@IntRange(from = 1, to = 150) factor: Int): ImageGenerator

    fun setImageJpegQuality(@IntRange(from = 1, to = 100) quality: Int): ImageGenerator

    fun start()

    fun stop()
}