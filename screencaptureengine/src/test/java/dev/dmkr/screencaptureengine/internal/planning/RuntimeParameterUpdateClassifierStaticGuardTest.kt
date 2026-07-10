package dev.dmkr.screencaptureengine.internal.planning

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class RuntimeParameterUpdateClassifierStaticGuardTest {
    @Test
    fun classifierRemainsPurePlanningCode() {
        val source = Files.readString(
            Paths.get(
                "src/main/java/dev/dmkr/screencaptureengine/internal/planning/RuntimeParameterUpdateClassifier.kt",
            ),
        )
        val forbiddenSnippets = listOf(
            "VirtualDisplay",
            "setDefaultBufferSize",
            ".setSurface",
            ".surface =",
            "resize(",
            "createTarget",
            "bindTarget",
            "createVirtualDisplay",
            "updateTexImage",
            "glReadPixels",
            "GLES",
            "createEncoder(",
            "kotlin.reflect",
            "java.lang.reflect",
        )

        forbiddenSnippets.forEach { snippet ->
            assertFalse("$snippet must not appear in runtime update classification.", source.contains(snippet))
        }
    }
}
