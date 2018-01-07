package info.dvkr.screenstream.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.domain.utils.Utils
import timber.log.Timber
import java.io.ByteArrayOutputStream


class ImageNotifyImpl(context: Context) : ImageNotify {
    private val defaultText: String = context.getString(R.string.image_generator_press_start)
    private val reloadPageText: String = context.getString(R.string.image_generator_reload_this_page)
    private val newAddressText: String = context.getString(R.string.image_generator_go_to_new_address)

    private val imageDefault: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { generateImage(defaultText) }
    private val imageReloadPage: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { generateImage(reloadPageText) }
    private val imageNewAddress: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { generateImage(newAddressText) }

    private val logo: Bitmap

    init {
        val tempBm = BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_icon)
        logo = Bitmap.createScaledBitmap(tempBm, 192, 192, false)
        tempBm.recycle()
    }

    override fun getImage(imageType: String): ByteArray {
        Timber.i("[${Utils.getLogPrefix(this)}] getImage: $imageType")

        return when (imageType) {
            ImageNotify.IMAGE_TYPE_DEFAULT -> imageDefault
            ImageNotify.IMAGE_TYPE_RELOAD_PAGE -> imageReloadPage
            ImageNotify.IMAGE_TYPE_NEW_ADDRESS -> imageNewAddress
            else -> ByteArray(0)
        }
    }

    private fun generateImage(message: String): ByteArray {
        val bitmap = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRGB(69, 90, 100)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(logo, 154f, 16f, paint)

        paint.textSize = 20f
        paint.color = Color.WHITE
        val bounds = Rect()
        paint.getTextBounds(message, 0, message.length, bounds)
        val x = (bitmap.width - bounds.width()) / 2f
        canvas.drawText(message, x, 300f, paint)

        val jpegOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, jpegOutputStream)
        val jpegByteArray = jpegOutputStream.toByteArray()
        bitmap.recycle()
        return jpegByteArray
    }
}