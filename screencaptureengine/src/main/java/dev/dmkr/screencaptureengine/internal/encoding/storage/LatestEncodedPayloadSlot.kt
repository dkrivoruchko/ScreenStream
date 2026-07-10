@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.encoding.storage

@JvmInline
internal value class EncodedPayloadIdentity(
    internal val value: Long,
) {
    init {
        require(value > 0L) { "Encoded payload identity must be positive." }
    }
}

internal sealed interface LatestPayloadReplacement {
    data object Replaced : LatestPayloadReplacement

    data class IdentityMismatch(
        val currentIdentity: EncodedPayloadIdentity?,
    ) : LatestPayloadReplacement
}

/** Identity-matched ownership slot for one replaceable immutable encoded payload. */
internal class LatestEncodedPayloadSlot {
    private val lock = Any()
    private var current: Entry? = null

    internal fun replace(
        expectedIdentity: EncodedPayloadIdentity?,
        replacementIdentity: EncodedPayloadIdentity,
        replacement: ImmutableEncodedPayload,
    ): LatestPayloadReplacement {
        val retired = synchronized(lock) {
            val existing = current
            if (existing?.identity != expectedIdentity) {
                return LatestPayloadReplacement.IdentityMismatch(existing?.identity)
            }
            require(replacementIdentity != expectedIdentity) {
                "Replacement payload identity must differ from the expected identity."
            }

            val replacementReference = replacement.moveToSlot()
            current = Entry(replacementIdentity, replacementReference)
            existing
        }
        retired?.reference?.release()
        return LatestPayloadReplacement.Replaced
    }

    internal fun acquire(identity: EncodedPayloadIdentity): ImmutableEncodedPayloadLease? =
        synchronized(lock) {
            val existing = current?.takeIf { it.identity == identity } ?: return@synchronized null
            existing.reference.acquireLease()
        }

    internal fun retire(identity: EncodedPayloadIdentity): Boolean {
        val retired = synchronized(lock) {
            val existing = current?.takeIf { it.identity == identity } ?: return false
            current = null
            existing
        }
        retired.reference.release()
        return true
    }

    private data class Entry(
        val identity: EncodedPayloadIdentity,
        val reference: EncodedPayloadReference,
    )
}
