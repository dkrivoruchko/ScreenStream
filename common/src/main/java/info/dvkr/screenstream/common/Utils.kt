package info.dvkr.screenstream.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

public fun Any.getLog(tag: String? = "", msg: String? = "Invoked"): String =
    "${this.javaClass.simpleName}#${this.hashCode()}.$tag@${Thread.currentThread().name}: $msg"

public fun randomString(len: Int, allowCapitalLetters: Boolean = false): String {
    val chars = CharArray(len)
    val symbols = if (allowCapitalLetters) "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    else "0123456789abcdefghijklmnopqrstuvwxyz"
    for (i in 0 until len) chars[i] = symbols.random()
    return String(chars)
}

public fun Context.getAppVersion(): String = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) packageManager.getPackageInfo(packageName, 0).versionName
    else packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
}.getOrDefault("unknown")