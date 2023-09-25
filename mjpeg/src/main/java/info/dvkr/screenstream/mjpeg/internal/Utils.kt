package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.random.Random

internal fun Context.getFileFromAssets(fileName: String): ByteArray {
    XLog.d(getLog("getFileFromAssets", fileName))

    assets.open(fileName).use { inputStream ->
        val fileBytes = ByteArray(inputStream.available())
        inputStream.read(fileBytes)
        fileBytes.isNotEmpty() || throw IllegalStateException("$fileName is empty")
        return fileBytes
    }
}

internal fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
    distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

//fun InetSocketAddress?.asString(): String = "${this?.hostName?.let { it + "\n" }}${this?.address?.asString()}:${this?.port}"

//internal fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()
