package info.dvkr.screenstream.logger

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.content.FileProvider
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogUtils
import com.elvishew.xlog.XLog
import com.elvishew.xlog.flattener.ClassicFlattener
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import com.elvishew.xlog.printer.file.naming.FileNameGenerator
import com.jakewharton.processphoenix.ProcessPhoenix
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getVersionName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object AppLogger {

    private lateinit var sharedPreferences: SharedPreferences

    @Volatile
    internal var isLoggingOn: Boolean = false
        private set

    @MainThread
    internal fun init(context: Context, configureLogger: (LogConfiguration.Builder) -> Unit) {
        sharedPreferences = context.getSharedPreferences("info.dvkr.screenstream_logging", Application.MODE_PRIVATE)
        isLoggingOn = sharedPreferences.getBoolean("loggingOn", false)

        val logConfiguration = LogConfiguration.Builder()
            .tag("SSApp")
            .apply { configureLogger(this) }
            .build()

        var printers = emptyArray<Printer>()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) printers = printers.plus(AndroidPrinter())
        if (isLoggingOn) {
            val filePrinter = FilePrinter.Builder(context.logFolder)
                .fileNameGenerator(DateSuffixFileNameGenerator(context.hashCode().toString()))
                .cleanStrategy(FileLastModifiedCleanStrategy(86400000)) // One day
                .flattener(ClassicFlattener())
                .build()
            printers = printers.plus(filePrinter)
        }

        XLog.init(logConfiguration, *printers)
    }

    @MainThread
    @SuppressLint("ApplySharedPref")
    @OptIn(DelicateCoroutinesApi::class)
    internal fun enableLogging(context: Context) {
        if (isLoggingOn) return
        isLoggingOn = true
        Toast.makeText(context, context.getString(R.string.app_logs_enabled), Toast.LENGTH_LONG).show()
        GlobalScope.launch {
            cleanLogFiles(context)
            sharedPreferences.edit().putBoolean("loggingOn", true).commit()
        }.invokeOnCompletion {
            ProcessPhoenix.triggerRebirth(context)
        }
    }

    @MainThread
    @SuppressLint("ApplySharedPref")
    @OptIn(DelicateCoroutinesApi::class)
    internal fun disableLogging(context: Context) {
        if (isLoggingOn.not()) return
        isLoggingOn = false
        Toast.makeText(context, context.getString(R.string.app_logs_disabled), Toast.LENGTH_LONG).show()
        GlobalScope.launch {
            sharedPreferences.edit().putBoolean("loggingOn", false).commit()
            cleanLogFiles(context)
        }.invokeOnCompletion {
            ProcessPhoenix.triggerRebirth(context)
        }
    }

    @MainThread
    internal suspend fun sendLogsInEmail(context: Context, text: String) {
        val logZipFile = withContext(Dispatchers.Default) {
            val logZipFile = context.logZipFile
            LogUtils.compress(context.logFolder, logZipFile)
            logZipFile
        }

        val fileUri = FileProvider.getUriForFile(context, "info.dvkr.screenstream.fileprovider", File(logZipFile))
        val version = context.getVersionName()
        val versions = "Device: ${Build.MANUFACTURER} ${Build.MODEL} [API:${Build.VERSION.SDK_INT}, Build:$version]"
        val emailIntent = Intent(Intent.ACTION_SEND)
            .setType("vnd.android.cursor.dir/email")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("Dmytro Kryvoruchko <dkrivoruchko@gmail.com>"))
            .putExtra(Intent.EXTRA_SUBJECT, "Screen Stream Logs ($version)")
            .putExtra(Intent.EXTRA_TEXT, "$versions \n\n Issue description: \n\n $text")
            .putExtra(Intent.EXTRA_STREAM, fileUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        runCatching {
            context.startActivity(
                Intent.createChooser(emailIntent, context.getString(R.string.app_tab_about_email_chooser_header))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun cleanLogFiles(context: Context) {
        runCatching { File(context.logFolder).let { if (it.exists()) it.deleteRecursively() } }
        runCatching { File(context.logZipFolder).let { if (it.exists()) it.deleteRecursively() } }
    }

    private val Context.logFolder: String
        get() = cacheDir.absolutePath + "/logs/"

    private val Context.logZipFolder: String
        get() = filesDir.absolutePath + "/logs/"

    private val Context.logZipFile: String
        get() = logZipFolder + "logs.zip"

    private class DateSuffixFileNameGenerator(private val suffix: String) : FileNameGenerator {
        private val localDateFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            }
        }

        override fun generateFileName(logLevel: Int, timestamp: Long): String {
            val date = localDateFormat.get()?.apply { timeZone = TimeZone.getDefault() }?.format(Date(timestamp))
            return "$date-$suffix.log"
        }

        override fun isFileNameChangeable(): Boolean = true
    }
}