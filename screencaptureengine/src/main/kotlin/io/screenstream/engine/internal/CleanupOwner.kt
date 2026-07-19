package io.screenstream.engine.internal

import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.AndroidLaneTerminationReceipt
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.jpeg.NativeEncodeFatalCleanupSettlement
import io.screenstream.engine.internal.jpeg.NativeEncodeOccurrence
import io.screenstream.engine.internal.jpeg.FrameworkJpegOwner
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class CleanupMutation {
    None,
    RootAttached,
    RootReduced,
    QuarantineAttached,
    QuarantineReduced,
}

internal enum class ControlShutdownReadiness {
    BlockedByCleanup,
    AwaitingStage5Delivery,
}

internal class MetricsCleanupRoot internal constructor() {
    internal var owner: CaptureMetricsOwner? = null
        private set

    private var shutdownAction: MetricsEndpointShutdownAction? = null

    internal val retainedShutdownAction: MetricsEndpointShutdownAction?
        get() = shutdownAction

    internal fun attach(candidate: CaptureMetricsOwner): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        shutdownAction = candidate.cleanupShutdownAction
        return true
    }

    internal fun claimShutdownAction(): MetricsEndpointShutdownAction? =
        shutdownAction?.takeIf { owner?.isEndpointShutdownReady == true && it.claim() }

    internal fun selectOwner(): CaptureMetricsOwner? = owner

    internal fun reduce(receipt: PrivateExecutorTerminationReceipt): Boolean {
        val exactOwner = owner ?: return false
        if (exactOwner.observedFatal != null || exactOwner.endpointTerminationReceipt !== receipt) return false
        owner = null
        shutdownAction = null
        return true
    }

    internal fun detach(expected: CaptureMetricsOwner): Boolean {
        if (owner !== expected) return false
        owner = null
        shutdownAction = null
        return true
    }
}

internal class AndroidCleanupRoot internal constructor() {
    internal var owner: AndroidCaptureOwner? = null
        private set

    private var quitAction: AndroidLaneQuitAction? = null

    internal val retainedQuitAction: AndroidLaneQuitAction?
        get() = quitAction

    internal fun attach(candidate: AndroidCaptureOwner): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        quitAction = candidate.cleanupQuitAction
        return true
    }

    internal fun claimQuitAction(): AndroidLaneQuitAction? =
        quitAction?.takeIf { owner?.isLaneQuitReady == true && it.claim() }

    internal fun selectOwner(): AndroidCaptureOwner? = owner

    internal fun reduce(receipt: AndroidLaneTerminationReceipt): Boolean {
        val exactOwner = owner ?: return false
        if (exactOwner.observedLaneFatal != null || exactOwner.laneTerminationReceipt !== receipt) return false
        owner = null
        quitAction = null
        return true
    }

    internal fun detach(expected: AndroidCaptureOwner): Boolean {
        if (owner !== expected) return false
        owner = null
        quitAction = null
        return true
    }
}

internal class PreparedTargetCleanupRoot internal constructor() {
    internal var target: PreparedTarget? = null
        private set

    internal fun attach(candidate: PreparedTarget): Boolean {
        if (target != null) return target === candidate
        target = candidate
        return true
    }

    internal fun reduceProvenComplete(expected: PreparedTarget): Boolean {
        if (target !== expected) return false
        target = null
        return true
    }

    internal fun detach(expected: PreparedTarget): Boolean {
        if (target !== expected) return false
        target = null
        return true
    }
}

internal class CurrentTargetCleanupRoot internal constructor() {
    internal var target: CurrentTarget? = null
        private set

    internal fun attach(candidate: CurrentTarget): Boolean {
        if (target != null) return target === candidate
        target = candidate
        return true
    }

    internal fun reduceProvenComplete(expected: CurrentTarget): Boolean {
        if (target !== expected) return false
        target = null
        return true
    }

    internal fun detach(expected: CurrentTarget): Boolean {
        if (target !== expected) return false
        target = null
        return true
    }
}

internal class GlCleanupRoot internal constructor() {
    internal var owner: GlPipelineOwner? = null
        private set

    private var shutdownAction: GlLaneShutdownAction? = null

    internal val retainedShutdownAction: GlLaneShutdownAction?
        get() = shutdownAction

    internal val hasShutdownAction: Boolean
        get() = shutdownAction != null

