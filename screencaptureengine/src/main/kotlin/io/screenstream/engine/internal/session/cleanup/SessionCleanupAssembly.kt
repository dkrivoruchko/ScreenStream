package io.screenstream.engine.internal.session.cleanup

import io.screenstream.engine.internal.session.runtime.AndroidRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.ControlRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.DeliveryRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.GlRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.JpegRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.MetricsRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.StorageRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.TargetRuntimeOwnership
import java.util.concurrent.atomic.AtomicReference

/**
 * Fixed, typed assembly boundary for the eight Session cleanup roots.
 *
 * Claim, exact resolution, and terminal sealing with absent-at-cutoff assignment linearize at
 * the one whole-state CAS below. This object owns no leaf generations, operations, resources, or
 * quarantine children; those remain reachable only through the matching typed root.
 */
internal class SessionCleanupAssembly internal constructor(
    internal val sessionGeneration: Long,
) {
    init {
        require(sessionGeneration > 0L)
    }

    private val assemblyIdentity: Any = Any()

    private val state: AtomicReference<AssemblyState> = AtomicReference(
        AssemblyState(
            terminal = AssemblyTerminal.Open,
            control = ControlCleanupSlot.Unclaimed,
            metrics = MetricsCleanupSlot.Unclaimed,
            android = AndroidCleanupSlot.Unclaimed,
            target = TargetCleanupSlot.Unclaimed,
            gl = GlCleanupSlot.Unclaimed,
            jpeg = JpegCleanupSlot.Unclaimed,
            storage = StorageCleanupSlot.Unclaimed,
            delivery = DeliveryCleanupSlot.Unclaimed,
        ),
    )

    internal fun claimControl(owner: ControlRuntimeOwnership): ControlCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return ControlCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.control) {
                ControlCleanupSlot.Unclaimed -> {
                    val pending = ControlCleanupSlot.Pending(
                        ControlCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(control = pending))) {
                        return ControlCleanupClaimResult.Claimed(pending)
                    }
                }

                is ControlCleanupSlot.StructurallyAbsent ->
                    error("Control absence cannot precede terminal seal")

                is ControlCleanupSlot.Pending,
                is ControlCleanupSlot.Owned,
                -> error("Control cleanup slot was already claimed")
            }
        }
    }

    internal fun claimMetrics(owner: MetricsRuntimeOwnership): MetricsCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return MetricsCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.metrics) {
                MetricsCleanupSlot.Unclaimed -> {
                    val pending = MetricsCleanupSlot.Pending(
                        MetricsCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(metrics = pending))) {
                        return MetricsCleanupClaimResult.Claimed(pending)
                    }
                }

                is MetricsCleanupSlot.StructurallyAbsent ->
                    error("Metrics absence cannot precede terminal seal")

                is MetricsCleanupSlot.Pending,
                is MetricsCleanupSlot.Owned,
                -> error("Metrics cleanup slot was already claimed")
            }
        }
    }

    internal fun claimAndroid(owner: AndroidRuntimeOwnership): AndroidCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return AndroidCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.android) {
                AndroidCleanupSlot.Unclaimed -> {
                    val pending = AndroidCleanupSlot.Pending(
                        AndroidCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(android = pending))) {
                        return AndroidCleanupClaimResult.Claimed(pending)
                    }
                }

                is AndroidCleanupSlot.StructurallyAbsent ->
                    error("Android absence cannot precede terminal seal")

                is AndroidCleanupSlot.Pending,
                is AndroidCleanupSlot.Owned,
                -> error("Android cleanup slot was already claimed")
            }
        }
    }

    internal fun claimTarget(owner: TargetRuntimeOwnership): TargetCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return TargetCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.target) {
                TargetCleanupSlot.Unclaimed -> {
                    val pending = TargetCleanupSlot.Pending(
                        TargetCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(target = pending))) {
                        return TargetCleanupClaimResult.Claimed(pending)
                    }
                }

                is TargetCleanupSlot.StructurallyAbsent ->
                    error("Target absence cannot precede terminal seal")

                is TargetCleanupSlot.Pending,
                is TargetCleanupSlot.Owned,
                -> error("Target cleanup slot was already claimed")
            }
        }
    }

    internal fun claimGl(owner: GlRuntimeOwnership): GlCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return GlCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.gl) {
                GlCleanupSlot.Unclaimed -> {
                    val pending = GlCleanupSlot.Pending(
                        GlCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(gl = pending))) {
                        return GlCleanupClaimResult.Claimed(pending)
                    }
                }

                is GlCleanupSlot.StructurallyAbsent ->
                    error("GL absence cannot precede terminal seal")

                is GlCleanupSlot.Pending,
                is GlCleanupSlot.Owned,
                -> error("GL cleanup slot was already claimed")
            }
        }
    }

    internal fun claimJpeg(owner: JpegRuntimeOwnership): JpegCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return JpegCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.jpeg) {
                JpegCleanupSlot.Unclaimed -> {
                    val pending = JpegCleanupSlot.Pending(
                        JpegCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(jpeg = pending))) {
                        return JpegCleanupClaimResult.Claimed(pending)
                    }
                }

                is JpegCleanupSlot.StructurallyAbsent ->
                    error("JPEG absence cannot precede terminal seal")

                is JpegCleanupSlot.Pending,
                is JpegCleanupSlot.Owned,
                -> error("JPEG cleanup slot was already claimed")
            }
        }
    }

    internal fun claimStorage(owner: StorageRuntimeOwnership): StorageCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return StorageCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.storage) {
                StorageCleanupSlot.Unclaimed -> {
                    val pending = StorageCleanupSlot.Pending(
                        StorageCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(storage = pending))) {
                        return StorageCleanupClaimResult.Claimed(pending)
                    }
                }

                is StorageCleanupSlot.StructurallyAbsent ->
                    error("Storage absence cannot precede terminal seal")

                is StorageCleanupSlot.Pending,
                is StorageCleanupSlot.Owned,
                -> error("Storage cleanup slot was already claimed")
            }
        }
    }

    internal fun claimDelivery(owner: DeliveryRuntimeOwnership): DeliveryCleanupClaimResult {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) {
                return DeliveryCleanupClaimResult.CutOff(terminal.aggregate.terminalCutoff)
            }
            when (val slot = snapshot.delivery) {
                DeliveryCleanupSlot.Unclaimed -> {
                    val pending = DeliveryCleanupSlot.Pending(
                        DeliveryCleanupClaim.mint(this, assemblyIdentity, sessionGeneration, owner),
                    )
                    if (state.compareAndSet(snapshot, snapshot.copy(delivery = pending))) {
                        return DeliveryCleanupClaimResult.Claimed(pending)
                    }
                }

                is DeliveryCleanupSlot.StructurallyAbsent ->
                    error("Delivery absence cannot precede terminal seal")

                is DeliveryCleanupSlot.Pending,
                is DeliveryCleanupSlot.Owned,
                -> error("Delivery cleanup slot was already claimed")
            }
        }
    }

    internal fun resolveControl(
        exactClaim: ControlCleanupClaim,
        typedRoot: ControlCleanupRoot,
    ): ControlCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.control) {
                is ControlCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = ControlCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(control = owned))) return owned
                }

                is ControlCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                ControlCleanupSlot.Unclaimed,
                is ControlCleanupSlot.StructurallyAbsent,
                -> error("Control cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveMetrics(
        exactClaim: MetricsCleanupClaim,
        typedRoot: MetricsCleanupRoot,
    ): MetricsCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.metrics) {
                is MetricsCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = MetricsCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(metrics = owned))) return owned
                }

                is MetricsCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                MetricsCleanupSlot.Unclaimed,
                is MetricsCleanupSlot.StructurallyAbsent,
                -> error("Metrics cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveAndroid(
        exactClaim: AndroidCleanupClaim,
        typedRoot: AndroidCleanupRoot,
    ): AndroidCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.android) {
                is AndroidCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = AndroidCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(android = owned))) return owned
                }

                is AndroidCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                AndroidCleanupSlot.Unclaimed,
                is AndroidCleanupSlot.StructurallyAbsent,
                -> error("Android cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveTarget(
        exactClaim: TargetCleanupClaim,
        typedRoot: TargetCleanupRoot,
    ): TargetCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.target) {
                is TargetCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = TargetCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(target = owned))) return owned
                }

                is TargetCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                TargetCleanupSlot.Unclaimed,
                is TargetCleanupSlot.StructurallyAbsent,
                -> error("Target cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveGl(
        exactClaim: GlCleanupClaim,
        typedRoot: GlCleanupRoot,
    ): GlCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.gl) {
                is GlCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = GlCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(gl = owned))) return owned
                }

                is GlCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                GlCleanupSlot.Unclaimed,
                is GlCleanupSlot.StructurallyAbsent,
                -> error("GL cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveJpeg(
        exactClaim: JpegCleanupClaim,
        typedRoot: JpegCleanupRoot,
    ): JpegCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.jpeg) {
                is JpegCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = JpegCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(jpeg = owned))) return owned
                }

                is JpegCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                JpegCleanupSlot.Unclaimed,
                is JpegCleanupSlot.StructurallyAbsent,
                -> error("JPEG cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveStorage(
        exactClaim: StorageCleanupClaim,
        typedRoot: StorageCleanupRoot,
    ): StorageCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.storage) {
                is StorageCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = StorageCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(storage = owned))) return owned
                }

                is StorageCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                StorageCleanupSlot.Unclaimed,
                is StorageCleanupSlot.StructurallyAbsent,
                -> error("Storage cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun resolveDelivery(
        exactClaim: DeliveryCleanupClaim,
        typedRoot: DeliveryCleanupRoot,
    ): DeliveryCleanupSlot.Owned {
        exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        check(typedRoot.exactClaim === exactClaim)
        while (true) {
            val snapshot = state.get()
            when (val slot = snapshot.delivery) {
                is DeliveryCleanupSlot.Pending -> {
                    check(slot.exactClaim === exactClaim)
                    val owned = DeliveryCleanupSlot.Owned(exactClaim, typedRoot)
                    if (state.compareAndSet(snapshot, snapshot.copy(delivery = owned))) return owned
                }

                is DeliveryCleanupSlot.Owned -> {
                    check(slot.exactClaim === exactClaim)
                    check(slot.typedRoot === typedRoot)
                    return slot
                }

                DeliveryCleanupSlot.Unclaimed,
                is DeliveryCleanupSlot.StructurallyAbsent,
                -> error("Delivery cleanup resolution has no matching pending claim")
            }
        }
    }

    internal fun seal(): SessionCleanupTransfer {
        while (true) {
            val snapshot = state.get()
            val terminal = snapshot.terminal
            if (terminal is AssemblyTerminal.Sealed) return terminal.transfer

            val sealAuthority = Any()
            val cutoff = SessionCleanupTerminalCutoff.mint(
                assembly = this,
                assemblyIdentity = assemblyIdentity,
                sessionGeneration = sessionGeneration,
                sealAuthority = sealAuthority,
            )
            val aggregate = SessionCleanupSealedAggregate.mint(
                assembly = this,
                assemblyIdentity = assemblyIdentity,
                terminalCutoff = cutoff,
                sealAuthority = sealAuthority,
            )
            val transfer = SessionCleanupTransfer.mint(aggregate, sealAuthority)
            val sealed = snapshot.copy(
                terminal = AssemblyTerminal.Sealed(aggregate, transfer),
                control = snapshot.control.atCutoff(aggregate, cutoff, sealAuthority),
                metrics = snapshot.metrics.atCutoff(aggregate, cutoff, sealAuthority),
                android = snapshot.android.atCutoff(aggregate, cutoff, sealAuthority),
                target = snapshot.target.atCutoff(aggregate, cutoff, sealAuthority),
                gl = snapshot.gl.atCutoff(aggregate, cutoff, sealAuthority),
                jpeg = snapshot.jpeg.atCutoff(aggregate, cutoff, sealAuthority),
                storage = snapshot.storage.atCutoff(aggregate, cutoff, sealAuthority),
                delivery = snapshot.delivery.atCutoff(aggregate, cutoff, sealAuthority),
            )
            if (state.compareAndSet(snapshot, sealed)) return transfer
        }
    }

    internal fun sealedManifestFor(aggregate: SessionCleanupSealedAggregate): SessionCleanupManifest {
        val snapshot = state.get()
        val terminal = snapshot.terminal as? AssemblyTerminal.Sealed
            ?: error("Cleanup assembly is not sealed")
        check(terminal.aggregate === aggregate)
        aggregate.requireAssemblyBinding(this, assemblyIdentity, sessionGeneration)
        snapshot.control.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.metrics.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.android.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.target.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.gl.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.jpeg.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.storage.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        snapshot.delivery.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
        return SessionCleanupManifest(
            sessionGeneration = sessionGeneration,
            terminalCutoff = aggregate.terminalCutoff,
            control = snapshot.control,
            metrics = snapshot.metrics,
            android = snapshot.android,
            target = snapshot.target,
            gl = snapshot.gl,
            jpeg = snapshot.jpeg,
            storage = snapshot.storage,
            delivery = snapshot.delivery,
        )
    }

    internal fun requireAssemblyAuthority(candidate: Any, generation: Long) {
        check(candidate === assemblyIdentity)
        check(generation == sessionGeneration)
    }
}

