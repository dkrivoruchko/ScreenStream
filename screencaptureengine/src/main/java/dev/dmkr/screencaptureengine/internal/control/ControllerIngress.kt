@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

internal sealed interface ControllerIngressPayload {
    class Terminal(val cause: ControllerTerminalCause) : ControllerIngressPayload

    data class Metrics(val evidence: MetricsEvidence) : ControllerIngressPayload

    data class CapturedResize(val evidence: CapturedResizeEvidence) : ControllerIngressPayload

    data class SourceTrust(val evidence: SourceTrustEvidence) : ControllerIngressPayload

    data class Pause(val physicalPaused: Boolean) : ControllerIngressPayload

    data class Cancellation(val transaction: TransactionIdentity) : ControllerIngressPayload

    data class Visibility(val visible: Boolean?) : ControllerIngressPayload
}

internal class ControllerIngressOffer internal constructor(
    internal val payload: ControllerIngressPayload,
    private val ownerIdentity: Any,
) {
    private var consumed = false

    internal fun take(ownerIdentity: Any): ControllerIngressPayload? {
        if (this.ownerIdentity !== ownerIdentity || consumed) return null
        consumed = true
        return payload
    }
}

internal sealed interface ControllerTurnFact {
    val sequence: IngressSequence
    val priority: Int
}

internal sealed interface ControllerIngress : ControllerTurnFact {

    class Terminal(
        override val sequence: IngressSequence,
        val cause: ControllerTerminalCause,
    ) : ControllerIngress {
        val evidence: TerminalEvidence
            get() = cause.evidence

        override val priority: Int = cause.evidence.priority
    }

    data class Metrics(override val sequence: IngressSequence, val evidence: MetricsEvidence) : ControllerIngress {
        override val priority = 4
    }

    data class CapturedResize(
        override val sequence: IngressSequence,
        val evidence: CapturedResizeEvidence,
    ) : ControllerIngress {
        override val priority = 4
    }

    data class SourceTrust(
        override val sequence: IngressSequence,
        val evidence: SourceTrustEvidence,
    ) : ControllerIngress {
        override val priority = 4
    }

    data class Pause(override val sequence: IngressSequence, val evidence: PauseEvidence) : ControllerIngress {
        override val priority = 4
    }

    data class Cancellation(
        override val sequence: IngressSequence,
        val transaction: TransactionIdentity,
    ) : ControllerIngress {
        override val priority = 5
    }

    data class Visibility(override val sequence: IngressSequence, val visible: Boolean?) : ControllerIngress {
        override val priority = 7
    }
}

internal enum class ControllerWakeIntent { Schedule, AlreadyScheduled, Ignored }

/** Fixed-size controller-confined store. A future controller calls it only while holding its sole gate. */
internal class ControllerIngressStore {
    private val identity = Any()
    private val terminals = arrayOfNulls<ControllerIngress.Terminal>(TerminalEvidence.entries.size)
    private var metrics: ControllerIngress.Metrics? = null
    private var capturedResize: ControllerIngress.CapturedResize? = null
    private var metricsSourceTrust: ControllerIngress.SourceTrust? = null
    private var capturedResizeSourceTrust: ControllerIngress.SourceTrust? = null
    private var pause: ControllerIngress.Pause? = null
    private var latestPhysicalPaused = false
    private var pauseDebtSequence: IngressSequence? = null
    private var cancellation: ControllerIngress.Cancellation? = null
    private var visibility: ControllerIngress.Visibility? = null
    private var wakeScheduled = false

    /**
     * Called under the future controller gate before allocating an ingress sequence. The returned
     * offer must be accepted exactly once by this store, under the same uninterrupted gate hold.
     * Cancellation is offered only when [currentTransaction] matches its live transaction.
     */
    fun offer(
        payload: ControllerIngressPayload,
        currentTransaction: TransactionIdentity? = null,
    ): ControllerIngressOffer? {
        if (payload is ControllerIngressPayload.Cancellation && payload.transaction != currentTransaction) return null
        return payload.takeIf(::wouldChange)?.let { ControllerIngressOffer(it, identity) }
    }

    /** Installs an offered payload with the sequence allocated after [offer] accepted it. */
    fun accept(offer: ControllerIngressOffer, sequence: IngressSequence): ControllerWakeIntent {
        val payload = offer.take(identity) ?: return ControllerWakeIntent.Ignored
        if (!wouldChange(payload)) return ControllerWakeIntent.Ignored
        val changed = when (payload) {
            is ControllerIngressPayload.Pause -> putPause(payload.physicalPaused, sequence)
            else -> when (val record = payload.toRecord(sequence)) {
                is ControllerIngress.Terminal -> putTerminal(record)
                is ControllerIngress.Metrics -> replaceIfChanged(metrics, record) { metrics = it }
                is ControllerIngress.CapturedResize -> replaceIfChanged(capturedResize, record) { capturedResize = it }
                is ControllerIngress.SourceTrust -> putSourceTrust(record)
                is ControllerIngress.Pause -> error("Pause payloads are installed by putPause().")
                is ControllerIngress.Cancellation -> replaceIfChanged(cancellation, record) { cancellation = it }
                is ControllerIngress.Visibility -> replaceIfChanged(visibility, record) { visibility = it }
            }
        }
        check(changed) { "An accepted offer must change exactly one ingress slot." }
        return if (wakeScheduled) ControllerWakeIntent.AlreadyScheduled else ControllerWakeIntent.Schedule.also { wakeScheduled = true }
    }