    internal fun attach(candidate: GlPipelineOwner): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        return true
    }

    internal fun claimExistingShutdownAction(): GlLaneShutdownAction? =
        shutdownAction?.takeIf { it.claim() }

    internal fun installAndClaimShutdownAction(
        expectedOwner: GlPipelineOwner,
        command: GlPipelineOwner.OrderlyShutdownCommand,
        candidate: GlLaneShutdownAction,
    ): GlLaneShutdownAction? {
        if (owner !== expectedOwner || !candidate.matches(expectedOwner, command)) return null
        val existing = shutdownAction
        if (existing != null) return existing.takeIf { it.claim() }
        return candidate.also {
            shutdownAction = candidate
            check(candidate.claim())
        }
    }

    internal fun reduce(receipt: PrivateExecutorTerminationReceipt): Boolean {
        val exactOwner = owner ?: return false
        if (!exactOwner.acceptsLaneTerminationReceipt(receipt) || exactOwner.laneTerminationReceipt !== receipt) return false
        owner = null
        shutdownAction = null
        return true
    }

    internal fun detach(expected: GlPipelineOwner): Boolean {
        if (owner !== expected) return false
        owner = null
        shutdownAction = null
        return true
    }
}

internal class JpegCleanupRoot internal constructor() {
    internal var owner: JpegRuntimeOwner? = null
        private set

    internal var returnedNativeFatal: ReturnedNativeFatalCleanupChild? = null
        private set
    internal var frameworkOwner: FrameworkJpegOwner? = null
        private set

    private var shutdownAction: JpegEndpointShutdownAction? = null

    internal val retainedShutdownAction: JpegEndpointShutdownAction?
        get() = shutdownAction

    internal fun attach(candidate: JpegRuntimeOwner): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        shutdownAction = candidate.cleanupShutdownAction
        return true
    }

    internal fun attachReturnedNativeFatal(child: ReturnedNativeFatalCleanupChild): Boolean {
        if (owner !== child.owner) return false
        if (returnedNativeFatal != null) return returnedNativeFatal?.matches(child) == true
        returnedNativeFatal = child
        return true
    }

    internal fun attachFramework(candidate: FrameworkJpegOwner): Boolean {
        if (frameworkOwner != null) return frameworkOwner === candidate
        frameworkOwner = candidate
        return true
    }

    internal fun clearFramework(expected: FrameworkJpegOwner): Boolean {
        if (frameworkOwner !== expected) return false
        frameworkOwner = null
        return true
    }

    internal fun claimShutdownAction(): JpegEndpointShutdownAction? {
        val exactOwner = owner ?: return null
        if (returnedNativeFatal != null || frameworkOwner != null || !exactOwner.isJpegShutdownReady) return null
        return shutdownAction?.takeIf { it.claim() }
    }

    internal fun reduce(receipt: PrivateExecutorTerminationReceipt): Boolean {
        val exactOwner = owner ?: return false
        if (returnedNativeFatal != null || frameworkOwner != null || !exactOwner.isJpegCleanupComplete(receipt)) return false
        owner = null
        shutdownAction = null
        return true
    }

    internal fun clearReturnedNativeFatal(expected: ReturnedNativeFatalCleanupChild): Boolean {
        if (returnedNativeFatal !== expected) return false
        returnedNativeFatal = null
        return true
    }

    internal fun detach(expected: JpegRuntimeOwner): Boolean {
        if (owner !== expected || returnedNativeFatal != null || frameworkOwner != null) return false
        owner = null
        shutdownAction = null
        return true
    }
}

internal class StorageCleanupRoot internal constructor() {
    internal var owner: EncodedStorageOwner? = null
        private set

    private var retirementAction: StorageRetirementAction? = null

    internal val retainedRetirementAction: StorageRetirementAction?
        get() = retirementAction

    internal fun attach(candidate: EncodedStorageOwner): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        return true
    }

    internal fun selectRetirementOwner(): EncodedStorageOwner? =
        if (retirementAction == null) owner else null

    internal fun claimExistingRetirementAction(): StorageRetirementAction? =
        retirementAction?.takeIf { it.claim() }

    internal fun installAndClaimRetirementAction(
        expectedOwner: EncodedStorageOwner,
        candidate: StorageRetirementAction,
    ): StorageRetirementAction? {
        if (owner !== expectedOwner || retirementAction != null || !candidate.matchesCurrent(expectedOwner)) return null
        retirementAction = candidate
        return candidate.takeIf { it.claim() }
    }

    internal fun reduce(expected: EncodedStorageOwner): Boolean {
        if (owner !== expected || expected.production != null || expected.unpublished != null ||
            expected.latest != null || expected.displaced != null || expected.lease != null
        ) {
            return false
        }
        owner = null
        retirementAction = null
        return true
    }

    internal fun detach(expected: EncodedStorageOwner): Boolean {
        if (owner !== expected) return false
        owner = null
        retirementAction = null
        return true
    }
}

