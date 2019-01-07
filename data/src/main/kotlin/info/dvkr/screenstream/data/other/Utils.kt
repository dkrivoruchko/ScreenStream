package info.dvkr.screenstream.data.other

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

fun Any.getLog(tag: String? = "", msg: String? = "") =
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