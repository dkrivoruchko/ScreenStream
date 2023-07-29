package info.dvkr.screenstream.mjpeg

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
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

//fun InetSocketAddress?.asString(): String = "${this?.hostName?.let { it + "\n" }}${this?.address?.asString()}:${this?.port}"

//internal fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()

internal fun randomPin(): String = Random.nextInt(10).toString() + Random.nextInt(10).toString() +
        Random.nextInt(10).toString() + Random.nextInt(10).toString() +
        Random.nextInt(10).toString() + Random.nextInt(10).toString()