internal class ControlResidueCleanupRoot internal constructor() {
    internal var residue: ControlWakeResidue? = null
        private set

    internal fun attach(candidate: ControlWakeResidue): Boolean {
        if (residue != null) return residue === candidate
        residue = candidate
        return true
    }

    internal fun reduceProvenSettled(expected: ControlWakeResidue): Boolean {
        if (residue !== expected) return false
        residue = null
        return true
    }

    internal fun detach(expected: ControlWakeResidue): Boolean {
        if (residue !== expected) return false
        residue = null
        return true
    }
}

internal sealed interface TargetQuarantineChild {
    val exactTarget: Any

    class Prepared(
        internal val target: PreparedTarget,
    ) : TargetQuarantineChild {
        override val exactTarget: Any
            get() = target
    }

    class Current(
        internal val target: CurrentTarget,
    ) : TargetQuarantineChild {
        override val exactTarget: Any
            get() = target
    }
}

internal class SessionQuarantineRoot internal constructor() {
    internal var metrics: CaptureMetricsOwner? = null
        private set
    internal var android: AndroidCaptureOwner? = null
        private set
    internal var targetChild: TargetQuarantineChild? = null
        private set
    internal var gl: GlPipelineOwner? = null
        private set
    internal var jpeg: JpegRuntimeOwner? = null
        private set
    internal var framework: FrameworkJpegOwner? = null
        private set
    internal var storage: EncodedStorageOwner? = null
        private set
    internal var controlResidue: ControlWakeResidue? = null
        private set
    internal var returnedNativeFatal: ReturnedNativeFatalCleanupChild? = null
        private set
    internal var metricsAction: MetricsEndpointShutdownAction? = null
        private set
    internal var androidAction: AndroidLaneQuitAction? = null
        private set
    internal var glAction: GlLaneShutdownAction? = null
        private set
    internal var jpegAction: JpegEndpointShutdownAction? = null
        private set
    internal var storageAction: StorageRetirementAction? = null
        private set

    internal val isEmpty: Boolean
        get() = metrics == null && android == null && targetChild == null &&
                gl == null && jpeg == null && framework == null && storage == null && controlResidue == null && returnedNativeFatal == null &&
                metricsAction == null && androidAction == null && glAction == null && jpegAction == null && storageAction == null

    internal fun attachMetrics(candidate: CaptureMetricsOwner, action: MetricsEndpointShutdownAction?): Boolean =
        if (metrics != null) false else {
            metrics = candidate
            metricsAction = action
            true
        }

    internal fun attachAndroid(candidate: AndroidCaptureOwner, action: AndroidLaneQuitAction?): Boolean =
        if (android != null) false else {
            android = candidate
            androidAction = action
            true
        }

    internal fun attachTarget(candidate: TargetQuarantineChild): Boolean =
        if (targetChild != null) targetChild === candidate else {
            targetChild = candidate
            true
        }

    internal fun attachGl(candidate: GlPipelineOwner, action: GlLaneShutdownAction?): Boolean =
        if (gl != null) false else {
            gl = candidate
            glAction = action
            true
        }

    internal fun attachJpeg(candidate: JpegRuntimeOwner, action: JpegEndpointShutdownAction?): Boolean =
        if (jpeg != null) false else {
            jpeg = candidate
            jpegAction = action
            true
        }

    internal fun attachFramework(candidate: FrameworkJpegOwner): Boolean =
        if (framework != null) framework === candidate else {
            framework = candidate
            true
        }

    internal fun attachStorage(candidate: EncodedStorageOwner, action: StorageRetirementAction?): Boolean =
        if (storage != null) false else {
            storage = candidate
            storageAction = action
            true
        }

    internal fun attachControlResidue(candidate: ControlWakeResidue): Boolean =
        if (controlResidue != null) false else {
            controlResidue = candidate
            true
        }

    internal fun attachReturnedNativeFatal(candidate: ReturnedNativeFatalCleanupChild): Boolean =
        if (returnedNativeFatal != null) false else {
            returnedNativeFatal = candidate
            true
        }

    internal fun reduceMetrics(expected: CaptureMetricsOwner): Boolean {
        if (metrics !== expected) return false
        metrics = null
        metricsAction = null
        return true
    }

