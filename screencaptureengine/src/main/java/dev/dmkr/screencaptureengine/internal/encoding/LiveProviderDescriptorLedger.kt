@file:Suppress("unused") // Intentionally dormant until provider integration.

package dev.dmkr.screencaptureengine.internal.encoding

import dev.dmkr.screencaptureengine.internal.policy.ScreenCaptureEnginePolicyDefaults
import java.util.EnumMap
import java.util.IdentityHashMap

internal enum class ProviderDescriptorRetentionRole {
    Desired,
    Candidate,
    Active,
    Retiring,
    Cleanup,
    Late,
    Quarantine,
}

/** Move-only ledger capability. Ordinary object identity is its complete identity contract. */
internal sealed interface ProviderDescriptorRetentionToken

private class LedgerRetentionToken : ProviderDescriptorRetentionToken

internal sealed interface ProviderDescriptorReserveResult {
    data class Reserved(
        val token: ProviderDescriptorRetentionToken,
        val firstAdmission: Boolean,
        val liveReferenceCount: Int,
    ) : ProviderDescriptorReserveResult

    data object CapacityExceeded : ProviderDescriptorReserveResult
}

internal sealed interface ProviderDescriptorForkResult {
    data class Forked(
        val token: ProviderDescriptorRetentionToken,
        val liveReferenceCount: Int,
    ) : ProviderDescriptorForkResult

    data object InvalidSource : ProviderDescriptorForkResult
}

internal sealed interface ProviderDescriptorRecordResult {
    data class Recorded(
        val firstAcceptedDescriptor: Boolean,
    ) : ProviderDescriptorRecordResult

    data class DescriptorViolation(
        val expected: ProviderDescriptorSnapshot,
        val observed: ProviderDescriptorSnapshot,
        val firstViolation: Boolean,
    ) : ProviderDescriptorRecordResult

    data class InvalidDescriptor(
        val observed: ProviderDescriptorSnapshot,
    ) : ProviderDescriptorRecordResult

    data object InvalidToken : ProviderDescriptorRecordResult

    data object AlreadyRecorded : ProviderDescriptorRecordResult
}

internal data class LiveProviderDescriptorSnapshot(
    val descriptor: ProviderDescriptorSnapshot?,
    val violated: Boolean,
    val retentionCounts: Map<ProviderDescriptorRetentionRole, Int>,
) {
    val liveReferenceCount: Int
        get() = retentionCounts.values.sum()
}

/**
 * The sole strong provider-to-descriptor store. All mutations are serialized so a successful
 * fork, rekey, or handoff is externally indivisible and can never expose a retention gap.
 */
internal class LiveProviderDescriptorLedger {
    private val lock = Any()
    private val entries = IdentityHashMap<Any, Entry>()
    private val retentions = IdentityHashMap<ProviderDescriptorRetentionToken, Retention>()

    internal val retainedDescriptorCount: Int
        get() = synchronized(lock) { entries.size }

    internal fun reserve(
        providerIdentity: Any,
        role: ProviderDescriptorRetentionRole,
    ): ProviderDescriptorReserveResult = synchronized(lock) {
        val existing = entries[providerIdentity]
        if (existing != null) {
            return@synchronized reserved(providerIdentity, existing, role, firstAdmission = false)
        }
        if (
            entries.size >=
            ScreenCaptureEnginePolicyDefaults.MAX_RETAINED_PROVIDER_DESCRIPTOR_SNAPSHOTS
        ) {
            return@synchronized ProviderDescriptorReserveResult.CapacityExceeded
        }

        val entry = Entry()
        entries[providerIdentity] = entry
        reserved(providerIdentity, entry, role, firstAdmission = true)
    }

    internal fun fork(
        source: ProviderDescriptorRetentionToken,
        role: ProviderDescriptorRetentionRole,
    ): ProviderDescriptorForkResult = synchronized(lock) {
        val sourceRetention = retentions[source]
            ?: return@synchronized ProviderDescriptorForkResult.InvalidSource
        val entry = entry(sourceRetention)
        val token = retain(sourceRetention.providerIdentity, entry, role)
        ProviderDescriptorForkResult.Forked(token, entry.liveReferenceCount)
    }

    internal fun recordDescriptor(
        token: ProviderDescriptorRetentionToken,
        descriptor: ProviderDescriptorSnapshot,
    ): ProviderDescriptorRecordResult = synchronized(lock) {
        val retention = retentions[token]
            ?: return@synchronized ProviderDescriptorRecordResult.InvalidToken
        if (retention.descriptorRecorded) {
            return@synchronized ProviderDescriptorRecordResult.AlreadyRecorded
        }
        retention.descriptorRecorded = true
        val entry = entry(retention)
        val expected = entry.descriptor

        if (expected != null) {
            if (entry.violated || expected != descriptor) {
                val firstViolation = !entry.violated
                entry.violated = true
                return@synchronized ProviderDescriptorRecordResult.DescriptorViolation(
                    expected = expected,
                    observed = descriptor,
                    firstViolation = firstViolation,
                )
            }
            return@synchronized if (entry.firstDescriptorAccepted) {
                ProviderDescriptorRecordResult.Recorded(firstAcceptedDescriptor = false)
            } else {
                ProviderDescriptorRecordResult.InvalidDescriptor(descriptor)
            }
        }

        entry.descriptor = descriptor
        if (!DescriptorSyntax.isValid(descriptor)) {
            return@synchronized ProviderDescriptorRecordResult.InvalidDescriptor(descriptor)
        }
        entry.firstDescriptorAccepted = true
        ProviderDescriptorRecordResult.Recorded(firstAcceptedDescriptor = true)
    }

