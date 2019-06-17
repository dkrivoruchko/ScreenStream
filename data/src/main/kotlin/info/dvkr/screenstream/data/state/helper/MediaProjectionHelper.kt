package info.dvkr.screenstream.data.state.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog

class MediaProjectionHelper(context: Context, private val onProjectionCallback: () -> Unit) {

    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@MediaProjectionHelper.getLog("Callback", "onStop"))
            onProjectionCallback()
        }
    }

    fun getMediaProjection(intent: Intent): MediaProjection {
        val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intent)
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        return mediaProjection
    }

    fun stopMediaProjection(mediaProjection: MediaProjection?) {
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
    }
}