@file:Suppress("unused") // Intentionally dormant until provider integration.

package dev.dmkr.screencaptureengine.internal.encoding

@ConsistentCopyVisibility
internal data class EncodedFormatDescriptorSnapshot private constructor(
    val name: String,
    val mimeType: String,
) {
    internal companion object {
        internal fun copy(
            name: String,
            mimeType: String,
        ): EncodedFormatDescriptorSnapshot = EncodedFormatDescriptorSnapshot(
            name = name,
            mimeType = mimeType,
        )

        internal fun createOrNull(
            name: String,
            mimeType: String,
        ): EncodedFormatDescriptorSnapshot? = if (
            DescriptorSyntax.isValidFormatName(name) &&
            DescriptorSyntax.isValidMimeType(mimeType)
        ) {
            EncodedFormatDescriptorSnapshot(name = name, mimeType = mimeType)
        } else {
            null
        }
    }
}

@ConsistentCopyVisibility
internal data class ProviderDescriptorSnapshot private constructor(
    val providerId: String,
    val outputFormat: EncodedFormatDescriptorSnapshot,
) {
    internal companion object {
        internal fun copy(
            providerId: String,
            formatName: String,
            mimeType: String,
        ): ProviderDescriptorSnapshot = ProviderDescriptorSnapshot(
            providerId = providerId,
            outputFormat = EncodedFormatDescriptorSnapshot.copy(
                name = formatName,
                mimeType = mimeType,
            ),
        )

        internal fun createOrNull(
            providerId: String,
            formatName: String,
            mimeType: String,
        ): ProviderDescriptorSnapshot? {
            if (!DescriptorSyntax.isValidProviderId(providerId)) return null
            val outputFormat = EncodedFormatDescriptorSnapshot.createOrNull(
                name = formatName,
                mimeType = mimeType,
            ) ?: return null
            return ProviderDescriptorSnapshot(
                providerId = providerId,
                outputFormat = outputFormat,
            )
        }
    }
}

internal object DescriptorSyntax {
    internal fun copySnapshot(
        providerId: String,
        formatName: String,
        mimeType: String,
    ): ProviderDescriptorSnapshot = ProviderDescriptorSnapshot.copy(
        providerId = providerId,
        formatName = formatName,
        mimeType = mimeType,
    )

    internal fun snapshotOrNull(
        providerId: String,
        formatName: String,
        mimeType: String,
    ): ProviderDescriptorSnapshot? = ProviderDescriptorSnapshot.createOrNull(
        providerId = providerId,
        formatName = formatName,
        mimeType = mimeType,
    )

    internal fun isValid(snapshot: ProviderDescriptorSnapshot): Boolean =
        isValidProviderId(snapshot.providerId) &&
            isValidFormatName(snapshot.outputFormat.name) &&
            isValidMimeType(snapshot.outputFormat.mimeType)

    internal fun isValidProviderId(value: String): Boolean =
        value.length in PROVIDER_ID_LENGTH &&
            value.first().isAsciiAlphaNumeric() &&
            value.all { it.isAsciiAlphaNumeric() || it == '.' || it == '_' || it == '-' }

    internal fun isValidFormatName(value: String): Boolean =
        value.length in FORMAT_NAME_LENGTH &&
            value.first() != ASCII_SPACE &&
            value.last() != ASCII_SPACE &&
            value.all { it in PRINTABLE_ASCII }

    internal fun isValidMimeType(value: String): Boolean {
        val separator = value.indexOf('/')
        if (separator <= 0 || separator != value.lastIndexOf('/')) return false

        val type = value.substring(0, separator)
        val subtype = value.substring(separator + 1)
        return type.isValidMimeComponent() && subtype.isValidMimeComponent()
    }

    private fun String.isValidMimeComponent(): Boolean =
        length in MIME_COMPONENT_LENGTH &&
            first().isAsciiLowercaseAlphaNumeric() &&
            all { it.isAsciiLowercaseAlphaNumeric() || it in MIME_OTHER_PUNCTUATION }

    private fun Char.isAsciiAlphaNumeric(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isAsciiLowercaseAlphaNumeric(): Boolean =
        this in 'a'..'z' || this in '0'..'9'

    private val PROVIDER_ID_LENGTH = 1..128
    private val FORMAT_NAME_LENGTH = 1..64
    private val MIME_COMPONENT_LENGTH = 1..127
    private val PRINTABLE_ASCII = '\u0020'..'\u007e'
    private val MIME_OTHER_PUNCTUATION = setOf('!', '#', '$', '&', '-', '^', '_', '.', '+')
    private const val ASCII_SPACE = '\u0020'
}
