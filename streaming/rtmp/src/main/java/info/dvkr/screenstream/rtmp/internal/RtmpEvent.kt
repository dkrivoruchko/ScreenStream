package info.dvkr.screenstream.rtmp.internal

import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import kotlin.uuid.Uuid

/** Process-local commands and observations; Android only transports native module startup. */
internal open class RtmpEvent(val priority: Int) {
    internal object Priority {
        internal const val RESTART_IGNORE: Int = 10
        internal const val RECOVER_IGNORE: Int = 20
        internal const val START_PROJECTION: Int = 21
        internal const val DESTROY_IGNORE: Int = 30
    }

    internal data class StopStream(
        val reason: String,
        val expectedAttempt: StreamingModule.CaptureAttemptId? = null,
    ) : RtmpEvent(Priority.RECOVER_IGNORE)

    internal data class RecoverError(val errorToken: Uuid? = null) : RtmpEvent(Priority.RECOVER_IGNORE)
    internal data class StartProjection(
        val attempt: StreamingModule.CaptureAttemptId,
        val grant: ScreenCaptureAccess.Grant,
    ) : RtmpEvent(Priority.START_PROJECTION)

    internal data class ConsentNotGranted(
        val attempt: StreamingModule.CaptureAttemptId,
        val reason: ScreenCaptureAccess.NoGrantReason,
    ) : RtmpEvent(Priority.DESTROY_IGNORE)

    internal data class ConsentFailed(val attempt: StreamingModule.CaptureAttemptId, val cause: Throwable) : RtmpEvent(Priority.DESTROY_IGNORE)
    internal data class RetryConsent(val token: Uuid, val retry: Boolean) : RtmpEvent(Priority.RECOVER_IGNORE)
}
