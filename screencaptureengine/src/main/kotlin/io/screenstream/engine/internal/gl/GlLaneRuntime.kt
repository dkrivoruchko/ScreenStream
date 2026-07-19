package io.screenstream.engine.internal.gl

import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val glEnteredOperationSafetyNanos: Long = 10_000_000_000L

/**
 * Identity authority for a cleanup suffix that may span more than one GL occurrence.
 * It is deliberately independent from private-executor submission settlement.
 */
internal interface GlTeardownOwner

internal class GlOrderlyShutdownCapability internal constructor(
    private val owner: GlPipelineOwner,
) : GlPipelineOwner.OrderlyShutdownCommand {
    private var claimed: Boolean = false

    internal fun claimFor(expectedOwner: GlPipelineOwner): Boolean =
        owner.glGate.withLock {
            if (owner !== expectedOwner || claimed) return@withLock false
            claimed = true
            true
        }
}

internal class GlLaneRuntime internal constructor(
    private val glGate: ReentrantLock,
    settlementSignal: SettlementSignal,
    threadName: String,
) {
    private val endpoint: PrivateExecutorRuntime = PrivateExecutorRuntime(
        threadName = threadName,
        settlementSignal = settlementSignal,
    )
    private var activeTeardownOwner: GlTeardownOwner? = null

    internal val observedFatal: Throwable?
        get() = endpoint.observedFatal

    internal val startupState: PrivateExecutorStartupDisposition
        get() = endpoint.startupState

    internal val startupFailure: Throwable?
        get() = endpoint.observedStartupFailure

    internal fun prestart(): PrivateExecutorStartupDisposition = endpoint.prestart()

    internal val terminationReceipt: PrivateExecutorTerminationReceipt?
        get() = endpoint.terminationReceipt

    internal fun acceptsTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        endpoint.acceptsTerminationReceipt(receipt)

    internal val hasUnsettledOperation: Boolean
        get() = endpoint.hasUnsettledOperation

    internal val isPoisoned: Boolean
        get() = endpoint.isPoisoned

    internal fun <R : OperationEvidence> operation(
        occurrence: OperationOccurrence<R>,
        enteredWork: Runnable,
    ): PrivateExecutorOperation<R> = endpoint.operation(occurrence, enteredWork)

    internal fun submit(
        operation: PrivateExecutorOperation<*>,
        teardownOwner: GlTeardownOwner? = null,
    ): PrivateExecutorSubmissionResult {
        val admitted = glGate.withLock {
            val active = activeTeardownOwner
            if (active == null) teardownOwner == null else active === teardownOwner
        }
        if (!admitted) return PrivateExecutorSubmissionResult.NotSubmitted
        return endpoint.submit(operation)
    }

    internal fun releaseSettledOperation(operation: PrivateExecutorOperation<*>): Boolean =
        endpoint.releaseSettledOperation(operation)

    internal fun claimTeardown(owner: GlTeardownOwner): Boolean = glGate.withLock {
        checkFatalLocked()
        if (activeTeardownOwner != null && activeTeardownOwner !== owner) return@withLock false
        activeTeardownOwner = owner
        true
    }

    internal fun ownsTeardown(owner: GlTeardownOwner): Boolean =
        glGate.withLock { activeTeardownOwner === owner }

    internal fun releaseTeardown(owner: GlTeardownOwner): Boolean = glGate.withLock {
        checkFatalLocked()
        if (activeTeardownOwner !== owner) return@withLock false
        activeTeardownOwner = null
        true
    }

    internal fun hasActiveTeardownLocked(): Boolean {
        check(glGate.isHeldByCurrentThread)
        return activeTeardownOwner != null
    }

    internal fun checkFatal() {
        observedFatal?.let(FatalThrowablePolicy::rethrow)
    }

    internal fun checkFatalLocked() {
        check(glGate.isHeldByCurrentThread)
        checkFatal()
    }

    internal inline fun <T> outward(block: () -> T): T {
        checkFatal()
        val result = block()
        checkFatal()
        return result
    }

    internal inline fun <T> outwardAdopt(adopt: (T) -> Unit, block: () -> T): T {
        checkFatal()
        val result = block()
        adopt(result)
        checkFatal()
        return result
    }

    internal fun requestShutdown(): Boolean = endpoint.requestShutdown()
}