    fun snapshot(): List<ControllerIngress> = buildList {
        terminals.filterNotNullTo(this)
        metrics?.let(::add)
        capturedResize?.let(::add)
        metricsSourceTrust?.let(::add)
        capturedResizeSourceTrust?.let(::add)
        pause?.let(::add)
        cancellation?.let(::add)
        visibility?.let(::add)
    }.sortedWith(compareBy({ it.priority }, { it.sequence.value })).also {
        terminals.fill(null)
        metrics = null
        capturedResize = null
        metricsSourceTrust = null
        capturedResizeSourceTrust = null
        pause = null
        pauseDebtSequence = null
        cancellation = null
        visibility = null
        wakeScheduled = false
    }

    private fun putTerminal(record: ControllerIngress.Terminal): Boolean {
        val index = record.evidence.ordinal
        if (terminals[index] != null) return false
        terminals[index] = record
        return true
    }

    private fun wouldChange(payload: ControllerIngressPayload): Boolean = when (payload) {
        is ControllerIngressPayload.Terminal -> terminals[payload.cause.evidence.ordinal] == null
        is ControllerIngressPayload.Metrics -> metrics?.evidence != payload.evidence
        is ControllerIngressPayload.CapturedResize -> capturedResize?.evidence != payload.evidence
        is ControllerIngressPayload.SourceTrust -> sourceTrust(payload.evidence)?.evidence != payload.evidence
        is ControllerIngressPayload.Pause -> latestPhysicalPaused != payload.physicalPaused
        is ControllerIngressPayload.Cancellation -> cancellation?.transaction != payload.transaction
        is ControllerIngressPayload.Visibility -> visibility?.visible != payload.visible
    }

    private fun putPause(physicalPaused: Boolean, sequence: IngressSequence): Boolean {
        if (latestPhysicalPaused == physicalPaused) return false
        latestPhysicalPaused = physicalPaused
        if (physicalPaused) pauseDebtSequence = sequence
        pause = ControllerIngress.Pause(
            sequence = pauseDebtSequence ?: sequence,
            evidence = PauseEvidence(
                physicalPaused = physicalPaused,
                debtSequence = pauseDebtSequence,
            ),
        )
        return true
    }

    private fun putSourceTrust(record: ControllerIngress.SourceTrust): Boolean = when (record.evidence) {
        SourceTrustEvidence.InvalidResize -> replaceIfChanged(capturedResizeSourceTrust, record) {
            capturedResizeSourceTrust = it
        }

        SourceTrustEvidence.NotReady,
        SourceTrustEvidence.Invalid,
        SourceTrustEvidence.NoLongerAvailable,
            -> replaceIfChanged(metricsSourceTrust, record) { metricsSourceTrust = it }
    }

    private fun sourceTrust(evidence: SourceTrustEvidence): ControllerIngress.SourceTrust? = when (evidence) {
        SourceTrustEvidence.InvalidResize -> capturedResizeSourceTrust
        SourceTrustEvidence.NotReady,
        SourceTrustEvidence.Invalid,
        SourceTrustEvidence.NoLongerAvailable,
            -> metricsSourceTrust
    }

    private inline fun <T> replaceIfChanged(current: T?, replacement: T, install: (T) -> Unit): Boolean {
        if (current == replacement) return false
        install(replacement)
        return true
    }

    private fun ControllerIngressPayload.toRecord(sequence: IngressSequence): ControllerIngress = when (this) {
        is ControllerIngressPayload.Terminal -> ControllerIngress.Terminal(sequence, cause)
        is ControllerIngressPayload.Metrics -> ControllerIngress.Metrics(sequence, evidence)
        is ControllerIngressPayload.CapturedResize -> ControllerIngress.CapturedResize(sequence, evidence)
        is ControllerIngressPayload.SourceTrust -> ControllerIngress.SourceTrust(sequence, evidence)
        is ControllerIngressPayload.Pause -> error("Pause payloads require store-owned debt integration.")
        is ControllerIngressPayload.Cancellation -> ControllerIngress.Cancellation(sequence, transaction)
        is ControllerIngressPayload.Visibility -> ControllerIngress.Visibility(sequence, visible)
    }
}
