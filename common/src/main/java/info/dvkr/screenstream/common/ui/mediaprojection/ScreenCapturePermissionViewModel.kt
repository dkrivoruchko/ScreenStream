package info.dvkr.screenstream.common.ui.mediaprojection

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Stable
internal class ScreenCapturePermissionViewModel(
    private val appSettings: AppSettings
) : ViewModel() {

    internal companion object {
        val GRANT_DELIVERY_TIMEOUT = 15.seconds
    }

    @Immutable
    internal data class UIState(
        val showEducationDialog: Boolean = false,
        val showRetryDialog: Boolean = false,
        val launchAttemptId: String? = null,
        val receivedGrantAttemptId: String? = null
    )

    private sealed interface PermissionState {
        data object Idle : PermissionState
        data object EducationRequired : PermissionState
        data class LaunchRequested(val attemptId: String) : PermissionState
        data class WaitingForResult(val attemptId: String) : PermissionState
        data class GrantReceived(val attemptId: String, val intent: Intent, val receivedAtElapsedMs: Long) : PermissionState
        data object RetryRequired : PermissionState
    }

    private var state: PermissionState by mutableStateOf(PermissionState.Idle)

    internal val uiState: UIState
        get() = UIState(
            showEducationDialog = state is PermissionState.EducationRequired,
            showRetryDialog = state is PermissionState.RetryRequired,
            launchAttemptId = (state as? PermissionState.LaunchRequested)?.attemptId,
            receivedGrantAttemptId = (state as? PermissionState.GrantReceived)?.attemptId
        )

    internal fun requestStart(onStartRequested: (Boolean) -> Unit) {
        if (appSettings.data.value.screenCaptureEducationCompleted) {
            state = PermissionState.Idle
            onStartRequested(false)
        } else {
            state = PermissionState.EducationRequired
        }
    }

    internal fun onEducationResult(confirmed: Boolean, onStartRequested: (Boolean) -> Unit) {
        state = PermissionState.Idle
        if (confirmed.not()) return

        viewModelScope.launch { appSettings.updateData { copy(screenCaptureEducationCompleted = true) } }
        onStartRequested(true)
    }

    internal fun onStartAttemptChanged(startAttemptId: String?) {
        if (startAttemptId == null) {
            if (state !is PermissionState.RetryRequired) state = PermissionState.Idle
            return
        }

        val currentAttemptId = (state as? PermissionState.LaunchRequested)?.attemptId
            ?: (state as? PermissionState.WaitingForResult)?.attemptId
            ?: (state as? PermissionState.GrantReceived)?.attemptId
        if (currentAttemptId != startAttemptId) {
            state = PermissionState.LaunchRequested(startAttemptId)
        }
    }

    internal fun onLaunchStarted(startAttemptId: String): Boolean {
        val launchRequested = state as? PermissionState.LaunchRequested ?: return false
        if (launchRequested.attemptId != startAttemptId) return false
        XLog.i(getLog("ScreenCapturePermission", "MP_UI launch id=$startAttemptId"))
        state = PermissionState.WaitingForResult(startAttemptId)
        return true
    }

    internal fun onLaunchFailed(startAttemptId: String, throwable: Throwable, onPermissionDenied: (String) -> Unit) {
        val waitingForResult = state as? PermissionState.WaitingForResult ?: return
        if (waitingForResult.attemptId != startAttemptId) return
        XLog.e(getLog("ScreenCapturePermission", "MP_UI launch_failed id=$startAttemptId"), throwable)
        state = PermissionState.RetryRequired
        onPermissionDenied(startAttemptId)
    }

    internal fun onActivityResult(
        resultCode: Int,
        data: Intent?,
        currentStartAttemptId: String?,
        lifecycleResumed: Boolean,
        onPermissionGranted: (String, Intent) -> Unit,
        onPermissionDenied: (String) -> Unit
    ) {
        val waitingForResult = state as? PermissionState.WaitingForResult
        val attemptId = waitingForResult?.attemptId
        val resultOk = resultCode == Activity.RESULT_OK
        val hasData = data != null
        XLog.i(
            getLog(
                "ScreenCapturePermission",
                "MP_UI result id=${attemptId ?: "none"} current=${currentStartAttemptId ?: "none"} ok=$resultOk " +
                        "data=$hasData resumed=$lifecycleResumed state=${state.name}"
            )
        )

        if (waitingForResult == null) return
        val activeAttemptId = waitingForResult.attemptId
        if (resultOk && data != null) {
            state = PermissionState.GrantReceived(
                attemptId = activeAttemptId,
                intent = data,
                receivedAtElapsedMs = SystemClock.elapsedRealtime()
            )
            if (lifecycleResumed.not()) {
                XLog.i(getLog("ScreenCapturePermission", "MP_UI result_pending_resume id=$activeAttemptId current=${currentStartAttemptId ?: "none"}"))
            }
            deliverGrantIfResumed(currentStartAttemptId, lifecycleResumed, onPermissionGranted, onPermissionDenied)
            return
        }

        state = PermissionState.RetryRequired
        onPermissionDenied(activeAttemptId)
    }

    internal fun deliverGrantIfResumed(
        currentStartAttemptId: String?,
        lifecycleResumed: Boolean,
        onPermissionGranted: (String, Intent) -> Unit,
        onPermissionDenied: ((String) -> Unit)? = null
    ): Boolean {
        val grant = state as? PermissionState.GrantReceived ?: return false
        if (grant.attemptId != currentStartAttemptId) {
            XLog.i(getLog("ScreenCapturePermission", "MP_UI result_stale id=${grant.attemptId} current=${currentStartAttemptId ?: "none"}"))
            state = PermissionState.Idle
            return false
        }
        if (lifecycleResumed.not()) return false

        val waitMs = grant.waitMs()
        if (waitMs >= GRANT_DELIVERY_TIMEOUT.inWholeMilliseconds) {
            XLog.i(getLog("ScreenCapturePermission", "MP_UI result_timeout id=${grant.attemptId} waitMs=$waitMs source=deliver"))
            state = PermissionState.RetryRequired
            onPermissionDenied?.invoke(grant.attemptId)
            return false
        }

        state = PermissionState.Idle
        XLog.i(getLog("ScreenCapturePermission", "MP_UI result_delivered id=${grant.attemptId} waitMs=$waitMs"))
        onPermissionGranted(grant.attemptId, grant.intent)
        return true
    }

    internal fun onGrantTimeout(startAttemptId: String, onPermissionDenied: (String) -> Unit) {
        val grant = state as? PermissionState.GrantReceived ?: return
        if (grant.attemptId != startAttemptId) return
        val waitMs = grant.waitMs()
        if (waitMs < GRANT_DELIVERY_TIMEOUT.inWholeMilliseconds) return

        state = PermissionState.RetryRequired
        XLog.i(getLog("ScreenCapturePermission", "MP_UI result_timeout id=$startAttemptId waitMs=$waitMs source=timer"))
        onPermissionDenied(startAttemptId)
    }

    internal fun onRetryResult(retry: Boolean, onStartRequested: (Boolean) -> Unit) {
        state = PermissionState.Idle
        if (retry) onStartRequested(false)
    }

    private val PermissionState.name: String
        get() = this::class.simpleName ?: "Unknown"

    private fun PermissionState.GrantReceived.waitMs(): Long =
        SystemClock.elapsedRealtime() - receivedAtElapsedMs
}