private data class AssemblyState(
    val terminal: AssemblyTerminal,
    val control: ControlCleanupSlot,
    val metrics: MetricsCleanupSlot,
    val android: AndroidCleanupSlot,
    val target: TargetCleanupSlot,
    val gl: GlCleanupSlot,
    val jpeg: JpegCleanupSlot,
    val storage: StorageCleanupSlot,
    val delivery: DeliveryCleanupSlot,
)

private sealed class AssemblyTerminal private constructor() {
    data object Open : AssemblyTerminal()

    class Sealed(
        val aggregate: SessionCleanupSealedAggregate,
        val transfer: SessionCleanupTransfer,
    ) : AssemblyTerminal()
}

internal class SessionCleanupTerminalCutoff private constructor(
    private val assembly: SessionCleanupAssembly,
    private val assemblyIdentity: Any,
    internal val sessionGeneration: Long,
    private val sealAuthority: Any,
) {
    init {
        require(sessionGeneration > 0L)
    }

    internal fun requireBoundTo(
        expectedAssembly: SessionCleanupAssembly,
        expectedAssemblyIdentity: Any,
        expectedGeneration: Long,
    ) {
        check(assembly === expectedAssembly)
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal fun requireSealAuthority(candidate: Any) {
        check(candidate === sealAuthority)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            assemblyIdentity: Any,
            sessionGeneration: Long,
            sealAuthority: Any,
        ): SessionCleanupTerminalCutoff {
            assembly.requireAssemblyAuthority(assemblyIdentity, sessionGeneration)
            return SessionCleanupTerminalCutoff(
                assembly,
                assemblyIdentity,
                sessionGeneration,
                sealAuthority,
            )
        }
    }
}

