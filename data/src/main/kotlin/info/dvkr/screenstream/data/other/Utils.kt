package info.dvkr.screenstream.data.other

import android.content.Context
import android.graphics.Bitmap
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import com.elvishew.xlog.XLog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*

fun Any.getLog(tag: String? = "", msg: String? = "Invoked") =
    "${this.javaClass.simpleName}#${this.hashCode()}.$tag@${Thread.currentThread().name}: $msg"

fun String.setColorSpan(color: Int, start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
    setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

fun String.setUnderlineSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
    setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

fun randomString(len: Int): String {
    val chars = CharArray(len)
    val symbols = "0123456789abcdefghijklmnopqrstuvwxyz"
    for (i in 0 until len) chars[i] = symbols.random()
    return String(chars)
}

fun Long.bytesToMbit() = (this * 8).toFloat() / 1024 / 1024

fun InetAddress.asString(): String =
    if (this is Inet6Address) "[${this.hostAddress}]"
    else this.hostAddress

fun InetSocketAddress.asString(): String = "${this.address.asString()}:${this.port}"

fun Context.getFileFromAssets(fileName: String): ByteArray {
    XLog.d(getLog("getFileFromAssets", fileName))

    assets.open(fileName).use { inputStream ->
        val fileBytes = ByteArray(inputStream.available())
        inputStream.read(fileBytes)
        fileBytes.isNotEmpty() || throw IllegalStateException("$fileName is empty")
        return fileBytes
    }
}

fun String.getQRBitmap(size: Int): Bitmap? =
    try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }

        val bitMatrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, size, size, hints)
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val pixels = IntArray(bitMatrix.width * bitMatrix.height)
        for (y in 0 until bitMatrix.height) {
            val offset = y * bitMatrix.width
            for (x in 0 until bitMatrix.width) pixels[offset + x] = if (bitMatrix.get(x, y)) black else white
        }

        Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (ex: Exception) {
        XLog.e(getLog("String.getQRBitmap", ex.toString()))
        null
    }