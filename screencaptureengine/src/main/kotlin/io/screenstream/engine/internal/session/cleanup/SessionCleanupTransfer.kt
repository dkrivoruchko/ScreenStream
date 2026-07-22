package io.screenstream.engine.internal.session.cleanup

import io.screenstream.engine.internal.session.runtime.AndroidRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.ControlRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.DeliveryRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.GlRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.JpegRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.MetricsRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.StorageRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.TargetRuntimeOwnership

/** Identity-bearing handle to the one sealed aggregate minted by [SessionCleanupAssembly]. */
internal class SessionCleanupTransfer private constructor(
    internal val sealedAggregate: SessionCleanupSealedAggregate,
    internal val dependencyToken: SessionCleanupDependencyToken,
) {
    internal val sessionGeneration: Long = sealedAggregate.terminalCutoff.sessionGeneration

    internal val manifest: SessionCleanupManifest
        get() = sealedAggregate.manifest

    internal companion object {
        internal fun mint(
            sealedAggregate: SessionCleanupSealedAggregate,
            sealAuthority: Any,
        ): SessionCleanupTransfer {
            sealedAggregate.requireSealAuthority(sealAuthority)
            return SessionCleanupTransfer(
                sealedAggregate,
                SessionCleanupDependencyToken.mint(sealedAggregate, sealAuthority),
            )
        }
    }
}

/** Opaque identity tied to one canonical sealed aggregate; it carries no settlement policy. */
internal class SessionCleanupDependencyToken private constructor(
    internal val sealedAggregate: SessionCleanupSealedAggregate,
) {
    internal companion object {
        internal fun mint(
            sealedAggregate: SessionCleanupSealedAggregate,
            sealAuthority: Any,
        ): SessionCleanupDependencyToken {
            sealedAggregate.requireSealAuthority(sealAuthority)
            return SessionCleanupDependencyToken(sealedAggregate)
        }
    }
}

/** Fixed typed roots retain only their exact assembly claim; owner identity is derived from it. */
internal class ControlCleanupRoot internal constructor(
    internal val exactClaim: ControlCleanupClaim,
) {
    internal val owner: ControlRuntimeOwnership
        get() = exactClaim.owner
}

internal class MetricsCleanupRoot internal constructor(
    internal val exactClaim: MetricsCleanupClaim,
) {
    internal val owner: MetricsRuntimeOwnership
        get() = exactClaim.owner
}

internal class AndroidCleanupRoot internal constructor(
    internal val exactClaim: AndroidCleanupClaim,
) {
    internal val owner: AndroidRuntimeOwnership
        get() = exactClaim.owner
}

internal class TargetCleanupRoot internal constructor(
    internal val exactClaim: TargetCleanupClaim,
) {
    internal val owner: TargetRuntimeOwnership
        get() = exactClaim.owner
}

internal class GlCleanupRoot internal constructor(
    internal val exactClaim: GlCleanupClaim,
) {
    internal val owner: GlRuntimeOwnership
        get() = exactClaim.owner
}

internal class JpegCleanupRoot internal constructor(
    internal val exactClaim: JpegCleanupClaim,
) {
    internal val owner: JpegRuntimeOwnership
        get() = exactClaim.owner
}

internal class StorageCleanupRoot internal constructor(
    internal val exactClaim: StorageCleanupClaim,
) {
    internal val owner: StorageRuntimeOwnership
        get() = exactClaim.owner
}

internal class DeliveryCleanupRoot internal constructor(
    internal val exactClaim: DeliveryCleanupClaim,
) {
    internal val owner: DeliveryRuntimeOwnership
        get() = exactClaim.owner
}
