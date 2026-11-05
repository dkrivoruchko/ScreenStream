package io.screenstream.mjpeg.ui

import io.screenstream.mjpeg.ui.settings.acceptsSettingNumberInput
import io.screenstream.mjpeg.ui.settings.parseSettingDecimal
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

internal class SettingNumberInputTest {
    @Test
    fun integerParsingConsumesTheWholeInputWithoutTruncationOrOverflow() {
        val format = numberFormat(Locale.US)
        for ((text, expected) in listOf(
            "2147483647" to Int.MAX_VALUE,
            "2147483648" to null,
            "1.5" to null,
            "12suffix" to null,
            "" to null,
        )) {
            assertEquals("Integer input: $text", expected, parseSettingInteger(text, format))
        }
        assertTrue(acceptsSettingNumberInput("2147483647", format))
        assertFalse(acceptsSettingNumberInput("2147483648", format))
        assertFalse(acceptsSettingNumberInput("12suffix", format))
    }

    @Test
    fun localizedDecimalDraftsAndExactMaximumRemainDistinctFromRoundedValues() {
        val format = numberFormat(Locale.GERMANY)
        for ((text, accepted) in listOf(
            "" to true,
            "," to true,
            "1,25" to true,
            "1.25" to false,
            "1,,25" to false,
            "12suffix" to false,
            "3600" to true,
            "3600,0000000000000001" to false,
        )) {
            assertEquals(
                "Decimal draft: $text",
                accepted,
                acceptsSettingNumberInput(text, format, allowDecimal = true, maximumValue = 3600),
            )
        }
        assertNull(parseSettingDecimal("", format))
        assertNull(parseSettingDecimal(",", format))
        assertNull(parseSettingDecimal("12suffix", format))
        assertEquals(1.25, checkNotNull(parseSettingDecimal("1,25", format)), 0.0)
        // The input guard must compare the exact decimal even when Double parsing rounds to the maximum.
        assertEquals(3600.0, checkNotNull(parseSettingDecimal("3600,0000000000000001", format)), 0.0)
    }

    private fun numberFormat(locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        (this as DecimalFormat).isParseBigDecimal = true
    }
}
