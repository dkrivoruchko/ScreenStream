package info.dvkr.screenstream.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

fun Any.getLog(tag: String? = "", msg: String? = "Invoked") =
    "${this.javaClass.simpleName}#${this.hashCode()}.$tag@${Thread.currentThread().name}: $msg"

fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 1, action: suspend (T) -> Unit) =
    distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

fun randomString(len: Int, allowCapitalLetters: Boolean = false): String {
    val chars = CharArray(len)
    val symbols = if (allowCapitalLetters) "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    else "0123456789abcdefghijklmnopqrstuvwxyz"
    for (i in 0 until len) chars[i] = symbols.random()
    return String(chars)
}