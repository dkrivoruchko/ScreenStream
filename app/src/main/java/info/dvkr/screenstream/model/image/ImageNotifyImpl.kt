package info.dvkr.screenstream.model.image

import android.content.Context
import android.graphics.*
import info.dvkr.screenstream.R
import info.dvkr.screenstream.model.ImageNotify
import java.io.ByteArrayOutputStream


class ImageNotifyImpl(context: Context) : ImageNotify {
    private val TAG = "ImageNotifyImpl"

    private var imageDefault: ByteArray? = null
    private var imageReloadPage: ByteArray? = null
    private var imageNewAddress: ByteArray? = null


    private val logo: Bitmap
    private val defaultText: String = context.getString(R.string.image_generator_press_start)
    private val reloadPageText: String = context.getString(R.string.image_generator_reload_this_page)
    private val newAddressText: String = context.getString(R.string.image_generator_go_to_new_address)

    init {
        val tempBm = BitmapFactory.decodeResource(context.resources, R.drawable.ic_app)
        logo = Bitmap.createScaledBitmap(tempBm, 192, 192, false)
        tempBm.recycle()
    }

    override fun getImage(imageType: String): ByteArray {
        println(TAG + ": Thread [" + Thread.currentThread().name + "] getImage: " + imageType)

        when (imageType) {
            ImageNotify.IMAGE_TYPE_DEFAULT -> {
                if (null == imageDefault) imageDefault = generateImage(defaultText)
                return imageDefault!!
            }

            ImageNotify.IMAGE_TYPE_RELOAD_PAGE -> {
                if (null == imageReloadPage) imageReloadPage = generateImage(reloadPageText)
                return imageReloadPage!!
            }

            ImageNotify.IMAGE_TYPE_NEW_ADDRESS -> {
                if (null == imageNewAddress) imageNewAddress = generateImage(newAddressText)
                return imageNewAddress!!
            }

            else -> return ByteArray(0)
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