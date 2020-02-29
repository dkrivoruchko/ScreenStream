package info.dvkr.screenstream.data.image

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class BitmapToJpeg(
    private val settingsReadOnly: SettingsReadOnly,
    private val inBitmapChannel: ReceiveChannel<Bitmap>,
    private val outJpegChannel: SendChannel<ByteArray>,
    onError: (AppError) -> Unit
) : AbstractImageHandler(onError) {

    private val resultJpegStream = ByteArrayOutputStream()
    private val jpegQuality = AtomicInteger(100)

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key == Settings.Key.JPEG_QUALITY) {
                XLog.d(this@BitmapToJpeg.getLog("onSettingsChanged", "jpegQuality: ${settingsReadOnly.jpegQuality}"))
                jpegQuality.set(settingsReadOnly.jpegQuality)
            }
        }
    }

    init {
        XLog.d(getLog("init", "Invoked"))
        settingsReadOnly.registerChangeListener(settingsListener)
        jpegQuality.set(settingsReadOnly.jpegQuality)
    }

    override fun start() {
        XLog.d(getLog("start", "Invoked"))
        super.start()
        coroutineScope.launch {
            for (bitmap in inBitmapChannel) {
                ensureActive()
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.get(), resultJpegStream)
                ensureActive()
                if (outJpegChannel.isClosedForSend.not()) outJpegChannel.offer(resultJpegStream.toByteArray())
                resultJpegStream.reset()
                ensureActive()
            }
        }
    }

    override fun destroy() {
        settingsReadOnly.unregisterChangeListener(settingsListener)
        super.destroy()
    }
}