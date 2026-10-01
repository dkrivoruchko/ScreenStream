package info.dvkr.screenstream.common.streaming

import androidx.annotation.StringRes
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owned snapshot of ordinary permission grants. [isGranted] treats missing entries as denied;
 * callers recheck their originating operation before continuation.
 */
public class PermissionSnapshot(grantStates: Map<String, Boolean>) {
    /** Owned, unmodifiable grant map; missing keys are denied. */
    public val grantStates: Map<String, Boolean> = Collections.unmodifiableMap(LinkedHashMap(grantStates))

    public fun isGranted(permission: String): Boolean = grantStates[permission] == true
}

/**
 * One permission request from a module startup assessment or a current user action, identified
 * by this instance. For a module request, [isOwnerCurrent] checks coordinator ownership of the
 * original module instance and current runtime, plus the applicable action, permission
 * requirements, and settings. For a shell action, it checks the original task and action. Its
 * in-memory callback is cheap, thread-safe, and nonblocking. The bridge owns the Activity and
 * rechecks ownership at UI and result handoffs; the caller rechecks before continuing its operation.
 */
public class PermissionRequest(
    permissions: Set<String>,
    /** Optional string resource for a cancelable rationale. */
    @get:StringRes public val rationaleResource: Int? = null,
    private val ownerIsCurrent: () -> Boolean,
) {
    /** Owned, unmodifiable permission names for this request. */
    public val permissions: Set<String> = Collections.unmodifiableSet(LinkedHashSet(permissions))

    private val claimed: AtomicBoolean = AtomicBoolean(false)

    /** Check current ownership at this instant; subsequent steps recheck before continuation. */
    public fun isOwnerCurrent(): Boolean = ownerIsCurrent()

    /**
     * Atomically consume this request after bridge admission to the physical dialog slot.
     * At most one claim succeeds for this instance. Pre-admission rejection discards the request;
     * subsequent UI and continuation steps check the owner again.
     */
    public fun tryClaim(): Boolean {
        if (!isOwnerCurrent()) return false
        if (!claimed.compareAndSet(false, true)) return false
        return isOwnerCurrent()
    }
}

/**
 * Ordinary permission checks and one physical dialog slot. UI collection, Activity resume, and
 * return from Settings may call [check] to refresh grants; [request] starts from a module startup
 * assessment or a current UI or settings action.
 */
public interface RuntimePermissions {
    /** Read current grants for [permissions]; the caller validates its operation before continuation. */
    public fun check(permissions: Set<String>): PermissionSnapshot

    /**
     * Present a cancelable rationale or system dialog for a current [request] from a RESUMED
     * Activity. Busy, stale, or paused requests return the current grant snapshot for caller-led
     * continuation. An in-flight dialog retains its slot across cancellation or rotation until
     * its result or a known terminal UI operation. Technical failure and caller cancellation throw;
     * POST_NOTIFICATIONS denial leaves module operation active.
     */
    public suspend fun request(request: PermissionRequest): PermissionSnapshot
}
