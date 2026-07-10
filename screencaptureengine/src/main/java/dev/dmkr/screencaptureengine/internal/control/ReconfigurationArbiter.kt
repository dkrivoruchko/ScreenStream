@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

import java.util.Collections

internal data class ReconfigurationReason(
    val token: ReasonToken,
    val spec: ReconfigurationReasonSpec,
    val acceptedAt: IngressSequence,
)

internal data class PendingReconfiguration(
    val desired: DesiredSnapshotIdentity,
    val geometry: GeometrySnapshot,
    val acceptedAt: IngressSequence,
)

internal class ReconfigurationArbiter(
    val activeReconfiguration: ReconfigurationIdentity? = null,
    val activeCandidate: CandidateIdentity? = null,
    val associatedParameterTransaction: TransactionIdentity? = null,
    val pending: PendingReconfiguration? = null,
    reasons: Map<ReconfigurationReasonKey, ReconfigurationReason> = emptyMap(),
) {
    val reasons: Map<ReconfigurationReasonKey, ReconfigurationReason> = reasons.ownedCopy()

    init {
        require(this.reasons.size <= ReconfigurationReasonKey.entries.size)
        require(this.reasons.all { (key, reason) -> key == reason.spec.key })
        require((activeReconfiguration == null) == (activeCandidate == null))
        require(associatedParameterTransaction == null || activeReconfiguration != null)
    }

    val principal: ReconfigurationReason?
        get() = reasons.values.minWithOrNull(
            compareBy(
                { reason: ReconfigurationReason -> reason.spec.kind.priority },
                { it.spec.stage.priority },
                { it.acceptedAt.value },
            ),
        )

    fun add(token: ReasonToken, spec: ReconfigurationReasonSpec, acceptedAt: IngressSequence): ReconfigurationArbiter {
        val current = reasons[spec.key]
        if (current?.spec == spec) return this
        return with(reasons = reasons + (spec.key to ReconfigurationReason(token, spec, acceptedAt)))
    }

    fun clear(key: ReconfigurationReasonKey, token: ReasonToken): ReconfigurationArbiter {
        val current = reasons[key] ?: return this
        return if (current.token == token) with(reasons = reasons - key) else this
    }

    fun begin(
        identity: ReconfigurationIdentity,
        candidate: CandidateIdentity,
        parameter: TransactionIdentity? = null,
    ): ReconfigurationArbiter =
        if (activeReconfiguration == null) {
            with(
                activeReconfiguration = identity,
                activeCandidate = candidate,
                associatedParameterTransaction = parameter,
            )
        } else {
            this
        }

    fun owns(origin: ControllerFactOrigin.Reconfiguration): Boolean =
        activeReconfiguration == origin.reconfiguration && activeCandidate == origin.candidate

    fun finish(origin: ControllerFactOrigin.Reconfiguration): ReconfigurationArbiter =
        if (owns(origin)) {
            with(
                activeReconfiguration = null,
                activeCandidate = null,
                associatedParameterTransaction = null,
            )
        } else {
            this
        }

    fun replacePending(value: PendingReconfiguration): ReconfigurationArbiter = with(pending = value)

    /** Clears only the exact desired/geometry/accepted-sequence record selected by reduction. */
    fun consumePending(expected: PendingReconfiguration): ReconfigurationArbiter =
        if (pending == expected) {
            ReconfigurationArbiter(
                activeReconfiguration = activeReconfiguration,
                activeCandidate = activeCandidate,
                associatedParameterTransaction = associatedParameterTransaction,
                pending = null,
                reasons = reasons,
            )
        } else {
            this
        }

    private fun with(
        activeReconfiguration: ReconfigurationIdentity? = this.activeReconfiguration,
        activeCandidate: CandidateIdentity? = this.activeCandidate,
        associatedParameterTransaction: TransactionIdentity? = this.associatedParameterTransaction,
        pending: PendingReconfiguration? = this.pending,
        reasons: Map<ReconfigurationReasonKey, ReconfigurationReason> = this.reasons,
    ): ReconfigurationArbiter = ReconfigurationArbiter(
        activeReconfiguration = activeReconfiguration,
        activeCandidate = activeCandidate,
        associatedParameterTransaction = associatedParameterTransaction,
        pending = pending,
        reasons = reasons,
    )

    private companion object {
        fun Map<ReconfigurationReasonKey, ReconfigurationReason>.ownedCopy():
                Map<ReconfigurationReasonKey, ReconfigurationReason> =
            if (isEmpty()) emptyMap() else Collections.unmodifiableMap(LinkedHashMap(this))
    }
}