    internal fun reduceAndroid(expected: AndroidCaptureOwner): Boolean {
        if (android !== expected) return false
        android = null
        androidAction = null
        return true
    }

    internal fun reduceTarget(expected: TargetQuarantineChild): Boolean {
        if (targetChild !== expected) return false
        targetChild = null
        return true
    }

    internal fun reduceGl(expected: GlPipelineOwner): Boolean {
        if (gl !== expected) return false
        gl = null
        glAction = null
        return true
    }

    internal fun reduceJpeg(expected: JpegRuntimeOwner): Boolean {
        if (jpeg !== expected) return false
        jpeg = null
        jpegAction = null
        return true
    }

    internal fun reduceFramework(expected: FrameworkJpegOwner): Boolean {
        if (framework !== expected) return false
        framework = null
        return true
    }

    internal fun reduceStorage(expected: EncodedStorageOwner): Boolean {
        if (storage !== expected) return false
        storage = null
        storageAction = null
        return true
    }

    internal fun reduceControlResidue(expected: ControlWakeResidue): Boolean {
        if (controlResidue !== expected) return false
        controlResidue = null
        return true
    }

    internal fun reduceReturnedNativeFatal(expected: ReturnedNativeFatalCleanupChild): Boolean {
        if (returnedNativeFatal !== expected) return false
        returnedNativeFatal = null
        return true
    }
}

internal class ReturnedNativeFatalCleanupChild internal constructor(
    internal val owner: JpegRuntimeOwner,
    internal val occurrence: NativeEncodeOccurrence,
    internal val storage: EncodedStorageOwner,
) {
    internal fun matches(other: ReturnedNativeFatalCleanupChild): Boolean =
        owner === other.owner && occurrence === other.occurrence && storage === other.storage
}

internal class ControlWakeResidue private constructor(
    internal val occurrence: OperationOccurrence<*>?,
    internal val settlementGate: ReentrantLock,
    internal val wakeLink: ControlWakeLink,
) {
    internal fun isMechanicallySettled(): Boolean = settlementGate.withLock {
        if (occurrence != null && occurrence.controlWakeLink !== wakeLink) return@withLock false
        wakeLink.isEngineOperationallySettledLocked() && wakeLink.isPhysicalWrapperSettledLocked()
    }

    internal companion object {
        internal fun fromOccurrence(occurrence: OperationOccurrence<*>): ControlWakeResidue? {
            val wakeLink = occurrence.controlWakeLink ?: return null
            return ControlWakeResidue(occurrence, occurrence.settlementGate, wakeLink)
        }

        internal fun standalone(settlementGate: ReentrantLock, wakeLink: ControlWakeLink): ControlWakeResidue =
            ControlWakeResidue(null, settlementGate, wakeLink)
    }
}

internal class MetricsEndpointShutdownAction internal constructor(
    private val owner: CaptureMetricsOwner,
) {
    private val claimed = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)
    internal var returned: Boolean? = null
        private set
    internal var failure: Exception? = null
        private set
    internal var directFatal: Throwable? = null
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun runUnlocked(): Boolean {
        if (!claimed.get() || !invoked.compareAndSet(false, true)) return false
        return try {
            owner.requestEndpointShutdown().also { returned = it }
        } catch (ordinary: Exception) {
            failure = ordinary
            false
        } catch (fatal: Throwable) {
            directFatal = fatal
            throw fatal
        }
    }

}

internal class AndroidLaneQuitAction internal constructor(
    private val owner: AndroidCaptureOwner,
) {
    private val claimed = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)
    internal var returned: Boolean? = null
        private set
    internal var failure: Exception? = null
        private set
    internal var directFatal: Throwable? = null
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun runUnlocked(): Boolean {
        if (!claimed.get() || !invoked.compareAndSet(false, true)) return false
        return try {
            owner.requestLaneQuitSafely().also { returned = it }
        } catch (ordinary: Exception) {
            failure = ordinary
            false
        } catch (fatal: Throwable) {
            directFatal = fatal
            throw fatal
        }
    }
}

