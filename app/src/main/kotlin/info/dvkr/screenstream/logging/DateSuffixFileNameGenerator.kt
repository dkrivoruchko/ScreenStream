package info.dvkr.screenstream.logging

import com.elvishew.xlog.printer.file.naming.FileNameGenerator
import java.text.SimpleDateFormat
import java.util.*

internal class DateSuffixFileNameGenerator(private val suffix: String) : FileNameGenerator {
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