internal class SessionCleanupSealedAggregate private constructor(
    private val assembly: SessionCleanupAssembly,
    private val assemblyIdentity: Any,
    internal val terminalCutoff: SessionCleanupTerminalCutoff,
    private val sealAuthority: Any,
) {
    internal val manifest: SessionCleanupManifest
        get() = assembly.sealedManifestFor(this)

    internal fun requireAssemblyBinding(
        expectedAssembly: SessionCleanupAssembly,
        expectedAssemblyIdentity: Any,
        expectedGeneration: Long,
    ) {
        check(assembly === expectedAssembly)
        check(assemblyIdentity === expectedAssemblyIdentity)
        terminalCutoff.requireBoundTo(
            expectedAssembly,
            expectedAssemblyIdentity,
            expectedGeneration,
        )
    }

    internal fun requireAssemblyProvenance(
        expectedAssemblyIdentity: Any,
        expectedGeneration: Long,
    ) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        assembly.requireAssemblyAuthority(expectedAssemblyIdentity, expectedGeneration)
    }

    internal fun requireSealAuthority(candidate: Any) {
        check(candidate === sealAuthority)
        terminalCutoff.requireSealAuthority(candidate)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            assemblyIdentity: Any,
            terminalCutoff: SessionCleanupTerminalCutoff,
            sealAuthority: Any,
        ): SessionCleanupSealedAggregate {
            assembly.requireAssemblyAuthority(assemblyIdentity, terminalCutoff.sessionGeneration)
            terminalCutoff.requireBoundTo(
                assembly,
                assemblyIdentity,
                terminalCutoff.sessionGeneration,
            )
            terminalCutoff.requireSealAuthority(sealAuthority)
            return SessionCleanupSealedAggregate(
                assembly,
                assemblyIdentity,
                terminalCutoff,
                sealAuthority,
            )
        }
    }
}

