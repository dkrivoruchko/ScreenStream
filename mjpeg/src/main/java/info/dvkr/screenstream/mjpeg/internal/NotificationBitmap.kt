package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegKoinScope
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.server.HttpServerFiles
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope

@Scope(MjpegKoinScope::class)
internal class NotificationBitmap(@InjectedParam context: Context) {

    enum class Type { START, NEW_ADDRESS, ADDRESS_BLOCKED }

    private val applicationContext: Context = context.applicationContext

    private val logo: Bitmap by lazy {
        val logoByteArray = applicationContext.getFileFromAssets(HttpServerFiles.LOGO_PNG)
        val logoBitmap = BitmapFactory.decodeByteArray(logoByteArray, 0, logoByteArray.size)
        Bitmap.createScaledBitmap(logoBitmap, 256, 256, false)
    }

    private val startBitmap by lazy { generateImage(applicationContext.getString(R.string.mjpeg_image_generator_press_start), logo) }
    private val newAddressBitmap by lazy { generateImage(applicationContext.getString(R.string.mjpeg_image_generator_go_to_new_address), logo) }
    private val addressBlockedBitmap by lazy { generateImage(applicationContext.getString(R.string.mjpeg_image_generator_address_blocked), logo) }

    init {
        XLog.d(getLog("init"))
    }

    internal fun getNotificationBitmap(notificationType: Type): Bitmap {
        XLog.d(getLog("getNotificationBitmap", "Type: $notificationType"))

        return when (notificationType) {
            Type.START -> startBitmap
            Type.NEW_ADDRESS -> newAddressBitmap
            Type.ADDRESS_BLOCKED -> addressBlockedBitmap
        }
    }

    private fun generateImage(message: String, logo: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
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