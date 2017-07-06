package info.dvkr.screenstream.model.image

import android.content.Context
import android.graphics.*
import info.dvkr.screenstream.R
import info.dvkr.screenstream.model.ImageNotify
import java.io.ByteArrayOutputStream

// TODO Implement
class ImageNotifyImpl(context: Context) : ImageNotify {
    private val TAG = "ImageNotifyImpl"

    private var mImageDefault: ByteArray? = null
    private var mImageReloadPage: ByteArray? = null
    private var mImageNewAddress: ByteArray? = null

    private val mDefaultText: String = context.getString(R.string.image_generator_press)
    private val mReloadPageText: String = context.getString(R.string.image_generator_reload_this_page)
    private val mNewAddressText: String = context.getString(R.string.image_generator_go_to_new_address)

    override fun getImage(imageType: String): ByteArray {
        println(TAG + ": Thread [" + Thread.currentThread().name + "] getImage: " + imageType)

        when (imageType) {
            ImageNotify.IMAGE_TYPE_DEFAULT -> {
                if (null == mImageDefault) mImageDefault = generateImage(mDefaultText)
                return mImageDefault!!
            }

            ImageNotify.IMAGE_TYPE_RELOAD_PAGE -> {
                if (null == mImageReloadPage) mImageReloadPage = generateImage(mReloadPageText)
                return mImageReloadPage!!
            }

            ImageNotify.IMAGE_TYPE_NEW_ADDRESS -> {
                if (null == mImageNewAddress) mImageNewAddress = generateImage(mNewAddressText)
                return mImageNewAddress!!
            }

            else -> return ByteArray(0)
        }
    }

    private fun generateImage(text1: String): ByteArray {
        val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRGB(255, 255, 255)
        val screenScale = 1

        val textSize: Int
        val x: Int
        val y: Int

        val bounds = Rect()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if ("" != text1) {
            textSize = 12 * screenScale
            paint.textSize = textSize.toFloat()
            paint.color = Color.BLACK
            paint.getTextBounds(text1, 0, text1.length, bounds)
            x = (bitmap.width - bounds.width()) / 2
            y = (bitmap.height + bounds.height()) / 2 - 2 * textSize
            canvas.drawText(text1, x.toFloat(), y.toFloat(), paint)
        }

        //        if (!"".equals(text2)) {
        //            textSize = (int) (16 * screenScale);
        //            paint.setTextSize(textSize);
        //            paint.setColor(Color.rgb(153, 50, 0));
        //            paint.getTextBounds(text2, 0, text2.length(), bounds);
        //            x = (bitmap.getWidth() - bounds.width()) / 2;
        //            y = (bitmap.getHeight() + bounds.height()) / 2;
        //            canvas.drawText(text2.toUpperCase(), x, y, paint);
        //        }

        //        if (!"".equals(text3)) {
        //            textSize = (int) (12 * screenScale);
        //            paint.setTextSize(textSize);
        //            paint.setColor(Color.BLACK);
        //            paint.getTextBounds(text3, 0, text3.length(), bounds);
        //            x = (bitmap.getWidth() - bounds.width()) / 2;
        //            y = (bitmap.getHeight() + bounds.height()) / 2 + 2 * textSize;
        //            canvas.drawText(text3, x, y, paint);
        //        }

        val jpegOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, jpegOutputStream)
        val jpegByteArray = jpegOutputStream.toByteArray()
        bitmap.recycle()
        return jpegByteArray
    }
}