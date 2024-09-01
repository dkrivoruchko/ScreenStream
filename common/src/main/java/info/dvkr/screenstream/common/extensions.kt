package info.dvkr.screenstream.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import io.nayuki.qrcodegen.QrCode

public fun Any.getLog(tag: String? = "", msg: String? = "Invoked"): String =
    "${this.javaClass.simpleName}#${this.hashCode()}.$tag@${Thread.currentThread().name}: $msg"

public fun randomString(size: Int, allowCapitalLetters: Boolean = false): String {
    val symbols = ('0'..'9') + ('a'..'z') + if (allowCapitalLetters) ('A'..'Z') else emptyList()
    return String(CharArray(size) { symbols.random() })
}

public fun String.generateQRBitmap(sizePx: Int): Bitmap {
    val qrCode = QrCode.encodeText(this, QrCode.Ecc.MEDIUM)
    val scale = sizePx / qrCode.size.toFloat()
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            val module = qrCode.getModule((x / scale).toInt(), (y / scale).toInt())
            pixels[y * sizePx + x] = if (module) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}

public fun Context.findActivity(): Activity {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    throw IllegalStateException("No Activity found from context")
}

public fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

public fun Activity.shouldShowPermissionRationale(permission: String): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

public fun Context.getVersionName(packageName: String = this.packageName, fallback: String = "unknown"): String = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) packageManager.getPackageInfo(packageName, 0).versionName
    else packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
}.getOrNull() ?: fallback

public fun Context.openStringUrl(url: String, onError: (Throwable) -> Unit = {}) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        XLog.w(getLog("openStringUrl", url))
        onError.invoke(it)
    }
}