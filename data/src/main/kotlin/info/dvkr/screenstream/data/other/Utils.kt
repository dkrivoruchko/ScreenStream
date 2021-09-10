package info.dvkr.screenstream.data.other

import android.content.Context
import android.graphics.Bitmap
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import com.elvishew.xlog.XLog
import io.nayuki.qrcodegen.QrCode
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

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

fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()

fun InetAddress.asString(): String = if (this is Inet6Address) "[${this.hostAddress}]" else this.hostAddress ?: ""

fun InetSocketAddress.asString(): String = "${this.hostName?.let { it + "\n" }}${this.address.asString()}:${this.port}"

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
        val qrCode = QrCode.encodeText(this, QrCode.Ecc.MEDIUM)
        val scale = size / qrCode.size
        val pixels = IntArray(size * size).apply { fill(0xFFFFFFFF.toInt()) }
        for (y in 0 until size)
            for (x in 0 until size)
                if (qrCode.getModule(x / scale, y / scale)) pixels[y * size + x] = 0xFF000000.toInt()

        val border = 16
        Bitmap.createBitmap(size + border, size + border, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, border, border, size, size)
        }
    } catch (ex: Exception) {
        XLog.e(getLog("String.getQRBitmap", ex.toString()))
        null
    }