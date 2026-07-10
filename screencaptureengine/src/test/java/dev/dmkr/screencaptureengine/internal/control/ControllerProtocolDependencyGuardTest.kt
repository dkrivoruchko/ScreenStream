package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProtocolDependencyGuardTest {
    @Test
    fun exactFoundationIsNeutralDormantAndHasNoAuthorityFacade() {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control")
        val actual = Files.list(root).use { paths ->
            paths.map { it.fileName.toString() }.filter(foundationFiles::contains).toList().toSet()
        }
        assertEquals(foundationFiles, actual)
        val text = foundationFiles.joinToString("\n") { Files.readString(root.resolve(it)) }
        forbidden.forEach { token -> assertFalse("Forbidden token: $token", text.contains(token)) }
        listOf("SessionController", "ControllerReducer", "ControllerEffect", "ControllerPublication").forEach {
            assertFalse("Deferred authority leaked into foundation: $it", text.contains(it))
            assertFalse("Deferred authority file exists: $it", Files.exists(root.resolve("$it.kt")))
        }
        assertTrue(Files.exists(root.resolve("ControllerState.kt")))
        assertTrue(text.contains("ControllerIngressStore"))
        val ingressText = Files.readString(root.resolve("ControllerIngress.kt"))
        listOf("ParameterTransactionFact", "ReconfigurationReasonSpec", "ReasonToken").forEach { token ->
            assertFalse(
                "Ordered controller facts/policy must not enter the generic latest-value ingress store: $token",
                ingressText.contains(token),
            )
        }
    }

    @Test
    fun oldProductionCannotWireTheDormantProtocolInReverse() {
        val productionRoot = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val protocolRoot = productionRoot.resolve("internal/control")
        val exactMechanicalConsumers = mechanicalProtocolConsumers.mapKeys { (path, _) ->
            productionRoot.resolve(path)
        }
        exactMechanicalConsumers.forEach { (path, expectedNames) ->
            val source = Files.readString(path)
            val actualNames = reverseNames.filterTo(mutableSetOf()) { name ->
                Regex("\\b$name\\b").containsMatchIn(source)
            }
            assertTrue("Mechanical protocol consumer inventory changed in $path: $actualNames", actualNames == expectedNames)
        }
        val violations = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter {
                    it !in exactMechanicalConsumers && (it.parent != protocolRoot ||
                            it.fileName.toString() !in
                            foundationFiles + snapshotBoundaryFiles + protocolExtensionFiles + stateExtensionFiles)
                }
                .filter { path -> reverseNames.any { name -> Regex("\\b$name\\b").containsMatchIn(Files.readString(path)) } }
                .toList()
        }
        assertTrue("Reverse protocol wiring: $violations", violations.isEmpty())
    }

    private fun assertEquals(expected: Any, actual: Any) = org.junit.Assert.assertEquals(expected, actual)

    private companion object {
        val foundationFiles = setOf(
            "ControllerIdentity.kt",
            "ControllerEvidence.kt",
            "ControllerIngress.kt",
            "ReconfigurationArbiter.kt",
            "ParameterTransaction.kt",
        )
        val snapshotBoundaryFiles = setOf(
            "ControllerSnapshot.kt",
            "ControllerSnapshotStore.kt",
            "ControllerTargetSnapshot.kt",
        )
        val protocolExtensionFiles = setOf(
            "ControllerDirectFact.kt",
            "ControllerGeometryAccumulator.kt",
            "ControllerPreparedTurn.kt",
        )
        val stateExtensionFiles = setOf("ControllerState.kt")
        val mechanicalProtocolConsumers = mapOf(
            "internal/platform/metrics/SessionMetricsCollector.kt" to setOf(
                "ControllerCancellationMarkerRevision",
            ),
            "internal/platform/metrics/SessionMetricsFact.kt" to setOf(
                "SessionIdentity",
                "ControllerOperationIdentity",
                "ControllerMetricsAttachmentIdentity",
                "ControllerCancellationMarkerRevision",
            ),
        )
        val forbidden = listOf(
            "android.", "kotlinx.coroutines", "StateFlow", "SharedFlow", "ByteArray", "ByteBuffer",
            "internal.lifecycle", "internal.startup", "internal.session", "internal.runtime", "java.lang.reflect",
            "internal.diagnostics", "internal.encoding", "internal.gl", "internal.operation", "internal.planning",
            "internal.platform", "internal.policy", "internal.rendering", "internal.target", "Facility",
            "ScreenCaptureProblem", "ScreenCaptureSessionState", "CaptureMetrics", "EncodedImage",
        )
        val reverseNames = listOf(
            "ControllerIdentity",
            "SessionIdentity",
            "TransactionIdentity",
            "ReconfigurationIdentity",
            "CandidateIdentity",
            "CompleteOwnerIdentity",
            "TargetIdentity",
            "IngressSequence",
            "ReasonToken",
            "DesiredSnapshotIdentity",
            "EffectiveSnapshotIdentity",
            "ControllerOperationIdentity",
            "ControllerResourceBagIdentity",
            "ControllerProductionAttemptIdentity",
            "ControllerCallbackAttachmentIdentity",
            "ControllerMetricsAttachmentIdentity",
            "ControllerInvariantIdentity",
            "ControllerCancellationMarkerRevision",
            "ControllerOccurrenceIdentity",
            "GeometrySnapshot",
            "TerminalEvidence",
            "MetricsEvidence",
            "CapturedResizeEvidence",
            "SourceTrustEvidence",
            "PauseEvidence",
            "ControllerIngressStore",
            "ControllerIngressPayload",
            "ControllerIngressOffer",
            "ControllerIngress",
            "ControllerWakeIntent",
            "ReconfigurationArbiter",
            "ReconfigurationReason",
            "ReconfigurationReasonKind",
            "ReconfigurationStage",
            "ReconfigurationReasonKey",
            "ReconfigurationReasonSpec",
            "PendingReconfiguration",
            "PreparedParameterCandidateClass",
            "ParameterPreparationRejectionEvidence",
            "ParameterTransactionStage",
            "ParameterTransactionFact",
            "ParameterTransition",
            "ParameterTransaction",
            "ControllerPreparedTurn",
            "PrePublicRetirement",
            "ControllerPreparedFact",
            "PreparedSourceFact",
            "PreparedTerminalFact",
            "PreparedPrePublicFact",
            "PreparedCancellationFact",
            "PreparedVisibilityFact",
            "PreparedParameterAdmissionFact",
            "PreparedParameterProgressFact",
            "PreparedArbiterReasonAddedFact",
            "PreparedArbiterReasonClearedFact",
            "NormalizedNoOpReady",
            "PrevalidatedFrameRateCommit",
            "PrevalidatedInitialActive",
            "PrevalidatedCandidatePrepared",
            "PrevalidatedCandidatePreparationFailed",
            "PrevalidatedParameterTargetAcknowledgement",
            "PrevalidatedOwnerCommit",
            "PrevalidatedRecoveryOwnerCommit",
            "PrevalidatedRecoveryTargetAcknowledgement",
            "PrevalidatedCandidatePreparationStartedTimeout",
            "PrevalidatedRecoveryCandidatePreparationFailed",
            "PrePublicStartRetirement",
            "PrePublicProjectionFreshness",
            "PrePublicStartOutcome",
            "PrePublicStartFailureEvidence",
            "ControllerReconfigurationStartIdentity",
            "ControllerTerminalCause",
            "ControllerTerminalFence",
            "ControllerGlTaskIdentity",
            "ControllerPlatformOperationIdentity",
            "ControllerProviderOwnershipIdentity",
            "ControllerTurnOccurrenceReservations",
            "FramePacingResetFact",
            "PeriodicRefreshSourceFence",
            "RecoveryCandidatePreparationFailureEvidence",
            "ReturnedCandidatePreparationOwnership",
            "ControllerCandidatePrevalidation",
            "ControllerCandidateCommitPrevalidation",
            "ControllerCurrentCandidatePrevalidation",
            "ControllerCandidateDispositionPrevalidation",
            "ControllerTerminalCandidateDispositionPrevalidation",
            "CandidateDispositionTrigger",
            "CandidateDispositionAction",
            "CandidateDispositionOutcome",
            "ControllerTargetAcknowledgementPrevalidation",
            "TargetAcknowledgementDisposition",
        )
    }
}
