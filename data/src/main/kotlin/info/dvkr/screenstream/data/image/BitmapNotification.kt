package info.dvkr.screenstream.data.image

import android.content.Context
import android.graphics.*
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class BitmapNotification(
    context: Context,
    logoBitmap: Bitmap,
    private val outBitmapChannel: SendChannel<Bitmap>,
    onError: (AppError) -> Unit
) : AbstractImageHandler(onError) {

    enum class Type { START, RELOAD_PAGE, NEW_ADDRESS }

    private val applicationContext: Context = context.applicationContext
    private val logo: Bitmap = Bitmap.createScaledBitmap(logoBitmap, 256, 256, false)

    init {
        XLog.d(getLog("init", "Invoked"))
    }

    override fun start() {}

    fun sentBitmapNotification(notificationType: Type) {
        XLog.d(getLog("sentBitmapNotification", "BitmapType: $notificationType"))

        val bitmap: Bitmap = when (notificationType) {
            Type.START -> generateImage(
                applicationContext.getString(R.string.image_generator_press_start), logo
            )
            Type.RELOAD_PAGE -> generateImage(
                applicationContext.getString(R.string.image_generator_reload_this_page), logo
            )
            Type.NEW_ADDRESS -> generateImage(
                applicationContext.getString(R.string.image_generator_go_to_new_address), logo
            )
        }

        launch {
            repeat(3) {
                outBitmapChannel.isClosedForSend.not() || return@launch
                outBitmapChannel.offer(bitmap)
                delay(200)
            }
        }
    }

    private fun generateImage(message: String, logo: Bitmap): Bitmap {
        val bitmap: Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRGB(25, 118, 159)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(logo, 192f, 16f, paint)

        paint.textSize = 24f
        paint.color = Color.WHITE
        val bounds = Rect()
        paint.getTextBounds(message, 0, message.length, bounds)
        val x = (bitmap.width - bounds.width()) / 2f
        canvas.drawText(message, x, 324f, paint)
        return bitmap
    }
}