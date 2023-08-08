package info.dvkr.screenstream.logging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.elvishew.xlog.LogUtils
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

internal fun Context.getLogFolder(): String = this.cacheDir.absolutePath
internal fun Context.getLogZipFolder(): String = "${this.filesDir.absolutePath}/logs/"
internal fun Context.getLogZipFile(): String = this.getLogZipFolder() + "logs.zip"

@OptIn(DelicateCoroutinesApi::class)
@Suppress("BlockingMethodInNonBlockingContext")
internal fun sendLogsInEmail(context: Context, text: String) {
    GlobalScope.launch {
        LogUtils.compress(context.getLogFolder(), context.getLogZipFile())

        val fileUri: Uri = FileProvider.getUriForFile(
            context, "info.dvkr.screenstream.fileprovider", File(context.getLogZipFile())
        )

        var version = ""
        try {
            version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (t: Throwable) {
            XLog.e(getLog("sendLogsInEmail", "getPackageInfo"), t)
        }

        val versions = "Device: ${Build.MANUFACTURER} ${Build.MODEL} [API:${Build.VERSION.SDK_INT}, Build:$version]"

        val emailIntent = Intent(Intent.ACTION_SEND)
            .setType("vnd.android.cursor.dir/email")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"))
            .putExtra(Intent.EXTRA_SUBJECT, "Screen Stream Logs ($version)")
            .putExtra(Intent.EXTRA_TEXT, "$versions \n\n Issue description: \n\n $text")
            .putExtra(Intent.EXTRA_STREAM, fileUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startActivity(
            Intent.createChooser(
                emailIntent, context.getString(R.string.about_fragment_email_chooser_header)
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun cleanLogFiles(context: Context) {
    try {
        val logFile = File(context.getLogZipFolder())
        if (logFile.exists()) logFile.deleteRecursively()
    } catch (ignore: Throwable) {
    }
}