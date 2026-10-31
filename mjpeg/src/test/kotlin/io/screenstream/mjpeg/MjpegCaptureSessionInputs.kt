package io.screenstream.mjpeg

import android.content.Context
import android.media.projection.MediaProjection
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.CoroutineScope
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList

/** Android, consent and foreground inputs only; each test owns its Engine observations and capabilities. */
internal class MjpegCaptureSessionInputs(
    val context: Context = RuntimeEnvironment.getApplication(),
    val projection: MediaProjection = Shadow.newInstanceOf(MediaProjection::class.java),
    val grant: ScreenCaptureAccess.Grant = object : ScreenCaptureAccess.Grant {},
    private val consent: suspend (StreamingModule.CaptureAttemptId) -> ScreenCaptureAccess.Grant? = { grant },
    private val projectionCreation: (ScreenCaptureAccess.Grant) -> MediaProjection = { projection },
    private val promotion: suspend () -> Unit = {},
    private val foregroundRelease: () -> Unit = {},
    private val guardRelease: (StreamingModule.CaptureAttemptId) -> Unit = {},
    private val consentReuse: (ScreenCaptureAccess.Grant) -> Unit = {},
) {
    val requested: MutableList<StreamingModule.CaptureAttemptId> = CopyOnWriteArrayList()
    val projections: MutableList<ScreenCaptureAccess.Grant> = CopyOnWriteArrayList()
    val finished: MutableList<StreamingModule.CaptureAttemptId> = CopyOnWriteArrayList()
    val saved: MutableList<ScreenCaptureAccess.Grant> = CopyOnWriteArrayList()
    val foregroundEvents: MutableList<String> = CopyOnWriteArrayList()

    val access: ScreenCaptureAccess = object : ScreenCaptureAccess {
        override suspend fun requestConsent(attempt: StreamingModule.CaptureAttemptId): ScreenCaptureAccess.Grant? {
            requested += attempt
            return consent(attempt)
        }

        override fun createProjection(grant: ScreenCaptureAccess.Grant): MediaProjection {
            projections += grant
            return projectionCreation(grant)
        }

        override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
            finished += attempt
            guardRelease(attempt)
        }

        override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) {
            saved += grant
            consentReuse(grant)
        }
    }

    fun session(
        scope: CoroutineScope,
        engineFactory: MjpegEngineFactory,
        onJpeg: (Int, (ByteArray) -> Unit) -> Unit = { _, _ -> error("No JPEG is expected in this history") },
        id: StreamingModule.CaptureAttemptId = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))),
    ): MjpegCaptureSession = MjpegCaptureSession(
        id = id,
        context = context,
        controllerScope = scope,
        captureAccess = access,
        enterForeground = {
            foregroundEvents += "enterForeground"
            promotion()
        },
        leaveForeground = {
            foregroundEvents += "leaveForeground"
            foregroundRelease()
        },
        onJpeg = onJpeg,
        engineFactory = engineFactory,
    )
}
