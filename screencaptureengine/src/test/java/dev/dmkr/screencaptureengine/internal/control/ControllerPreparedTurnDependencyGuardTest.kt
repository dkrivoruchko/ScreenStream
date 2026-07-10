package dev.dmkr.screencaptureengine.internal.control

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPreparedTurnDependencyGuardTest {
    @Test
    fun completeTurnShapeIsOwnedBoundedExactAndOldAxesAreAbsent() {
        val text = preparedTurnText()
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine")

        requiredShape.forEach { assertTrue("Missing complete-turn shape: $it", text.contains(it)) }
        oldShape.forEach { assertFalse("Obsolete four-axis shape remains: $it", text.contains(it)) }
        forbiddenDependencies.forEach { assertFalse("Forbidden prepared-turn dependency: $it", text.contains(it)) }
        assertTrue(text.contains("private val facts: List<ControllerPreparedFact> = facts.toList()"))
        assertFalse(text.contains("\n        val facts: List<ControllerPreparedFact>"))
        assertFalse(text.contains("MutableList<"))
        val budget = text.substringAfter("internal class ControllerReconfigurationStartIdentity(")
            .substringBefore("/** One owned immutable complete turn")
        assertFalse(budget.contains("var "))
        assertFalse(budget.contains("claim("))
        assertFalse(Files.exists(root.resolve("internal/control/ControllerTurnPrevalidatedFacts.kt")))
        val stale = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .anyMatch { Files.readString(it).contains("ControllerTurnPrevalidatedFacts") }
        }
        assertFalse("Old prevalidated-turn type remains referenced.", stale)
    }

    @Test
    fun everyRawIngressAndDirectLeafHasOneExplicitMappingWithNoCatchAll() {
        val text = preparedTurnText()
        val mapping = text.substringAfter("private fun ControllerPreparedFact.matchesExactWrapper()")
            .substringBefore("private fun FramePacingResetFact.matches")
        rawLeafInventory.forEach { leaf ->
            assertTrue(
                "Raw fact mapping must occur exactly once: $leaf",
                mapping.split(leaf).size - 1 == 1,
            )
        }
        assertFalse(mapping.contains("else ->"))
        parameterLeafInventory.forEach { leaf ->
            assertTrue("Parameter leaf mapping must occur exactly once: $leaf", mapping.split(leaf).size - 1 == 1)
        }
    }

    @Test
    fun preparedFactSealedInventoryCannotEscapeItsProductionFile() {
        val productionRoot = Path.of("src/main/java/dev/dmkr/screencaptureengine")
        val root = productionRoot.resolve("internal/control")
        val owner = root.resolve("ControllerPreparedTurn.kt")
        val ownerText = Files.readString(owner)
        val direct = Regex(
            "(?s)internal (?:sealed interface|class) (\\w+)\\s*" +
                    "(?:\\((?:(?!\\ninternal ).)*?\\))?\\s*:\\s*ControllerPreparedFact",
        ).findAll(ownerText).map { it.groupValues[1] }.toSet() - PREPARED_TURN_CONTAINER
        assertTrue("Direct prepared-fact inventory mismatch: $direct", direct == directPreparedTypes)
        assertFalse(
            Regex("(?s)internal class ControllerPreparedTurn\\s*\\([^)]*\\)\\s*:\\s*ControllerPreparedFact")
                .containsMatchIn(ownerText),
        )
        val sources = Regex(
            "(?s)class (\\w+)\\s*\\((?:(?!\\n {4}class ).)*?\\)\\s*:\\s*PreparedSourceFact",
        ).findAll(ownerText).map { it.groupValues[1] }.toSet()
        assertTrue("Prepared source inventory mismatch: $sources", sources == preparedSourceTypes)
        val productionFiles = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
        val productionTexts = productionFiles.map { Files.readString(it) }
        val controlFiles = productionFiles.filter { path ->
            Regex("(?m)^package\\s+dev\\.dmkr\\.screencaptureengine\\.internal\\.control\\s*$")
                .containsMatchIn(Files.readString(path))
        }
        val escaped = controlFiles.filter { path ->
            path != owner && listOf("ControllerPreparedFact", "PreparedSourceFact").any { type ->
                inheritsResolvedAlias(Files.readString(path), resolveAliases(productionTexts, type))
            }
        }
        assertTrue("Prepared-fact sealed subtype escaped its exact inventory: $escaped", escaped.isEmpty())
        assertTrue(escapedSubtype("internal class Rogue : ControllerPreparedFact", "ControllerPreparedFact"))
        assertTrue(
            escapedSubtype(
                "import dev.dmkr.screencaptureengine.internal.control.ControllerPreparedFact as F\n" +
                        "internal class Rogue : F",
                "ControllerPreparedFact",
            ),
        )
        val crossFileAliases = resolveAliases(
            listOf(
                "typealias SharedPrepared = ControllerPreparedFact",
                "typealias IndirectPrepared = SharedPrepared",
            ),
            "ControllerPreparedFact",
        )
        assertTrue(inheritsResolvedAlias("internal class Rogue : IndirectPrepared", crossFileAliases))
        assertFalse(inheritsResolvedAlias("internal fun consume(value: IndirectPrepared) = value", crossFileAliases))
        val externalAliases = resolveAliases(
            listOf("package other.pkg\ntypealias ExternalPrepared = ControllerPreparedFact"),
            "ControllerPreparedFact",
        )
        assertTrue(
            inheritsResolvedAlias(
                "import other.pkg.ExternalPrepared as Imported\ninternal class Rogue : Imported",
                externalAliases,
            ),
        )
        assertTrue(
            inheritsResolvedAlias(
                "import other.pkg.*\ninternal class Rogue : ExternalPrepared",
                externalAliases,
            ),
        )
        assertTrue(inheritsResolvedAlias("internal class Rogue : other.pkg.ExternalPrepared", externalAliases))
        assertFalse(inheritsResolvedAlias("internal class Innocent : UnrelatedPrepared", externalAliases))
        assertFalse(
            inheritsResolvedAlias(
                "internal class Consumer<T : ExternalPrepared>(val value: T)",
                externalAliases,
            ),
        )
        assertTrue(
            escapedSubtype(
                "typealias F = ControllerPreparedFact\ninternal class Rogue : F",
                "ControllerPreparedFact",
            ),
        )
    }

    @Test
    fun occurrenceMaterializationAndFrozenRemainOutsidePreparedTurn() {
        val text = preparedTurnText()
        listOf(
            "ControllerTurnOccurrenceReservations",
            "NormalRange",
            "FrozenSequenceBoundary",
            "ControllerOccurrenceIdentity",
        ).forEach { assertFalse("Concrete occurrence input leaked into K3 input: $it", text.contains(it)) }
    }

    @Test
    fun exactTerminalCauseAndTaskInventoriesAreClosed() {
        val text = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerEvidence.kt"),
        )
        terminalCauseTypes.forEach { type ->
            assertTrue("Missing exact terminal cause: $type", text.contains("class $type("))
            assertTrue(
                "Terminal cause/evidence mismatch: $type",
                Regex(
                    "(?s)class $type\\((?:(?!\\n\\x20{4}class ).)*?" +
                        "override val evidence: TerminalEvidence = TerminalEvidence\\.$type",
                ).containsMatchIn(text),
            )
        }
        assertTrue(terminalCauseTypes.size == TerminalEvidence.entries.size)
        glTaskTypes.forEach { assertTrue("Missing GL task identity: $it", text.contains("class $it(")) }
        platformTaskTypes.forEach { assertTrue("Missing platform task identity: $it", text.contains(it)) }
        providerOwnershipTypes.forEach { assertTrue("Missing provider ownership identity: $it", text.contains(it)) }
        val ingress = Files.readString(
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerIngress.kt"),
        )
        assertTrue(ingress.contains("val cause: ControllerTerminalCause"))
        assertFalse(ingress.contains("val evidence: TerminalEvidence)"))
        val controlRoot = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control")
        val evidenceFile = controlRoot.resolve("ControllerEvidence.kt")
        val escaped = Files.list(controlRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && it != evidenceFile }
                .filter { path ->
                    val candidate = Files.readString(path)
                    listOf(
                        "ControllerTerminalCause",
                        "ControllerGlTaskIdentity",
                        "ControllerPlatformOperationIdentity",
                        "ControllerProviderOwnershipIdentity",
                    ).any { escapedSubtype(candidate, it) }
                }
                .toList()
        }
        assertTrue("Terminal causal subtype escaped its exact owner file: $escaped", escaped.isEmpty())
    }

    private fun preparedTurnText(): String = Files.readString(
        Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/control/ControllerPreparedTurn.kt"),
    )

    private fun escapedSubtype(text: String, type: String): Boolean {
        val aliases = mutableSetOf(type)
        Regex("(?m)^\\s*import\\s+[\\w.]+\\.$type(?:\\s+as\\s+(\\w+))?\\s*$")
            .findAll(text)
            .forEach { aliases += it.groupValues[1].ifEmpty { type } }
        var changed: Boolean
        do {
            changed = false
            Regex("(?m)^\\s*typealias\\s+(\\w+)\\s*=\\s*([\\w.]+)\\s*$").findAll(text).forEach { match ->
                val alias = match.groupValues[1]
                val target = match.groupValues[2].substringAfterLast('.')
                if (target in aliases && aliases.add(alias)) changed = true
            }
        } while (changed)
        return aliases.any { alias ->
            Regex(
                "(?m)^\\s*(?:internal\\s+)?(?:sealed\\s+)?(?:class|object|interface)\\s+\\w+" +
                    "(?:<[^>{}]*>)?\\s*(?:\\([^)]*\\))?\\s*:\\s*[^{}]*?" +
                    "(?:[\\w.]+\\.)?$alias\\b",
            )
                .containsMatchIn(text)
        }
    }

    private fun resolveAliases(texts: List<String>, root: String): Set<String> {
        val aliases = mutableSetOf(root)
        var changed: Boolean
        do {
            changed = false
            texts.forEach { text ->
                Regex("(?m)^\\s*typealias\\s+(\\w+)\\s*=\\s*([\\w.]+)\\s*$").findAll(text).forEach { match ->
                    if (match.groupValues[2].substringAfterLast('.') in aliases && aliases.add(match.groupValues[1])) {
                        changed = true
                    }
                }
            }
        } while (changed)
        return aliases
    }

    private fun inheritsResolvedAlias(text: String, aliases: Set<String>): Boolean {
        val visible = aliases.toMutableSet()
        Regex("(?m)^\\s*import\\s+[\\w.]+\\.(\\w+)\\s+as\\s+(\\w+)\\s*$").findAll(text).forEach { match ->
            if (match.groupValues[1] in aliases) visible += match.groupValues[2]
        }
        return visible.any { alias ->
            Regex(
                "(?m)^\\s*(?:internal\\s+)?(?:sealed\\s+)?(?:class|object|interface)\\s+\\w+" +
                    "(?:<[^>{}]*>)?\\s*(?:\\([^)]*\\))?\\s*:\\s*[^{}]*?" +
                    "(?:[\\w.]+\\.)?$alias\\b",
            )
                .containsMatchIn(text)
        }
    }

    private companion object {
        val requiredShape = listOf(
            "sealed interface ControllerPreparedFact",
            "sealed interface PreparedSourceFact",
            "class PreparedTerminalFact",
            "class PreparedPrePublicFact",
            "class PreparedCancellationFact",
            "class PreparedVisibilityFact",
            "class PreparedParameterAdmissionFact",
            "class PreparedParameterProgressFact",
            "class PreparedArbiterReasonAddedFact",
            "class PreparedArbiterReasonClearedFact",
            "class ControllerReconfigurationStartIdentity",
            "class ControllerPreparedTurn",
            "MAX_TURN_FACTS = 26",
            "facts.toList()",
            "exactSlotsAreUnique",
            "matchesExactWrapper",
        )
        val oldShape = listOf(
            "ControllerPreparedTurn.Forward",
            "sealed interface ControllerPreparedTurn",
            "class Runtime(",
            "class PrePublic(",
            "class Losing",
            "sealed interface Boundary",
            "class IdentityBudgets",
            "ControllerReconfigurationIdentityBudget",
            "ControllerReconfigurationStartBudget",
            "val forward:",
            "val losing:",
            "val boundary:",
            "TerminalCandidateDisposition",
            "PrevalidatedLosingCandidateDisposition",
        )
        val forbiddenDependencies = listOf(
            "ControllerReducer",
            "ControllerTransition",
            "ControllerCommitKernel",
            "SessionController",
            "ScreenCaptureProblem",
            "ScreenCaptureSessionState",
            "ImageEncoderProvider",
            "android.",
            "kotlinx.coroutines",
            "() ->",
            "ByteArray",
            "ByteBuffer",
            "CandidateDispositionAction",
            "ControllerCandidateDispositionPrevalidation",
            "ControllerCandidatePrevalidation",
            "ControllerCandidateCommitPrevalidation",
            "ControllerTargetAcknowledgementPrevalidation",
            "ControllerDesiredSnapshot",
            "ControllerCandidateSnapshot",
            ".provider",
        )
        val rawLeafInventory = listOf(
            "is ControllerIngress.Terminal ->",
            "is ControllerIngress.Metrics ->",
            "is ControllerIngress.CapturedResize ->",
            "is ControllerIngress.SourceTrust ->",
            "is ControllerIngress.Pause ->",
            "is ControllerIngress.Cancellation ->",
            "is ControllerIngress.Visibility ->",
            "is ControllerDirectFact.PrePublicRetirement ->",
            "is ControllerDirectFact.NormalizedNoOpReady ->",
            "is ControllerDirectFact.ParameterAdmitted ->",
            "is ControllerDirectFact.InitialActiveReady ->",
            "is ControllerDirectFact.Parameter ->",
            "is ControllerDirectFact.ArbiterReasonAdded ->",
            "is ControllerDirectFact.ArbiterReasonCleared ->",
            "is ControllerDirectFact.TargetAcknowledged ->",
            "is ControllerDirectFact.CompleteOwnerReady ->",
            "is ControllerDirectFact.RecoveryCandidatePreparationFailed ->",
        )
        val parameterLeafInventory = listOf(
            "is ParameterTransactionFact.CandidatePrepared ->",
            "is ParameterTransactionFact.FrameRateCommitReady ->",
            "is ParameterTransactionFact.ReadyPermitAcquired,",
            "is ParameterTransactionFact.DrainCompleted,",
            "is ParameterTransactionFact.RetargetStarted,",
            "is ParameterTransactionFact.ReadyPermitRejected,",
            "is ParameterTransactionFact.RetargetStartTimedOut,",
            "is ParameterTransactionFact.Superseded,",
            "is ParameterTransactionFact.TargetAcknowledged ->",
            "is ParameterTransactionFact.OwnerCommitReady ->",
            "is ParameterTransactionFact.CandidatePreparationFailed ->",
            "is ParameterTransactionFact.CandidatePreparationStartedTimedOut ->",
        )
        val directPreparedTypes = setOf(
            "NormalizedNoOpReady",
            "PrevalidatedInitialActive",
            "PrevalidatedFrameRateCommit",
            "PrevalidatedCandidatePrepared",
            "PrevalidatedCandidatePreparationFailed",
            "PrevalidatedParameterTargetAcknowledgement",
            "PrevalidatedOwnerCommit",
            "PrevalidatedRecoveryOwnerCommit",
            "PrevalidatedRecoveryTargetAcknowledgement",
            "PrevalidatedCandidatePreparationStartedTimeout",
            "PrevalidatedRecoveryCandidatePreparationFailed",
            "PreparedSourceFact",
            "PreparedTerminalFact",
            "PreparedPrePublicFact",
            "PreparedCancellationFact",
            "PreparedVisibilityFact",
            "PreparedParameterAdmissionFact",
            "PreparedParameterProgressFact",
            "PreparedArbiterReasonAddedFact",
            "PreparedArbiterReasonClearedFact",
        )
        val preparedSourceTypes = setOf("Metrics", "CapturedResize", "SourceTrust", "Pause")
        const val PREPARED_TURN_CONTAINER = "ControllerPreparedTurn"
        val terminalCauseTypes = setOf(
            "ProjectionStopped",
            "DisplayStopped",
            "OwnerStopped",
            "StartedEncoderStall",
            "PoisonedProviderPreparationRequired",
            "StartedGlTimeout",
            "StartedPlatformTimeout",
            "ListenerRetirementUnprovable",
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
        )
        val glTaskTypes = setOf(
            "Bootstrap",
            "TargetCreate",
            "PipelinePrepare",
            "TargetProbe",
            "Production",
            "PboProgress",
            "TargetDestroy",
        )
        val platformTaskTypes = setOf(
            "class DeviceMemorySample(",
            "class CallbackAttach(",
            "class CallbackDetach(",
            "class Create(",
            "class Retarget(",
            "sealed interface TerminalCleanup",
        )
        val providerOwnershipTypes = setOf(
            "class CandidatePreparation(",
            "class ActiveEncode(",
            "class RetiredCleanup(",
        )
    }
}
