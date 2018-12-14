package info.dvkr.screenstream.data.other

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

fun Any.getTag(name: String? = "") =
    "${this.javaClass.simpleName}#${this.hashCode()}.$name@${Thread.currentThread().name}"

fun String.setColorSpan(color: Int, start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
    setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

fun randomString(len: Int): String {
    val chars = CharArray(len)
    val symbols = "0123456789abcdefghijklmnopqrstuvwxyz"
    for (i in 0 until len) chars[i] = symbols.random()
    return String(chars)
}