internal class GlLaneShutdownAction internal constructor(
    private val owner: GlPipelineOwner,
    private val command: GlPipelineOwner.OrderlyShutdownCommand,
) {
    private val claimed = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)
    internal var returned: Boolean? = null
        private set
    internal var failure: Exception? = null
        private set
    internal var directFatal: Throwable? = null
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun matches(expectedOwner: GlPipelineOwner, expectedCommand: GlPipelineOwner.OrderlyShutdownCommand): Boolean =
        owner === expectedOwner && command === expectedCommand

    internal fun runUnlocked(): Boolean {
        if (!claimed.get() || !invoked.compareAndSet(false, true)) return false
        return try {
            owner.requestOrderlyShutdown(command).also { returned = it }
        } catch (ordinary: Exception) {
            failure = ordinary
            false
        } catch (fatal: Throwable) {
            directFatal = fatal
            throw fatal
        }
    }
}

internal class JpegEndpointShutdownAction internal constructor(
    private val owner: JpegRuntimeOwner,
) {
    private val claimed = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)
    internal var returned: Boolean? = null
        private set
    internal var failure: Exception? = null
        private set
    internal var directFatal: Throwable? = null
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun runUnlocked(): Boolean {
        if (!claimed.get() || !invoked.compareAndSet(false, true)) return false
        return try {
            owner.requestJpegShutdown().also { returned = it }
        } catch (ordinary: Exception) {
            failure = ordinary
            false
        } catch (fatal: Throwable) {
            directFatal = fatal
            throw fatal
        }
    }
}

internal class StorageRetirementAction internal constructor(
    private val owner: EncodedStorageOwner,
    private val releasedLease: EncodedStorageOwner.EncodedPayloadLease?,
    private val unpublished: EncodedStorageOwner.UnpublishedEncodedPayload?,
    private val latest: EncodedStorageOwner.PublishedEncodedPayload?,
) {
    private val claimed = AtomicBoolean(false)
    private val invoked = AtomicBoolean(false)
    internal var returned: Boolean? = null
        private set
    internal var failure: Exception? = null
        private set
    internal var directFatal: Throwable? = null
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun matchesCurrent(expectedOwner: EncodedStorageOwner): Boolean =
        owner === expectedOwner && expectedOwner.production == null && expectedOwner.lease === releasedLease &&
                expectedOwner.unpublished === unpublished && expectedOwner.latest === latest

    internal fun runUnlocked(): Boolean {
        if (!claimed.get() || !invoked.compareAndSet(false, true)) return false
        return try {
            val exactLease = releasedLease
            if (exactLease != null && !owner.consumeReleasedLease(exactLease)) {
                returned = false
                return false
            }
            val exactUnpublished = unpublished
            if (exactUnpublished != null && !owner.retireUnpublished(exactUnpublished)) {
                returned = false
                return false
            }
            val exactLatest = latest
            if (exactLatest != null && !owner.retireLatest(exactLatest)) {
                returned = false
                return false
            }
            true.also { returned = it }
        } catch (ordinary: Exception) {
            failure = ordinary
            false
        } catch (fatal: Throwable) {
            directFatal = fatal
            throw fatal
        }
    }

    internal companion object {
        internal fun prepare(owner: EncodedStorageOwner): StorageRetirementAction? {
            if (owner.production != null) return null
            val exactLease = owner.lease
            if (exactLease != null && !exactLease.isReleased) return null
            return StorageRetirementAction(owner, exactLease, owner.unpublished, owner.latest)
        }
    }
}

