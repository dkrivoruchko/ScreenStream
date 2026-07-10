package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDirectFactDependencyGuardTest {
    @Test
    fun directFactLeafIsControllerLocalAndPolicyNeutral() {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control")
        val direct = Files.readString(root.resolve("ControllerDirectFact.kt"))
        val ingress = Files.readString(root.resolve("ControllerIngress.kt"))
        val evidence = Files.readString(root.resolve("ControllerEvidence.kt"))
        val parameter = Files.readString(root.resolve("ParameterTransaction.kt"))

        forbidden.forEach { token -> assertFalse("Forbidden direct-fact dependency: $token", direct.contains(token)) }
        listOf("ControllerDirectFact", "ControllerFactOrigin", "ParameterTransactionFact", "ReconfigurationReasonSpec").forEach {
            assertFalse("Direct facts must remain outside generic ingress slots: $it", ingress.contains(it))
        }
        assertFalse("Generic terminal exhaustion loses the failure domain", evidence.contains("\n    RecoveryExhausted"))
        listOf(
            "MetricsCollectionTerminated",
            "UnsafePlatformBinding",
            "UnsafeRenderingState",
            "UnsafeGlOutOfMemory",
            "UnsafeProviderRetainedOwnership",
            "InternalControllerInvariant",
            "PlatformRecoveryExhausted",
            "RenderingRecoveryExhausted",
            "EncodingRecoveryExhausted",
            "ResourceRecoveryExhausted",
        ).forEach { assertTrue("Missing closed terminal source: $it", evidence.contains(it)) }
        assertTrue(direct.contains("val fact: ParameterTransactionFact.Direct"))
        assertTrue(direct.contains("data class NormalizedNoOpReady("))
        assertTrue(direct.contains("class PrePublicRetirement("))
        assertTrue(direct.contains("data class ParameterAdmitted("))
        assertTrue(parameter.contains("fun admit(fact: ControllerDirectFact.ParameterAdmitted)"))
        assertTrue(direct.contains("val call: TransactionIdentity"))
        assertTrue(direct.contains("sealed interface FramePacingResetFact"))
        assertTrue(direct.contains("sealed interface PeriodicRefreshSourceFence"))
        assertTrue(direct.contains("data class RecoveryCandidatePreparationFailed("))
        assertTrue(parameter.contains("val pacingReset: FramePacingResetFact"))
        assertTrue(parameter.contains("data class CandidatePreparationStartedTimedOut("))
        assertTrue(direct.contains("val origin: ControllerFactOrigin.Recovery"))
        assertTrue(direct.contains("val fence: ArbiterFenceEvidence"))
        assertTrue(direct.contains("enum class ArbiterFenceEvidence { None, OutputFreshness }"))
        listOf(
            "InitialActiveCommitted",
            "CompleteOwnerCommitted",
            "FrameRateCommitted",
            "OwnerCommitted",
            "SuspendedCommitted",
            "RestorationCommitted",
        ).forEach { assertFalse("External fact claims a reducer-owned commit: $it", direct.contains(it) || parameter.contains(it)) }
        assertFalse(direct.contains("else -> null"))
        val completeOwner = direct.substringAfter("data class CompleteOwnerReady(")
            .substringBefore(") : ControllerDirectFact")
        assertTrue(completeOwner.contains("val origin: ControllerFactOrigin.Reconfiguration"))
        assertFalse(completeOwner.contains("ControllerFactOrigin.Startup"))
        assertFalse(completeOwner.contains("ControllerFactOrigin.Recovery"))
        assertTrue(
            Regex("data class Cancelled\\([^)]*\\) : ParameterTransactionFact").containsMatchIn(parameter),
        )
        assertFalse(
            Regex("data class Cancelled\\([^)]*\\) : Direct").containsMatchIn(parameter),
        )
    }

    @Test
    fun oldProductionCannotWireDirectFactsInReverse() {
        val production = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val directFile = production.resolve("internal/control/ControllerDirectFact.kt")
        val evidenceFile = production.resolve("internal/control/ControllerEvidence.kt")
        val identityFile = production.resolve("internal/control/ControllerIdentity.kt")
        val ingressFile = production.resolve("internal/control/ControllerIngress.kt")
        val preparedTurnFile = production.resolve("internal/control/ControllerPreparedTurn.kt")
        val parameterFile = production.resolve("internal/control/ParameterTransaction.kt")
        val snapshotStoreFile = production.resolve("internal/control/ControllerSnapshotStore.kt")
        val arbiterFile = production.resolve("internal/control/ReconfigurationArbiter.kt")
        val exactConsumers = setOf(
            directFile,
            evidenceFile,
            identityFile,
            ingressFile,
            preparedTurnFile,
            parameterFile,
            snapshotStoreFile,
            arbiterFile,
        )
        val violations = Files.walk(production).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { it !in exactConsumers }
                .filter { path -> reverseNames.any { Regex("\\b$it\\b").containsMatchIn(Files.readString(path)) } }
                .toList()
        }

        assertTrue("Reverse direct-fact wiring: $violations", violations.isEmpty())
    }

    private companion object {
        val forbidden = listOf(
            "android.",
            "kotlinx.coroutines",
            "ScreenCaptureProblem",
            "ScreenCaptureSessionState",
            "ControllerReducer",
            "ControllerState",
            "SessionController",
            "Facility",
            "ByteArray",
            "ByteBuffer",
            "internal.platform",
            "internal.rendering",
            "internal.runtime",
        )
        val reverseNames = listOf(
            "ControllerTurnFact",
            "ControllerFactOrigin",
            "ControllerDirectFact",
            "ControllerTurnFactMerge",
            "ArbiterFenceEvidence",
        )
    }
}
