package dev.dmkr.screencaptureengine.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorSyntaxTest {
    @Test
    fun providerIdAcceptsExactAsciiGrammarAndLengthBoundaries() {
        assertTrue(DescriptorSyntax.isValidProviderId("0"))
        assertTrue(DescriptorSyntax.isValidProviderId("A" + "._-a0".repeat(25) + "ab"))
        assertEquals(128, ("A" + "._-a0".repeat(25) + "ab").length)

        assertFalse(DescriptorSyntax.isValidProviderId(""))
        assertFalse(DescriptorSyntax.isValidProviderId("."))
        assertFalse(DescriptorSyntax.isValidProviderId("a".repeat(129)))
        listOf('/', ' ', '\t', '\n', '\u007f', '\u0080').forEach { invalid ->
            assertFalse(
                "Unexpected provider-id character ${invalid.code}",
                DescriptorSyntax.isValidProviderId("a$invalid"),
            )
        }
    }

    @Test
    fun providerIdCharacterClassesAreExhaustiveAcrossAscii() {
        (0..127).forEach { code ->
            val character = code.toChar()
            val expectedAsFirst = character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9'
            val expectedAsLater = expectedAsFirst || character == '.' || character == '_' || character == '-'

            assertEquals(
                "first provider-id char $code",
                expectedAsFirst,
                DescriptorSyntax.isValidProviderId("$character"),
            )
            assertEquals(
                "later provider-id char $code",
                expectedAsLater,
                DescriptorSyntax.isValidProviderId("a$character"),
            )
        }
    }

    @Test
    fun formatNameAcceptsPrintableAsciiWithoutEdgeSpaces() {
        assertTrue(DescriptorSyntax.isValidFormatName("!"))
        assertTrue(DescriptorSyntax.isValidFormatName("JPEG image ~ 1"))
        assertTrue(DescriptorSyntax.isValidFormatName("x".repeat(64)))

        assertFalse(DescriptorSyntax.isValidFormatName(""))
        assertFalse(DescriptorSyntax.isValidFormatName(" "))
        assertFalse(DescriptorSyntax.isValidFormatName(" leading"))
        assertFalse(DescriptorSyntax.isValidFormatName("trailing "))
        assertFalse(DescriptorSyntax.isValidFormatName("x".repeat(65)))
        listOf('\u001f', '\t', '\n', '\u007f', '\u0080').forEach { invalid ->
            assertFalse(DescriptorSyntax.isValidFormatName("a${invalid}b"))
        }
    }

    @Test
    fun formatNameCharacterClassIsExhaustiveAcrossAscii() {
        (0..127).forEach { code ->
            val character = code.toChar()
            val expected = character in '\u0020'..'\u007e'
            val value = if (character == ' ') "a b" else "a${character}b"

            assertEquals("format-name char $code", expected, DescriptorSyntax.isValidFormatName(value))
        }
    }

    @Test
    fun mimeAcceptsExactRestrictedLowercaseGrammarAndBoundaries() {
        val allowedPunctuation = "!#$&-^_.+"
        assertTrue(DescriptorSyntax.isValidMimeType("a/0"))
        assertTrue(DescriptorSyntax.isValidMimeType("a$allowedPunctuation/z$allowedPunctuation"))
        val maximumMime = "a".repeat(127) + "/" + "9".repeat(127)
        assertEquals(255, maximumMime.length)
        assertTrue(DescriptorSyntax.isValidMimeType(maximumMime))

        listOf(
            "",
            "a",
            "/a",
            "a/",
            "a/b/c",
            "A/b",
            "a/B",
            "a/ b",
            "a/+b",
            "a/b;c=d",
            "a/é",
            "a".repeat(128) + "/b",
            "a/" + "b".repeat(128),
        ).forEach { invalid ->
            assertFalse("Unexpected MIME acceptance: $invalid", DescriptorSyntax.isValidMimeType(invalid))
        }
    }

    @Test
    fun mimeCharacterClassesAreExhaustiveAcrossAscii() {
        val punctuation = "!#$&-^_.+".toSet()
        (0..127).forEach { code ->
            val character = code.toChar()
            val alphaNumeric = character in 'a'..'z' || character in '0'..'9'
            val expectedAsLater = alphaNumeric || character in punctuation

            assertEquals("first MIME char $code", alphaNumeric, DescriptorSyntax.isValidMimeType("$character/a"))
            assertEquals("later MIME char $code", expectedAsLater, DescriptorSyntax.isValidMimeType("a$character/a"))
        }
    }

    @Test
    fun snapshotIsValidatedImmutableAndNeverNormalizesInput() {
        val snapshot = DescriptorSyntax.snapshotOrNull(
            providerId = "Provider_1",
            formatName = "JPEG",
            mimeType = "image/jpeg",
        )

        assertNotNull(snapshot)
        assertEquals("Provider_1", snapshot?.providerId)
        assertEquals(EncodedFormatDescriptorSnapshot.createOrNull("JPEG", "image/jpeg"), snapshot?.outputFormat)
        assertNull(DescriptorSyntax.snapshotOrNull("Provider_1", "JPEG", "IMAGE/JPEG"))
        assertNull(DescriptorSyntax.snapshotOrNull("Provider_1", " JPEG", "image/jpeg"))

        val invalidCopy = DescriptorSyntax.copySnapshot(
            providerId = "Provider_1",
            formatName = "JPEG",
            mimeType = "IMAGE/JPEG",
        )
        assertEquals("IMAGE/JPEG", invalidCopy.outputFormat.mimeType)
        assertFalse(DescriptorSyntax.isValid(invalidCopy))
    }
}