internal class SessionCleanupManifest internal constructor(
    internal val sessionGeneration: Long,
    internal val terminalCutoff: SessionCleanupTerminalCutoff,
    internal val control: ControlCleanupSlot,
    internal val metrics: MetricsCleanupSlot,
    internal val android: AndroidCleanupSlot,
    internal val target: TargetCleanupSlot,
    internal val gl: GlCleanupSlot,
    internal val jpeg: JpegCleanupSlot,
    internal val storage: StorageCleanupSlot,
    internal val delivery: DeliveryCleanupSlot,
)

/** Each claim's concrete Kotlin type is its fixed slot identity. */
internal class ControlCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: ControlRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: ControlRuntimeOwnership,
        ): ControlCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return ControlCleanupClaim(identity, generation, owner)
        }
    }
}

internal class MetricsCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: MetricsRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: MetricsRuntimeOwnership,
        ): MetricsCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return MetricsCleanupClaim(identity, generation, owner)
        }
    }
}

internal class AndroidCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: AndroidRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: AndroidRuntimeOwnership,
        ): AndroidCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return AndroidCleanupClaim(identity, generation, owner)
        }
    }
}

internal class TargetCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: TargetRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: TargetRuntimeOwnership,
        ): TargetCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return TargetCleanupClaim(identity, generation, owner)
        }
    }
}

