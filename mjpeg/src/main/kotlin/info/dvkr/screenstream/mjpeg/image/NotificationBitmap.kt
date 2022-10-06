package info.dvkr.screenstream.mjpeg.image

import android.content.Context
import android.graphics.*
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.getFileFromAssets
import info.dvkr.screenstream.mjpeg.httpserver.HttpServerFiles
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.flow.first


class NotificationBitmap(context: Context, private val mjpegSettings: MjpegSettings) {

    enum class Type { START, RELOAD_PAGE, NEW_ADDRESS, ADDRESS_BLOCKED }

    private val applicationContext: Context = context.applicationContext
    private val logo: Bitmap

    init {
        XLog.d(getLog("init"))

        val logoByteArray = applicationContext.getFileFromAssets(HttpServerFiles.LOGO_PNG)
        val logoBitmap = BitmapFactory.decodeByteArray(logoByteArray, 0, logoByteArray.size)
        logo = Bitmap.createScaledBitmap(logoBitmap, 256, 256, false)
    }

    suspend fun getNotificationBitmap(notificationType: Type): Bitmap {
        XLog.d(getLog("getNotificationBitmap", "Type: $notificationType"))

        val message = when (notificationType) {
            Type.START -> applicationContext.getString(R.string.image_generator_press_start)
            Type.RELOAD_PAGE -> applicationContext.getString(R.string.image_generator_reload_this_page)
            Type.NEW_ADDRESS -> applicationContext.getString(R.string.image_generator_go_to_new_address)
            Type.ADDRESS_BLOCKED -> applicationContext.getString(R.string.image_generator_address_blocked)
        }

        return generateImage(message, logo)
    }

    private suspend fun generateImage(message: String, logo: Bitmap): Bitmap {
        val bitmap: Bitmap
        if (mjpegSettings.htmlShowPressStartFlow.first()) {
            bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
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
        } else {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(mjpegSettings.htmlBackColorFlow.first())
        }
        return bitmap
    }
}