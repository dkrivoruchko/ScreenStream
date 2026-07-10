package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerSnapshotDependencyGuardTest {
    @Test
    fun snapshotLeafRemainsPureFlatAndUnwired() {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control")
        val text = files.joinToString("\n") { Files.readString(root.resolve(it)) }

        forbidden.forEach { token -> assertFalse("Forbidden token: $token", text.contains(token)) }
        required.forEach { token -> assertTrue("Missing contract token: $token", text.contains(token)) }
        listOf("ControllerProviderReference", "ControllerDesiredSnapshot", "ControllerCandidateSnapshot").forEach { name ->
            assertFalse("Provider-bearing value must be an ordinary class: $name", text.contains("data class $name"))
        }
        listOf("provider.id", "provider.outputFormat", "requestedProvider.").forEach { token ->
            assertFalse("Provider method/property access escaped preparation: $token", text.contains(token))
        }
        val effective = text.substringAfter("internal class ControllerEffectiveSnapshot")
            .substringBefore("internal sealed interface ControllerParameterClassification")
        assertFalse(effective.contains("ImageEncoderProvider"))
        assertFalse(effective.contains("ControllerProviderReference"))
        val requestClassifier = text.substringAfter("internal fun classifyRequest")
            .substringBefore("internal fun classifyReplacement")
        listOf(
            "NormalizedOutputValues(", "NormalizedOutputValues.copyOf", "ControllerProviderReference(",
            "DesiredSnapshotIdentity(", "CandidateIdentity(", ".id", ".outputFormat",
        ).forEach { token -> assertFalse("No-op classifier allocates or reads descriptors: $token", requestClassifier.contains(token)) }
    }

    @Test
    fun ownershipStoreUsesOnlyExactFixedSlotsAndKeepsCapabilitiesOutOfSnapshots() {
        val store = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerSnapshotStore.kt"),
        )
        listOf(
            "private var provisionalDesired", "private var currentDesired", "private var lastEffective",
            "private var currentOwner", "private var candidate", "private var physicalCurrentTarget",
            "private var firstCleanup", "private var secondCleanup", "private var terminalCleanup",
            "private var preparationQuarantine",
        ).forEach { token -> assertTrue("Missing fixed ownership slot: $token", store.contains(token)) }
        listOf(
            "MutableList", "MutableMap", "HashMap", "ArrayList", "synchronized", "ReentrantLock",
            "Facility", "Flow", "ByteArray", "ByteBuffer", "android.", "internal.gl", "internal.platform",
            "internal.rendering", "internal.target",
        ).forEach { token -> assertFalse("Forbidden store dependency: $token", store.contains(token)) }
        assertFalse(
            "Physical target acknowledgement must require a store-minted ticket.",
            store.contains("acknowledgePhysicalTarget(target: ControllerTargetSnapshot)"),
        )
        listOf(
            "CandidateRollbackDisposition",
            "CandidateQuarantineDisposition",
            "rollbackCandidateBeforeStart",
            "rollbackReturnedCandidate",
            "retainStartedCandidateForLateReturn",
            "quarantineCandidate",
        ).forEach { token -> assertFalse("Legacy raw candidate disposition bypass: $token", store.contains(token)) }

        val immutableView = store.substringAfter("internal data class ControllerSnapshotOwnershipView")
            .substringBefore("internal class ControllerSnapshotStore")
        listOf(
            "ControllerCandidateOwnership", "ControllerCleanupOwnership",
            "ControllerPreparationQuarantineOwnership", "ProviderDescriptorRetentionToken",
            "ControllerDesiredSnapshot", "ControllerCandidateSnapshot", "ControllerProviderReference",
            "ImageEncoderProvider",
        ).forEach { token -> assertFalse("Capability escaped into immutable view: $token", immutableView.contains(token)) }
    }

    @Test
    fun candidateCommitIsReachableOnlyThroughAStoreMintedFullPrevalidation() {
        val store = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerSnapshotStore.kt"),
        )
        assertTrue(
            store.contains(
                "internal fun commitCandidate(\n" +
                        "        prevalidation: ControllerCandidateCommitPrevalidation,",
            ),
        )
        assertTrue(store.contains("private fun commitCandidatePrevalidated("))
        assertFalse(Regex("internal fun commitCandidatePrevalidated\\s*\\(").containsMatchIn(store))
        assertFalse(Regex("fun commitCandidate\\s*\\(\\s*ownership:").containsMatchIn(store))
        assertTrue(store.contains("physicalCurrentTarget !== expectedTarget"))
        assertEquals(2, Regex("commitCandidatePrevalidated\\(").findAll(store).count())

        val nonPrivateRawAuthorityFunctions = Regex(
            "(?ms)^[ \\t]*(?:(internal|private)\\s+)?fun\\s+(\\w+)\\s*\\((.*?)\\)\\s*[:={]",
        ).findAll(store)
            .filter { it.groupValues[1] != "private" }
            .filter { match ->
                val parameters = match.groupValues[3]
                parameters.contains("ControllerCandidateOwnership") &&
                        parameters.contains("CompleteOwnerIdentity") &&
                        parameters.contains("ControllerEffectiveSnapshot")
            }
            .map { match -> match.groupValues[2] }
            .toList()
        assertEquals(
            "Only the ticket minter may accept the three raw candidate facts.",
            listOf("prevalidateCandidateCommit"),
            nonPrivateRawAuthorityFunctions,
        )
    }

    @Test
    fun oldAuthorityDoesNotImportSnapshotLeafInReverse() {
        val productionRoot = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val leafRoot = productionRoot.resolve("internal/control")
        val violations = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { it.parent != leafRoot || it.fileName.toString() !in files + exactConsumers }
                .filter { path -> leafNames.any { name -> Regex("\\b$name\\b").containsMatchIn(Files.readString(path)) } }
                .toList()
        }
        assertTrue("Reverse snapshot wiring: $violations", violations.isEmpty())
    }

    @Test
    fun snapshotReverseGuardEnumeratesActualSymbolsAndDetectsSamePackageFixtures() {
        assertEquals(leafNames, declaredInternalNames(files))
        leafNames.forEach { name ->
            assertTrue(
                "Same-package snapshot guard missed: $name",
                Regex("\\b$name\\b").containsMatchIn("private val leaked: $name? = null"),
            )
        }
    }

    @Test
    fun onlySnapshotStoreUsesLedgerOwnershipApiIncludingFromSamePackage() {
        val productionRoot = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val allowed = setOf(
            productionRoot.resolve("internal/control/ControllerSnapshotStore.kt"),
            productionRoot.resolve("internal/encoding/LiveProviderDescriptorLedger.kt"),
        )
        val violations = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { it !in allowed }
                .filter { path -> ledgerOwnershipNames.any { name -> Regex("\\b$name\\b").containsMatchIn(Files.readString(path)) } }
                .toList()
        }
        assertTrue("Reverse/same-package ledger ownership wiring: $violations", violations.isEmpty())
    }

    @Test
    fun ledgerReverseGuardEnumeratesActualSymbolsAndDetectsSamePackageFixtures() {
        assertEquals(
            ledgerOwnershipNames,
            declaredInternalNames(setOf("../encoding/LiveProviderDescriptorLedger.kt")),
        )
        ledgerOwnershipNames.forEach { name ->
            assertTrue(
                "Same-package ledger guard missed: $name",
                Regex("\\b$name\\b").containsMatchIn("private val leaked: $name? = null"),
            )
        }
    }

    private fun declaredInternalNames(relativeFiles: Set<String>): List<String> {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control")
        val declaration = Regex(
            "(?m)^internal (?:data class|enum class|sealed interface|class|object) ([A-Za-z][A-Za-z0-9_]*)",
        )
        return relativeFiles.flatMap { file ->
            declaration.findAll(Files.readString(root.resolve(file))).map { match -> match.groupValues[1] }.toList()
        }.sorted()
    }

    private companion object {
        val files = setOf("ControllerSnapshot.kt", "ControllerTargetSnapshot.kt", "ControllerSnapshotStore.kt")
        val exactConsumers = setOf("ControllerState.kt", "ControllerPreparedTurn.kt")
        val forbidden = listOf(
            "android.", "kotlinx.coroutines", "StateFlow", "SharedFlow", "ByteArray", "ByteBuffer",
            "internal.lifecycle", "internal.startup", "internal.session", "internal.runtime", "java.lang.reflect",
            "internal.gl", "internal.operation", "internal.platform", "internal.rendering", "internal.target",
            "Facility", "ScreenCaptureSessionState", "ScreenCaptureEffectiveParameters", "ScreenCaptureOutputPlan",
            "synchronized", "ReentrantLock", "MutableList", "MutableMap", "HashMap", "Flow<",
            "32_768", "300_000", "0.10", "2.00", "MAX_FPS_RANGE", "PERIODIC_REFRESH_RANGE",
        )
        val required = listOf(
            "provider === candidate", "System.identityHashCode", "NormalizedNoOp",
            "FrameRateOnly", "SameTargetReplacement", "TargetReplan", "RequestNotRepresentable",
            "requestedParameters: ScreenCaptureParameters", "val samplingCapacity: SamplingDemand", "cappedAtOne()",
        )
        val leafNames = listOf(
            "NormalizedOutputValues", "NormalizedSourceRegion", "NormalizedCrop", "NormalizedOutputSize",
            "NormalizedContentMode", "NormalizedRotation", "NormalizedMirror", "NormalizedColorMode",
            "NormalizedFrameRate", "ControllerProviderReference", "ControllerDesiredSnapshot",
            "ControllerCandidateSnapshot", "ControllerEffectiveSnapshot", "ControllerParameterClassification",
            "ControllerParameterClassifier", "ControllerTargetSnapshot", "TargetAssignmentEvidence",
            "TargetHealthEvidence", "TargetRetention", "TargetReplacementReason", "ControllerCandidateOwnership",
            "ControllerCandidatePrevalidation", "ControllerCurrentCandidatePrevalidation",
            "CandidateDispositionTrigger", "CandidateDispositionAction",
            "ControllerCandidateDispositionPrevalidation", "ControllerTerminalCandidateDispositionPrevalidation",
            "ControllerCandidateCommitPrevalidation",
            "ControllerCleanupOwnership", "ControllerPreparationQuarantineOwnership", "CandidateOwnershipAdmission",
            "CandidateCommitDisposition",
            "QuarantineReturnDisposition", "CleanupTransitionDisposition", "ActiveOwnerReturnDisposition",
            "TerminalOwnershipDisposition", "CandidateDispositionOutcome",
            "ControllerTargetAcknowledgementPrevalidation", "TargetAcknowledgementDisposition",
            "ControllerSnapshotOwnershipView", "ControllerSnapshotStore",
        ).sorted()
        val ledgerOwnershipNames = listOf(
            "LiveProviderDescriptorLedger", "LiveProviderDescriptorSnapshot", "ProviderDescriptorRetentionRole",
            "ProviderDescriptorRetentionToken", "ProviderDescriptorReserveResult", "ProviderDescriptorForkResult",
            "ProviderDescriptorRecordResult",
        ).sorted()
    }
}