internal class GlCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: GlRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: GlRuntimeOwnership,
        ): GlCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return GlCleanupClaim(identity, generation, owner)
        }
    }
}

internal class JpegCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: JpegRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: JpegRuntimeOwnership,
        ): JpegCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return JpegCleanupClaim(identity, generation, owner)
        }
    }
}

internal class StorageCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: StorageRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: StorageRuntimeOwnership,
        ): StorageCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return StorageCleanupClaim(identity, generation, owner)
        }
    }
}

internal class DeliveryCleanupClaim private constructor(
    private val assemblyIdentity: Any,
    private val sessionGeneration: Long,
    internal val owner: DeliveryRuntimeOwnership,
) {
    internal fun requireBoundTo(expectedAssemblyIdentity: Any, expectedGeneration: Long) {
        check(assemblyIdentity === expectedAssemblyIdentity)
        check(sessionGeneration == expectedGeneration)
    }

    internal companion object {
        internal fun mint(
            assembly: SessionCleanupAssembly,
            identity: Any,
            generation: Long,
            owner: DeliveryRuntimeOwnership,
        ): DeliveryCleanupClaim {
            assembly.requireAssemblyAuthority(identity, generation)
            return DeliveryCleanupClaim(identity, generation, owner)
        }
    }
}

/** Exact assembly-cutoff absence; never a leaf inapplicable or release receipt. */
private class AssemblyAbsentAtCutoffBinding(
    private val aggregate: SessionCleanupSealedAggregate,
    private val terminalCutoff: SessionCleanupTerminalCutoff,
) {
    fun requireBoundTo(
        expectedAssemblyIdentity: Any,
        expectedGeneration: Long,
        expectedAggregate: SessionCleanupSealedAggregate,
    ) {
        check(aggregate === expectedAggregate)
        check(terminalCutoff === expectedAggregate.terminalCutoff)
        check(terminalCutoff.sessionGeneration == expectedGeneration)
        expectedAggregate.requireAssemblyProvenance(expectedAssemblyIdentity, expectedGeneration)
    }
}

