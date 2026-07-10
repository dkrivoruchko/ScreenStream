package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGeometryAccumulatorDependencyGuardTest {
    @Test
    fun accumulatorRemainsOnePureFlatControllerLeaf() {
        val path = Path.of(
            "src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerGeometryAccumulator.kt",
        )
        val text = Files.readString(path)
        val evidenceText = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerEvidence.kt"),
        )

        forbidden.forEach { token -> assertFalse("Forbidden token: $token", text.contains(token)) }
        required.forEach { token -> assertTrue("Missing closed fact: $token", text.contains(token)) }
        assertFalse(text.contains("import "))
        assertFalse(Regex("SourceTrustEvidence\\s*\\{[^}]*\\bTrusted\\b").containsMatchIn(evidenceText))
    }

    private companion object {
        val required = listOf(
            "Accepted",
            "Awaiting",
            "Duplicate",
            "Untrusted",
            "NotRepresentable",
            "metricsUntrustedEvidence",
            "capturedResizeUntrustedEvidence",
        )
        val forbidden = listOf(
            "android.",
            "CaptureGeometry",
            "CommittedRevision",
            "ControllerState",
            "ControllerReducer",
            "SessionController",
            "StateFlow",
            "SharedFlow",
            "kotlinx.coroutines",
            "synchronized",
            "Lock",
            "Facility",
            "Effect",
            "Publication",
            "internal.platform",
            "internal.planning",
            "internal.target",
            "java.lang.reflect",
        )
    }
}
