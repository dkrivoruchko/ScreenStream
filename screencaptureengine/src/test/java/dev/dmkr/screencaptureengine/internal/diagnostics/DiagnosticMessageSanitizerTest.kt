package dev.dmkr.screencaptureengine.internal.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticMessageSanitizerTest {
    @Test
    fun replacesControlsFormatsAndLineAndParagraphSeparators() {
        val candidate = "a\u0000b\tc\nd\re\u0085f\u200Eg\u2028h\u2029i"

        assertEquals(
            "a b c d e f g h i",
            sanitizeDiagnosticMessage(candidate, maxCodePoints = 100),
        )
    }

    @Test
    fun everySpecifiedUnicodeReplacementCategoryBecomesSpace() {
        for (codePoint in Character.MIN_CODE_POINT..Character.MAX_CODE_POINT) {
            if (!Character.isValidCodePoint(codePoint) || codePoint in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) {
                continue
            }
            val replacedByContract = Character.isISOControl(codePoint) ||
                    when (Character.getType(codePoint)) {
                        Character.FORMAT.toInt(),
                        Character.LINE_SEPARATOR.toInt(),
                        Character.PARAGRAPH_SEPARATOR.toInt(),
                        -> true

                        else -> false
                    }
            if (!replacedByContract) continue

            val candidate = buildString {
                append('a')
                appendCodePoint(codePoint)
                append('b')
            }
            assertEquals("Code point U+${codePoint.toString(16)}", "a b", sanitizeDiagnosticMessage(candidate, 3))
        }
    }

    @Test
    fun collapsesUnicodeWhitespaceAndTrimsBothEnds() {
        val candidate = " \u00A0\u2003alpha\u2002 \t beta\u3000 "

        assertEquals("alpha beta", sanitizeDiagnosticMessage(candidate, maxCodePoints = 100))
    }

    @Test
    fun truncatesByCodePointWithoutSplittingSurrogatePairOrAppendingEllipsis() {
        val sanitized = sanitizeDiagnosticMessage("A\uD83D\uDE00BC", maxCodePoints = 3)

        assertEquals("A\uD83D\uDE00B", sanitized)
        assertEquals(3, sanitized?.codePointCount(0, sanitized.length))
        assertFalse(checkNotNull(sanitized).endsWith("…"))
    }

    @Test
    fun emptyOrWhitespaceOnlyInputAndZeroMaximumBecomeNull() {
        assertNull(sanitizeDiagnosticMessage("", maxCodePoints = 10))
        assertNull(sanitizeDiagnosticMessage("\t\n\u2003", maxCodePoints = 10))
        assertNull(sanitizeDiagnosticMessage("content", maxCodePoints = 0))
    }

    @Test
    fun negativeMaximumIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizeDiagnosticMessage("content", maxCodePoints = -1)
        }
    }

    @Test
    fun truncationFollowsNormalizationWithoutASecondTrimPass() {
        assertEquals("ab ", sanitizeDiagnosticMessage("ab cd", maxCodePoints = 3))
    }
}