internal sealed class ControlCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : ControlCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class MetricsCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : MetricsCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class AndroidCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : AndroidCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class TargetCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : TargetCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class GlCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : GlCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class JpegCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : JpegCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class StorageCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : StorageCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class DeliveryCleanupAbsenceEvidence private constructor(
    private val binding: AssemblyAbsentAtCutoffBinding,
) {
    class AtCutoff private constructor(
        aggregate: SessionCleanupSealedAggregate,
        internal val terminalCutoff: SessionCleanupTerminalCutoff,
    ) : DeliveryCleanupAbsenceEvidence(AssemblyAbsentAtCutoffBinding(aggregate, terminalCutoff)) {
        internal companion object {
            internal fun mint(
                aggregate: SessionCleanupSealedAggregate,
                cutoff: SessionCleanupTerminalCutoff,
                sealAuthority: Any,
            ): AtCutoff {
                aggregate.requireSealAuthority(sealAuthority)
                check(cutoff === aggregate.terminalCutoff)
                return AtCutoff(aggregate, cutoff)
            }
        }
    }

    internal fun requireBoundTo(identity: Any, generation: Long, aggregate: SessionCleanupSealedAggregate) {
        binding.requireBoundTo(identity, generation, aggregate)
    }
}

internal sealed class ControlCleanupSlot private constructor() {
    data object Unclaimed : ControlCleanupSlot()
    class Pending(internal val exactClaim: ControlCleanupClaim) : ControlCleanupSlot()
    class Owned(
        internal val exactClaim: ControlCleanupClaim,
        internal val typedRoot: ControlCleanupRoot,
    ) : ControlCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: ControlCleanupAbsenceEvidence,
    ) : ControlCleanupSlot()
}

internal sealed class MetricsCleanupSlot private constructor() {
    data object Unclaimed : MetricsCleanupSlot()
    class Pending(internal val exactClaim: MetricsCleanupClaim) : MetricsCleanupSlot()
    class Owned(
        internal val exactClaim: MetricsCleanupClaim,
        internal val typedRoot: MetricsCleanupRoot,
    ) : MetricsCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: MetricsCleanupAbsenceEvidence,
    ) : MetricsCleanupSlot()
}

internal sealed class AndroidCleanupSlot private constructor() {
    data object Unclaimed : AndroidCleanupSlot()
    class Pending(internal val exactClaim: AndroidCleanupClaim) : AndroidCleanupSlot()
    class Owned(
        internal val exactClaim: AndroidCleanupClaim,
        internal val typedRoot: AndroidCleanupRoot,
    ) : AndroidCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: AndroidCleanupAbsenceEvidence,
    ) : AndroidCleanupSlot()
}

