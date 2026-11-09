package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NativeHealthCellStateTest {
    // Verification: ENC-02
    @Test
    fun enabledHealthDisablesExactlyOnceAndNeverRecovers() {
        val health = NativeHealthCell(NativeHealthCell.State.Enabled)

        assertTrue(health.disable())
        assertSame(NativeHealthCell.State.Disabled, health.state)
        assertFalse(health.disable())
        assertSame(NativeHealthCell.State.Disabled, health.state)
    }
}