internal class CleanupOwner internal constructor(
    private val sessionGate: ReentrantLock,
) {

    internal val quarantineRoot: SessionQuarantineRoot = SessionQuarantineRoot()
    internal val metricsRoot: MetricsCleanupRoot = MetricsCleanupRoot()
    internal val androidRoot: AndroidCleanupRoot = AndroidCleanupRoot()
    internal val preparedTargetRoot: PreparedTargetCleanupRoot = PreparedTargetCleanupRoot()
    internal val currentTargetRoot: CurrentTargetCleanupRoot = CurrentTargetCleanupRoot()
    internal val glRoot: GlCleanupRoot = GlCleanupRoot()
    internal val jpegRoot: JpegCleanupRoot = JpegCleanupRoot()
    internal val storageRoot: StorageCleanupRoot = StorageCleanupRoot()
    internal val controlResidueRoot: ControlResidueCleanupRoot = ControlResidueCleanupRoot()

    internal fun attachMetrics(owner: CaptureMetricsOwner): CleanupMutation {
        checkControllerGate()
        if (metricsRoot.owner === owner) return CleanupMutation.None
        return if (metricsRoot.attach(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachAndroid(owner: AndroidCaptureOwner): CleanupMutation {
        checkControllerGate()
        if (androidRoot.owner === owner) return CleanupMutation.None
        return if (androidRoot.attach(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachPreparedTarget(target: PreparedTarget): CleanupMutation {
        checkControllerGate()
        if (preparedTargetRoot.target === target) return CleanupMutation.None
        return if (preparedTargetRoot.attach(target)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachCurrentTarget(target: CurrentTarget): CleanupMutation {
        checkControllerGate()
        if (currentTargetRoot.target === target) return CleanupMutation.None
        return if (currentTargetRoot.attach(target)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachGl(owner: GlPipelineOwner): CleanupMutation {
        checkControllerGate()
        if (glRoot.owner === owner) return CleanupMutation.None
        return if (glRoot.attach(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachJpeg(owner: JpegRuntimeOwner): CleanupMutation {
        checkControllerGate()
        if (jpegRoot.owner === owner) return CleanupMutation.None
        return if (jpegRoot.attach(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachFramework(owner: FrameworkJpegOwner): CleanupMutation {
        checkControllerGate()
        if (jpegRoot.frameworkOwner === owner) return CleanupMutation.None
        return if (jpegRoot.attachFramework(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun reduceFramework(owner: FrameworkJpegOwner): CleanupMutation {
        checkControllerGate()
        return if (jpegRoot.clearFramework(owner)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun attachStorage(owner: EncodedStorageOwner): CleanupMutation {
        checkControllerGate()
        if (storageRoot.owner === owner) return CleanupMutation.None
        return if (storageRoot.attach(owner)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachControlResidue(residue: ControlWakeResidue): CleanupMutation {
        checkControllerGate()
        if (controlResidueRoot.residue === residue) return CleanupMutation.None
        return if (controlResidueRoot.attach(residue)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun attachReturnedNativeFatal(child: ReturnedNativeFatalCleanupChild): CleanupMutation {
        checkControllerGate()
        if (jpegRoot.owner !== child.owner || storageRoot.owner !== child.storage) return CleanupMutation.None
        val existing = jpegRoot.returnedNativeFatal
        if (existing != null) return CleanupMutation.None
        return if (jpegRoot.attachReturnedNativeFatal(child)) CleanupMutation.RootAttached else CleanupMutation.None
    }

    internal fun claimMetricsShutdownAction(): MetricsEndpointShutdownAction? {
        checkControllerGate()
        return metricsRoot.claimShutdownAction()
    }

    internal fun claimAndroidQuitAction(): AndroidLaneQuitAction? {
        checkControllerGate()
        return androidRoot.claimQuitAction()
    }

    internal fun selectGlShutdownOwner(): GlPipelineOwner? {
        checkControllerGate()
        if (preparedTargetRoot.target != null || currentTargetRoot.target != null || quarantineRoot.targetChild != null ||
            glRoot.hasShutdownAction
        ) {
            return null
        }
        return glRoot.owner
    }

    internal fun claimExistingGlShutdownAction(): GlLaneShutdownAction? {
        checkControllerGate()
        return glRoot.claimExistingShutdownAction()
    }

    internal fun installAndClaimGlShutdownAction(
        owner: GlPipelineOwner,
        command: GlPipelineOwner.OrderlyShutdownCommand,
        candidate: GlLaneShutdownAction,
    ): GlLaneShutdownAction? {
        checkControllerGate()
        if (preparedTargetRoot.target != null || currentTargetRoot.target != null || quarantineRoot.targetChild != null) {
            return null
        }
        return glRoot.installAndClaimShutdownAction(owner, command, candidate)
    }

    internal fun claimJpegShutdownAction(): JpegEndpointShutdownAction? {
        checkControllerGate()
        if (quarantineRoot.returnedNativeFatal != null) return null
        return jpegRoot.claimShutdownAction()
    }

    internal fun claimExistingStorageRetirementAction(): StorageRetirementAction? {
        checkControllerGate()
        if (jpegRoot.returnedNativeFatal != null || quarantineRoot.returnedNativeFatal != null) return null
        return storageRoot.claimExistingRetirementAction()
    }

    internal fun selectStorageRetirementOwner(): EncodedStorageOwner? {
        checkControllerGate()
        if (jpegRoot.returnedNativeFatal != null || quarantineRoot.returnedNativeFatal != null) return null
        return storageRoot.selectRetirementOwner()
    }

    internal fun installAndClaimStorageRetirementAction(
        owner: EncodedStorageOwner,
        action: StorageRetirementAction,
    ): StorageRetirementAction? {
        checkControllerGate()
        if (jpegRoot.returnedNativeFatal != null || quarantineRoot.returnedNativeFatal != null) return null
        return storageRoot.installAndClaimRetirementAction(owner, action)
    }

    internal fun reduceMetrics(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        return if (metricsRoot.reduce(receipt)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceAndroid(receipt: AndroidLaneTerminationReceipt): CleanupMutation {
        checkControllerGate()
        return if (androidRoot.reduce(receipt)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reducePreparedTargetProvenComplete(target: PreparedTarget): CleanupMutation {
        checkControllerGate()
        return if (preparedTargetRoot.reduceProvenComplete(target)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceCurrentTargetProvenComplete(target: CurrentTarget): CleanupMutation {
        checkControllerGate()
        return if (currentTargetRoot.reduceProvenComplete(target)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceGl(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        return if (glRoot.reduce(receipt)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceJpeg(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        if (quarantineRoot.returnedNativeFatal != null) return CleanupMutation.None
        return if (jpegRoot.reduce(receipt)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceStorage(owner: EncodedStorageOwner): CleanupMutation {
        checkControllerGate()
        return if (storageRoot.reduce(owner)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun reduceControlResidueProvenSettled(residue: ControlWakeResidue): CleanupMutation {
        checkControllerGate()
        return if (controlResidueRoot.reduceProvenSettled(residue)) CleanupMutation.RootReduced else CleanupMutation.None
    }

    internal fun selectReturnedNativeFatal(): ReturnedNativeFatalCleanupChild? {
        checkControllerGate()
        return jpegRoot.returnedNativeFatal ?: quarantineRoot.returnedNativeFatal
    }

    internal fun applyReturnedNativeFatalReduction(
        child: ReturnedNativeFatalCleanupChild,
        reduction: NativeEncodeFatalCleanupSettlement,
    ): CleanupMutation {
        checkControllerGate()
        return when (reduction) {
            NativeEncodeFatalCleanupSettlement.NotReady -> CleanupMutation.None
            NativeEncodeFatalCleanupSettlement.Reduced -> when {
                jpegRoot.clearReturnedNativeFatal(child) -> CleanupMutation.RootReduced
                quarantineRoot.reduceReturnedNativeFatal(child) -> CleanupMutation.QuarantineReduced
                else -> CleanupMutation.None
            }

            NativeEncodeFatalCleanupSettlement.UnsafeResidue -> {
                if (jpegRoot.returnedNativeFatal !== child) return CleanupMutation.None
                if (!quarantineRoot.attachReturnedNativeFatal(child)) return CleanupMutation.None
                check(jpegRoot.clearReturnedNativeFatal(child))
                CleanupMutation.QuarantineAttached
            }
        }
    }

    internal fun quarantineMetrics(owner: CaptureMetricsOwner): CleanupMutation {
        checkControllerGate()
        if (metricsRoot.owner !== owner ||
            !quarantineRoot.attachMetrics(owner, metricsRoot.retainedShutdownAction)
        ) {
            return CleanupMutation.None
        }
        check(metricsRoot.detach(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineAndroid(owner: AndroidCaptureOwner): CleanupMutation {
        checkControllerGate()
        if (androidRoot.owner !== owner ||
            !quarantineRoot.attachAndroid(owner, androidRoot.retainedQuitAction)
        ) {
            return CleanupMutation.None
        }
        check(androidRoot.detach(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantinePreparedTarget(target: PreparedTarget): CleanupMutation {
        checkControllerGate()
        if (preparedTargetRoot.target !== target || !quarantineRoot.attachTarget(target.quarantineChild)) {
            return CleanupMutation.None
        }
        check(preparedTargetRoot.detach(target))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineCurrentTarget(target: CurrentTarget): CleanupMutation {
        checkControllerGate()
        if (currentTargetRoot.target !== target || !quarantineRoot.attachTarget(target.quarantineChild)) {
            return CleanupMutation.None
        }
        check(currentTargetRoot.detach(target))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineGl(owner: GlPipelineOwner): CleanupMutation {
        checkControllerGate()
        if (glRoot.owner !== owner || !quarantineRoot.attachGl(owner, glRoot.retainedShutdownAction)) {
            return CleanupMutation.None
        }
        check(glRoot.detach(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineJpeg(owner: JpegRuntimeOwner): CleanupMutation {
        checkControllerGate()
        if (jpegRoot.owner !== owner || jpegRoot.returnedNativeFatal != null ||
            !quarantineRoot.attachJpeg(owner, jpegRoot.retainedShutdownAction)
        ) {
            return CleanupMutation.None
        }
        check(jpegRoot.detach(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineFramework(owner: FrameworkJpegOwner): CleanupMutation {
        checkControllerGate()
        if (jpegRoot.frameworkOwner !== owner || !quarantineRoot.attachFramework(owner)) return CleanupMutation.None
        check(jpegRoot.clearFramework(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineStorage(owner: EncodedStorageOwner): CleanupMutation {
        checkControllerGate()
        if (storageRoot.owner !== owner ||
            !quarantineRoot.attachStorage(owner, storageRoot.retainedRetirementAction)
        ) {
            return CleanupMutation.None
        }
        check(storageRoot.detach(owner))
        return CleanupMutation.QuarantineAttached
    }

    internal fun quarantineControlResidue(residue: ControlWakeResidue): CleanupMutation {
        checkControllerGate()
        if (controlResidueRoot.residue !== residue || !quarantineRoot.attachControlResidue(residue)) {
            return CleanupMutation.None
        }
        check(controlResidueRoot.detach(residue))
        return CleanupMutation.QuarantineAttached
    }

    internal fun reduceQuarantinedMetrics(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        val owner = quarantineRoot.metrics ?: return CleanupMutation.None
        if (owner.observedFatal != null || owner.endpointTerminationReceipt !== receipt ||
            !quarantineRoot.reduceMetrics(owner)
        ) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedAndroid(receipt: AndroidLaneTerminationReceipt): CleanupMutation {
        checkControllerGate()
        val owner = quarantineRoot.android ?: return CleanupMutation.None
        if (owner.observedLaneFatal != null || owner.laneTerminationReceipt !== receipt ||
            !quarantineRoot.reduceAndroid(owner)
        ) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedPreparedTargetProvenComplete(target: PreparedTarget): CleanupMutation {
        checkControllerGate()
        return if (quarantineRoot.reduceTarget(target.quarantineChild)) CleanupMutation.QuarantineReduced else CleanupMutation.None
    }

    internal fun reduceQuarantinedCurrentTargetProvenComplete(target: CurrentTarget): CleanupMutation {
        checkControllerGate()
        return if (quarantineRoot.reduceTarget(target.quarantineChild)) CleanupMutation.QuarantineReduced else CleanupMutation.None
    }

    internal fun reduceQuarantinedGl(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        val owner = quarantineRoot.gl ?: return CleanupMutation.None
        if (!owner.acceptsLaneTerminationReceipt(receipt) || owner.laneTerminationReceipt !== receipt ||
            !quarantineRoot.reduceGl(owner)
        ) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedJpeg(receipt: PrivateExecutorTerminationReceipt): CleanupMutation {
        checkControllerGate()
        if (quarantineRoot.returnedNativeFatal != null) return CleanupMutation.None
        val owner = quarantineRoot.jpeg ?: return CleanupMutation.None
        if (!owner.isJpegCleanupComplete(receipt) || !quarantineRoot.reduceJpeg(owner)) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedFrameworkProvenComplete(owner: FrameworkJpegOwner): CleanupMutation {
        checkControllerGate()
        if (quarantineRoot.framework !== owner || !owner.hasNoBitmap() || !quarantineRoot.reduceFramework(owner)) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedStorage(owner: EncodedStorageOwner): CleanupMutation {
        checkControllerGate()
        if (quarantineRoot.storage !== owner || owner.production != null || owner.unpublished != null ||
            owner.latest != null || owner.displaced != null || owner.lease != null ||
            !quarantineRoot.reduceStorage(owner)
        ) {
            return CleanupMutation.None
        }
        return CleanupMutation.QuarantineReduced
    }

    internal fun reduceQuarantinedControlResidueProvenSettled(residue: ControlWakeResidue): CleanupMutation {
        checkControllerGate()
        return if (quarantineRoot.reduceControlResidue(residue)) CleanupMutation.QuarantineReduced else CleanupMutation.None
    }

    internal val controlShutdownReadiness: ControlShutdownReadiness
        get() {
            checkControllerGate()
            return if (metricsRoot.owner != null || androidRoot.owner != null || preparedTargetRoot.target != null ||
                currentTargetRoot.target != null || glRoot.owner != null || jpegRoot.owner != null ||
                storageRoot.owner != null || controlResidueRoot.residue != null || !quarantineRoot.isEmpty
            ) {
                ControlShutdownReadiness.BlockedByCleanup
            } else {
                ControlShutdownReadiness.AwaitingStage5Delivery
            }
        }

    private fun checkControllerGate() {
        check(sessionGate.isHeldByCurrentThread)
    }
}
