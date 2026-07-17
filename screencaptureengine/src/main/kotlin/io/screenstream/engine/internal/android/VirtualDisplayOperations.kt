package io.screenstream.engine.internal.android

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetNoProducerEvidence
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import java.util.concurrent.atomic.AtomicReference

private const val initialCapturedResizeReadinessNanos: Long = 5_000_000_000L

internal object AndroidVirtualDisplayCreationReceipt : OperationReceipt

internal class AndroidVirtualDisplayCreationEvidence : OperationEvidence, OperationReturnedOwner {
    internal var virtualDisplay: VirtualDisplay? = null
        private set

    override val receipt: AndroidVirtualDisplayCreationReceipt = AndroidVirtualDisplayCreationReceipt
    override val returnedOwner: OperationReturnedOwner?
        get() = if (virtualDisplay == null) null else this

    internal var initialResizeStartNanos: Long? = null
        private set

    internal var initialResizeDeadlineNanos: Long? = null
        private set

    internal var initialResizeDeadlineGuardFailed: Boolean = false
        private set

    private val producerEvidence = AtomicReference<TargetProducerEvidence?>(null)
    private val noProducerEvidence = AtomicReference<TargetNoProducerEvidence?>(null)

    internal val publishedProducerEvidence: TargetProducerEvidence?
        get() = producerEvidence.get()

    internal val publishedNoProducerEvidence: TargetNoProducerEvidence?
        get() = noProducerEvidence.get()

    internal fun publishProducerEvidence(evidence: TargetProducerEvidence): Boolean =
        noProducerEvidence.get() == null && producerEvidence.compareAndSet(null, evidence)

    internal fun publishNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
        producerEvidence.get() == null && noProducerEvidence.compareAndSet(null, evidence)

    internal fun recordReturnLocked(virtualDisplay: VirtualDisplay?, sdkInt: Int, sampleNanos: Long) {
        this.virtualDisplay = virtualDisplay
        if (virtualDisplay == null || sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        initialResizeStartNanos = sampleNanos
        if (sampleNanos < 0L || sampleNanos > Long.MAX_VALUE - initialCapturedResizeReadinessNanos) {
            initialResizeDeadlineGuardFailed = true
            return
        }
        initialResizeDeadlineNanos = Math.addExact(sampleNanos, initialCapturedResizeReadinessNanos)
    }

    internal fun isTimelyInitialResize(fact: AndroidCaptureFact.CapturedContentResized): Boolean {
        val deadlineNanos = initialResizeDeadlineNanos ?: return false
        return fact.widthPx > 0 && fact.heightPx > 0 && fact.sampleNanos < deadlineNanos
    }
}

internal class AndroidVirtualDisplayCreationOwnerBag(
    internal val projection: MediaProjection,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidSurfacePort,
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val densityDpi: Int,
) : OperationOwnerBag {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
    }
}

internal object AndroidVirtualDisplayResizeReceipt : OperationReceipt

internal class AndroidVirtualDisplayResizeEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayResizeReceipt = AndroidVirtualDisplayResizeReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidVirtualDisplayResizeOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val densityDpi: Int,
) : OperationOwnerBag {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
    }
}

internal object AndroidVirtualDisplayAttachReceipt : OperationReceipt

internal class AndroidVirtualDisplayAttachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayAttachReceipt = AndroidVirtualDisplayAttachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val producerEvidence = AtomicReference<TargetProducerEvidence?>(null)
    private val noProducerEvidence = AtomicReference<TargetNoProducerEvidence?>(null)

    internal val publishedProducerEvidence: TargetProducerEvidence?
        get() = producerEvidence.get()

    internal val publishedNoProducerEvidence: TargetNoProducerEvidence?
        get() = noProducerEvidence.get()

    internal fun publishProducerEvidence(evidence: TargetProducerEvidence): Boolean =
        noProducerEvidence.get() == null && producerEvidence.compareAndSet(null, evidence)

    internal fun publishNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
        producerEvidence.get() == null && noProducerEvidence.compareAndSet(null, evidence)
}

internal class AndroidVirtualDisplayAttachOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidSurfacePort,
) : OperationOwnerBag

internal object AndroidVirtualDisplayDetachReceipt : OperationReceipt

internal class AndroidVirtualDisplayDetachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayDetachReceipt = AndroidVirtualDisplayDetachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val targetReceipt = AtomicReference<TargetProducerDetachReceipt?>(null)

    internal val publishedTargetReceipt: TargetProducerDetachReceipt?
        get() = targetReceipt.get()

    internal fun publishTargetReceipt(receipt: TargetProducerDetachReceipt): Boolean =
        targetReceipt.compareAndSet(null, receipt)
}

internal class AndroidVirtualDisplayDetachOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidDetachPort,
) : OperationOwnerBag


internal object AndroidVirtualDisplayReleaseReceipt : OperationReceipt

internal class AndroidVirtualDisplayReleaseEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayReleaseReceipt = AndroidVirtualDisplayReleaseReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val targetReceipt = AtomicReference<TargetProducerDetachReceipt?>(null)

    internal val publishedTargetReceipt: TargetProducerDetachReceipt?
        get() = targetReceipt.get()

    internal fun publishTargetReceipt(receipt: TargetProducerDetachReceipt): Boolean =
        targetReceipt.compareAndSet(null, receipt)
}

internal class AndroidVirtualDisplayReleaseOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget?,
    internal val targetPort: TargetPorts.AndroidDetachPort?,
) : OperationOwnerBag
