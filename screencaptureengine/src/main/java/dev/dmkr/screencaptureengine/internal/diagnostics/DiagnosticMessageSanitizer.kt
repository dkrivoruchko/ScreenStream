@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.diagnostics

internal fun sanitizeDiagnosticMessage(
    candidate: String,
    maxCodePoints: Int,
): String? {
    require(maxCodePoints >= 0) { "Maximum diagnostic message length must be non-negative." }

    val normalized = StringBuilder(candidate.length)
    var index = 0
    var previousWasWhitespace = true
    while (index < candidate.length) {
        val sourceCodePoint = candidate.codePointAt(index)
        index += Character.charCount(sourceCodePoint)

        val codePoint = if (sourceCodePoint.requiresReplacement()) ' '.code else sourceCodePoint
        if (codePoint.isUnicodeWhitespace()) {
            if (!previousWasWhitespace) {
                normalized.append(' ')
                previousWasWhitespace = true
            }
        } else {
            normalized.appendCodePoint(codePoint)
            previousWasWhitespace = false
        }
    }

    if (normalized.isNotEmpty() && normalized.last() == ' ') {
        normalized.setLength(normalized.length - 1)
    }
    if (normalized.isEmpty() || maxCodePoints == 0) return null

    val normalizedCodePointCount = normalized.codePointCount(0, normalized.length)
    if (normalizedCodePointCount <= maxCodePoints) return normalized.toString()

    val truncationIndex = normalized.offsetByCodePoints(0, maxCodePoints)
    return normalized.substring(0, truncationIndex)
}

private fun Int.requiresReplacement(): Boolean =
    this == '\r'.code ||
            this == '\n'.code ||
            this == '\t'.code ||
            Character.isISOControl(this) ||
            when (Character.getType(this)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> true

                else -> false
            }

private fun Int.isUnicodeWhitespace(): Boolean =
    Character.isWhitespace(this) || Character.isSpaceChar(this)