    internal fun rekey(
        token: ProviderDescriptorRetentionToken,
        role: ProviderDescriptorRetentionRole,
    ): Boolean = synchronized(lock) {
        val retention = retentions[token] ?: return@synchronized false
        if (retention.role != role) {
            entry(retention).replace(retention.role, role)
            retention.role = role
        }
        true
    }

    /**
     * Installs [successor] in [successorRole] before releasing [predecessor]. Both must be live
     * capabilities of this ledger. The same token degenerates to a rekey.
     */
    internal fun handoff(
        predecessor: ProviderDescriptorRetentionToken,
        successor: ProviderDescriptorRetentionToken,
        successorRole: ProviderDescriptorRetentionRole,
    ): Boolean = synchronized(lock) {
        val predecessorRetention = retentions[predecessor] ?: return@synchronized false
        val successorRetention = retentions[successor] ?: return@synchronized false
        if (predecessor === successor) {
            if (successorRetention.role != successorRole) {
                entry(successorRetention).replace(successorRetention.role, successorRole)
                successorRetention.role = successorRole
            }
            return@synchronized true
        }

        if (successorRetention.role != successorRole) {
            entry(successorRetention).replace(successorRetention.role, successorRole)
            successorRetention.role = successorRole
        }
        release(predecessor, predecessorRetention)
        true
    }

    internal fun release(token: ProviderDescriptorRetentionToken): Boolean = synchronized(lock) {
        val retention = retentions[token] ?: return@synchronized false
        release(token, retention)
        true
    }

    internal fun snapshot(providerIdentity: Any): LiveProviderDescriptorSnapshot? = synchronized(lock) {
        entries[providerIdentity]?.snapshot()
    }

    private fun reserved(
        providerIdentity: Any,
        entry: Entry,
        role: ProviderDescriptorRetentionRole,
        firstAdmission: Boolean,
    ): ProviderDescriptorReserveResult.Reserved {
        val token = retain(providerIdentity, entry, role)
        return ProviderDescriptorReserveResult.Reserved(
            token = token,
            firstAdmission = firstAdmission,
            liveReferenceCount = entry.liveReferenceCount,
        )
    }

    private fun retain(
        providerIdentity: Any,
        entry: Entry,
        role: ProviderDescriptorRetentionRole,
    ): ProviderDescriptorRetentionToken {
        val token = LedgerRetentionToken()
        entry.increment(role)
        retentions[token] = Retention(providerIdentity, role)
        return token
    }

    private fun entry(retention: Retention): Entry =
        entries[retention.providerIdentity] ?: error("Retention references a missing descriptor")

    private fun release(
        token: ProviderDescriptorRetentionToken,
        retention: Retention,
    ) {
        check(retentions.remove(token) === retention)
        val entry = entry(retention)
        entry.decrement(retention.role)
        if (entry.liveReferenceCount == 0) {
            check(entries.remove(retention.providerIdentity) === entry)
        }
    }

    private class Entry {
        private val retentionCounts = EnumMap<ProviderDescriptorRetentionRole, Int>(
            ProviderDescriptorRetentionRole::class.java,
        )

        var violated: Boolean = false
        var descriptor: ProviderDescriptorSnapshot? = null
        var firstDescriptorAccepted: Boolean = false

        val liveReferenceCount: Int
            get() = retentionCounts.values.sum()

        fun increment(role: ProviderDescriptorRetentionRole) {
            retentionCounts[role] = (retentionCounts[role] ?: 0) + 1
        }

        fun decrement(role: ProviderDescriptorRetentionRole) {
            val count = retentionCounts[role] ?: error("Missing descriptor retention role")
            if (count == 1) retentionCounts.remove(role) else retentionCounts[role] = count - 1
        }

        fun replace(from: ProviderDescriptorRetentionRole, to: ProviderDescriptorRetentionRole) {
            decrement(from)
            increment(to)
        }

        fun snapshot(): LiveProviderDescriptorSnapshot = LiveProviderDescriptorSnapshot(
            descriptor = descriptor,
            violated = violated,
            retentionCounts = retentionCounts.toMap(),
        )
    }

    private class Retention(
        val providerIdentity: Any,
        var role: ProviderDescriptorRetentionRole,
    ) {
        var descriptorRecorded: Boolean = false
    }
}