internal sealed class TargetCleanupSlot private constructor() {
    data object Unclaimed : TargetCleanupSlot()
    class Pending(internal val exactClaim: TargetCleanupClaim) : TargetCleanupSlot()
    class Owned(
        internal val exactClaim: TargetCleanupClaim,
        internal val typedRoot: TargetCleanupRoot,
    ) : TargetCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: TargetCleanupAbsenceEvidence,
    ) : TargetCleanupSlot()
}

internal sealed class GlCleanupSlot private constructor() {
    data object Unclaimed : GlCleanupSlot()
    class Pending(internal val exactClaim: GlCleanupClaim) : GlCleanupSlot()
    class Owned(
        internal val exactClaim: GlCleanupClaim,
        internal val typedRoot: GlCleanupRoot,
    ) : GlCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: GlCleanupAbsenceEvidence,
    ) : GlCleanupSlot()
}

internal sealed class JpegCleanupSlot private constructor() {
    data object Unclaimed : JpegCleanupSlot()
    class Pending(internal val exactClaim: JpegCleanupClaim) : JpegCleanupSlot()
    class Owned(
        internal val exactClaim: JpegCleanupClaim,
        internal val typedRoot: JpegCleanupRoot,
    ) : JpegCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: JpegCleanupAbsenceEvidence,
    ) : JpegCleanupSlot()
}

internal sealed class StorageCleanupSlot private constructor() {
    data object Unclaimed : StorageCleanupSlot()
    class Pending(internal val exactClaim: StorageCleanupClaim) : StorageCleanupSlot()
    class Owned(
        internal val exactClaim: StorageCleanupClaim,
        internal val typedRoot: StorageCleanupRoot,
    ) : StorageCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: StorageCleanupAbsenceEvidence,
    ) : StorageCleanupSlot()
}

internal sealed class DeliveryCleanupSlot private constructor() {
    data object Unclaimed : DeliveryCleanupSlot()
    class Pending(internal val exactClaim: DeliveryCleanupClaim) : DeliveryCleanupSlot()
    class Owned(
        internal val exactClaim: DeliveryCleanupClaim,
        internal val typedRoot: DeliveryCleanupRoot,
    ) : DeliveryCleanupSlot()
    class StructurallyAbsent(
        internal val evidence: DeliveryCleanupAbsenceEvidence,
    ) : DeliveryCleanupSlot()
}

internal sealed class ControlCleanupClaimResult private constructor() {
    class Claimed(internal val pending: ControlCleanupSlot.Pending) : ControlCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : ControlCleanupClaimResult()
}

internal sealed class MetricsCleanupClaimResult private constructor() {
    class Claimed(internal val pending: MetricsCleanupSlot.Pending) : MetricsCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : MetricsCleanupClaimResult()
}

internal sealed class AndroidCleanupClaimResult private constructor() {
    class Claimed(internal val pending: AndroidCleanupSlot.Pending) : AndroidCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : AndroidCleanupClaimResult()
}

internal sealed class TargetCleanupClaimResult private constructor() {
    class Claimed(internal val pending: TargetCleanupSlot.Pending) : TargetCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : TargetCleanupClaimResult()
}

internal sealed class GlCleanupClaimResult private constructor() {
    class Claimed(internal val pending: GlCleanupSlot.Pending) : GlCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : GlCleanupClaimResult()
}

internal sealed class JpegCleanupClaimResult private constructor() {
    class Claimed(internal val pending: JpegCleanupSlot.Pending) : JpegCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : JpegCleanupClaimResult()
}

internal sealed class StorageCleanupClaimResult private constructor() {
    class Claimed(internal val pending: StorageCleanupSlot.Pending) : StorageCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : StorageCleanupClaimResult()
}

internal sealed class DeliveryCleanupClaimResult private constructor() {
    class Claimed(internal val pending: DeliveryCleanupSlot.Pending) : DeliveryCleanupClaimResult()
    class CutOff(internal val cutoff: SessionCleanupTerminalCutoff) : DeliveryCleanupClaimResult()
}

