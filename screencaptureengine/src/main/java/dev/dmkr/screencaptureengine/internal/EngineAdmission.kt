@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal

internal class ActiveAdmission

internal class RetiringAdmission

/**
 * Linearizes one engine's partial admission lifecycle.
 *
 * The monitor is the linearization point for every transition. Reuse remains deliberately absent
 * until the controller can prove the complete v41 reuse barrier.
 */
internal class EngineAdmission {
    private val lock = Any()
    private var state: State = State.Vacant

    internal fun acquire(): ActiveAdmission? =
        synchronized(lock) {
            if (state !== State.Vacant) return@synchronized null
            ActiveAdmission().also { state = State.Occupied(it) }
        }

    internal fun markRetiring(admission: ActiveAdmission): RetiringAdmission =
        synchronized(lock) {
            val occupied = state as? State.Occupied
            check(occupied?.admission === admission) { "Only the current active admission may retire." }
            RetiringAdmission().also { state = State.Retiring(it) }
        }

    internal fun poison(admission: ActiveAdmission): Unit =
        synchronized(lock) {
            val occupied = state as? State.Occupied
            check(occupied?.admission === admission) { "Only the current active admission may poison." }
            state = State.Poisoned
        }

    internal fun poison(admission: RetiringAdmission): Unit =
        synchronized(lock) {
            val retiring = state as? State.Retiring
            check(retiring?.admission === admission) { "Only the current retiring admission may poison." }
            state = State.Poisoned
        }

    private sealed interface State {
        data object Vacant : State

        class Occupied(
            val admission: ActiveAdmission,
        ) : State

        class Retiring(
            val admission: RetiringAdmission,
        ) : State

        data object Poisoned : State
    }
}
