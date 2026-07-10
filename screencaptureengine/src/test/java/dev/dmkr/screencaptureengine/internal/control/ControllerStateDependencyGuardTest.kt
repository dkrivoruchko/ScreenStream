package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerStateDependencyGuardTest {
    @Test
    fun stateRemainsOneImmutableProviderFreeLeaf() {
        val text = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerState.kt"),
        )

        forbidden.forEach { token ->
            val present = if (token == "ControllerEffect") {
                Regex("\\bControllerEffect\\b").containsMatchIn(text)
            } else {
                text.contains(token)
            }
            assertFalse("Forbidden state dependency: $token", present)
        }
        required.forEach { token -> assertTrue("Missing state contract: $token", text.contains(token)) }
        assertFalse(text.contains("data class ControllerState"))
        assertFalse(text.contains("internal constructor(\n    val controllerIdentity"))
    }

    @Test
    fun noOtherProductionFileUsesTheDormantStateLeaf() {
        val production = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val state = production.resolve("internal/control/ControllerState.kt")
        val names = declaredNames(Files.readString(state))
        val violations = Files.walk(production).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && it != state }
                .filter { path ->
                    val text = Files.readString(path)
                    names.any { name -> Regex("\\b$name\\b").containsMatchIn(text) }
                }
                .toList()
        }

        assertTrue("Reverse state wiring: $violations", violations.isEmpty())
        assertEquals(
            listOf(
                "ControllerLifecycle",
                "ControllerPauseState",
                "ControllerRunningOutput",
                "ControllerState",
                "ControllerTerminalOutcome",
            ),
            names,
        )
    }

    private fun declaredNames(text: String): List<String> = Regex(
        "(?m)^internal (?:sealed interface|class) ([A-Za-z][A-Za-z0-9_]*)",
    ).findAll(text).map { it.groupValues[1] }.toList().sorted()

    private companion object {
        val required = listOf(
            "data object PreActive",
            "data object RetiredBeforePublic",
            "class Running",
            "class Terminal",
            "class Active",
            "class Suspended",
            "val revisions: CommittedRevisions",
            "val geometry: ControllerGeometryAccumulator",
            "val pause: ControllerPauseState",
            "val arbiter: ReconfigurationArbiter",
            "val parameterTransaction: ParameterTransaction?",
            "val publicPrincipal",
        )
        val forbidden = listOf(
            "ImageEncoderProvider",
            "ControllerProviderReference",
            "ControllerDesiredSnapshot",
            "ControllerCandidateSnapshot",
            "LiveProviderDescriptorLedger",
            "ProviderLedgerRoleToken",
            "ProviderDescriptorRetention",
            "ControllerSnapshotStore",
            "ControllerCandidateOwnership",
            "ControllerCleanupOwnership",
            "ControllerPreparationQuarantineOwnership",
            "CandidateOwnershipAdmission",
            "TerminalOwnershipDisposition",
            "ControllerSnapshotOwnership",
            "android.",
            "kotlinx.coroutines",
            "Flow",
            "Facility",
            "ControllerReducer",
            "ControllerDecision",
            "SessionController",
            "ControllerEffect",
            "Publication",
            "ByteArray",
            "ByteBuffer",
            "synchronized",
            "ReentrantLock",
            "java.lang.reflect",
            "var ",
        )
    }
}