private fun ControlCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): ControlCleanupSlot = if (this === ControlCleanupSlot.Unclaimed) {
    ControlCleanupSlot.StructurallyAbsent(
        ControlCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun MetricsCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): MetricsCleanupSlot = if (this === MetricsCleanupSlot.Unclaimed) {
    MetricsCleanupSlot.StructurallyAbsent(
        MetricsCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun AndroidCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): AndroidCleanupSlot = if (this === AndroidCleanupSlot.Unclaimed) {
    AndroidCleanupSlot.StructurallyAbsent(
        AndroidCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun TargetCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): TargetCleanupSlot = if (this === TargetCleanupSlot.Unclaimed) {
    TargetCleanupSlot.StructurallyAbsent(
        TargetCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun GlCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): GlCleanupSlot = if (this === GlCleanupSlot.Unclaimed) {
    GlCleanupSlot.StructurallyAbsent(
        GlCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun JpegCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): JpegCleanupSlot = if (this === JpegCleanupSlot.Unclaimed) {
    JpegCleanupSlot.StructurallyAbsent(
        JpegCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun StorageCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): StorageCleanupSlot = if (this === StorageCleanupSlot.Unclaimed) {
    StorageCleanupSlot.StructurallyAbsent(
        StorageCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun DeliveryCleanupSlot.atCutoff(
    aggregate: SessionCleanupSealedAggregate,
    cutoff: SessionCleanupTerminalCutoff,
    sealAuthority: Any,
): DeliveryCleanupSlot = if (this === DeliveryCleanupSlot.Unclaimed) {
    DeliveryCleanupSlot.StructurallyAbsent(
        DeliveryCleanupAbsenceEvidence.AtCutoff.mint(aggregate, cutoff, sealAuthority),
    )
} else {
    this
}

private fun ControlCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        ControlCleanupSlot.Unclaimed -> error("Unclaimed Control slot escaped terminal seal")
        is ControlCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is ControlCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is ControlCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun MetricsCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        MetricsCleanupSlot.Unclaimed -> error("Unclaimed Metrics slot escaped terminal seal")
        is MetricsCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is MetricsCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is MetricsCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun AndroidCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        AndroidCleanupSlot.Unclaimed -> error("Unclaimed Android slot escaped terminal seal")
        is AndroidCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is AndroidCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is AndroidCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun TargetCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        TargetCleanupSlot.Unclaimed -> error("Unclaimed Target slot escaped terminal seal")
        is TargetCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is TargetCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is TargetCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun GlCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        GlCleanupSlot.Unclaimed -> error("Unclaimed GL slot escaped terminal seal")
        is GlCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is GlCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is GlCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun JpegCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        JpegCleanupSlot.Unclaimed -> error("Unclaimed JPEG slot escaped terminal seal")
        is JpegCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is JpegCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is JpegCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun StorageCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        StorageCleanupSlot.Unclaimed -> error("Unclaimed Storage slot escaped terminal seal")
        is StorageCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is StorageCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is StorageCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}

private fun DeliveryCleanupSlot.requireBoundTo(
    assemblyIdentity: Any,
    sessionGeneration: Long,
    aggregate: SessionCleanupSealedAggregate,
) {
    when (this) {
        DeliveryCleanupSlot.Unclaimed -> error("Unclaimed Delivery slot escaped terminal seal")
        is DeliveryCleanupSlot.Pending -> exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
        is DeliveryCleanupSlot.Owned -> {
            exactClaim.requireBoundTo(assemblyIdentity, sessionGeneration)
            check(typedRoot.exactClaim === exactClaim)
        }
        is DeliveryCleanupSlot.StructurallyAbsent ->
            evidence.requireBoundTo(assemblyIdentity, sessionGeneration, aggregate)
    }
}
