package dev.dmkr.screencaptureengine.internal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RuntimeBoundaryStaticGuardTest {
    @Test
    fun allRuntimeSourceFilesAreExplicitlyClassifiedForBoundaryGuards() {
        val discoveredPaths = discoveredRuntimeSourceFiles()
            .map { sourceFile -> sourceFile.runtimeRelativePath }
            .toSet()

        assertEquals(
            "Runtime boundary guard classifications must be updated when runtime files are added, " +
                    "removed, or moved.",
            runtimeFileClassifications.keys.sorted(),
            discoveredPaths.sorted(),
        )
    }

    @Test
    fun dormantFoundationRemainsIsolatedFromOldAndPublicAuthority() {
        val sourceFiles = runtimeSourceFiles()
        val foundationFiles = sourceFiles.filter { sourceFile ->
            sourceFile.role == RuntimeFileRole.DormantFoundation
        }

        assertEquals(
            "The dormant-foundation role must cover exactly the permanent mechanical foundation.",
            dormantFoundationPaths.sorted(),
            foundationFiles.map { sourceFile -> sourceFile.runtimeRelativePath }.sorted(),
        )

        foundationFiles.forEach { sourceFile ->
            val executableContent = sourceFile.dormantFoundationExecutableContent()
            val applicablePatterns = dormantFoundationForbiddenAuthorityPatterns +
                    if (sourceFile.runtimeRelativePath in controllerProtocolPaths) {
                        controllerProtocolForbiddenDependencyPatterns
                    } else {
                        emptyList()
                    }
            val forbiddenReferences = applicablePatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match ->
                    "${pattern.description}: ${match.value}"
                }
            }

            assertTrue(
                "${sourceFile.displayPath} imports or references old/public authority: " +
                        forbiddenReferences.joinToString(),
                forbiddenReferences.isEmpty(),
            )
        }

        sourceFiles
            .filter { sourceFile -> sourceFile.isOldAuthoritySource() }
            .forEach { sourceFile ->
                val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
                val references = dormantFoundationReferencePattern.findAll(executableContent)
                    .map { match -> match.value }
                    .toList()

                assertTrue(
                    "${sourceFile.displayPath} depends on the dormant foundation before cutover: " +
                            references.joinToString(),
                    references.isEmpty(),
                )
            }
    }

    @Test
    fun dormantPlanningAndStorageRemainPureIndependentAndBaselineOnly() {
        val sourceFiles = runtimeSourceFiles()
        val leafFiles = sourceFiles.filter { sourceFile ->
            sourceFile.runtimeRelativePath in dormantPlanningStoragePaths
        }

        assertEquals(
            "Planning/storage leaf coverage must remain exact.",
            dormantPlanningStoragePaths.sorted(),
            leafFiles.map { sourceFile -> sourceFile.runtimeRelativePath }.sorted(),
        )
        leafFiles.forEach { sourceFile ->
            val executableContent = sourceFile.dormantFoundationExecutableContent()
            val couplingPattern = if (sourceFile.runtimeRelativePath.startsWith("planning/")) {
                dormantPlanningToStoragePattern
            } else {
                dormantStorageToPlanningPattern
            }
            val forbiddenDependencies = (dormantPlanningStorageForbiddenPatterns + couplingPattern).flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match ->
                    "${pattern.description}: ${match.value}"
                }
            }
            val optionalMechanisms = dormantBaselineOptionalMechanismPattern.findAll(executableContent)
                .map { match -> match.value }
                .toList()

            assertTrue(
                "${sourceFile.displayPath} crosses the pure planning/storage leaf boundary: " +
                        forbiddenDependencies.joinToString(),
                forbiddenDependencies.isEmpty(),
            )
            assertTrue(
                "${sourceFile.displayPath} introduces optional native/PBO/downscale behavior: " +
                        optionalMechanisms.joinToString(),
                optionalMechanisms.isEmpty(),
            )
        }
    }

    private fun RuntimeSourceFile.dormantFoundationExecutableContent(): String {
        val executableContent = Files.readString(path).withoutKotlinCommentsAndStrings()
        val allowedImports = dormantFoundationAllowedPublicImports[runtimeRelativePath].orEmpty()
        val actualImports = publicRootImportPattern.findAll(executableContent)
            .map { match -> match.value.trim() }
            .toSet()
        assertEquals(
            "$displayPath changed its exact allowed public value/SPI imports.",
            allowedImports,
            actualImports,
        )
        return allowedImports.fold(executableContent) { content, allowedImport ->
            content.replace(allowedImport, "")
        }
    }

    @Test
    fun dormantFoundationDependencyPatternsCoverSessionAndPublicWildcards() {
        val sourceRoot = resolveSourceRoot().resolve(runtimePackagePath)
        assertEquals(
            dormantSnapshotBoundaryNames,
            declaredInternalNames(
                sourceRoot,
                setOf(
                    "control/ControllerSnapshot.kt",
                    "control/ControllerSnapshotStore.kt",
                    "control/ControllerTargetSnapshot.kt",
                ),
            ),
        )
        assertEquals(
            dormantTypedLedgerNames,
            declaredInternalNames(sourceRoot, setOf("encoding/LiveProviderDescriptorLedger.kt")),
        )
        val foundationForbiddenSamples = listOf(
            "import dev.dmkr.screencaptureengine.internal.session.delivery.LegacyDeliveryOwner",
            "import dev.dmkr.screencaptureengine.*",
            "dev.dmkr.screencaptureengine.ScreenCaptureSession",
        )
        foundationForbiddenSamples.forEach { sample ->
            assertTrue(
                "Dormant-foundation dependency guard missed: $sample",
                dormantFoundationForbiddenAuthorityPatterns.any { pattern ->
                    pattern.regex.containsMatchIn(sample)
                },
            )
        }
        assertTrue(
            "Dormant-foundation dependency guard rejected a foundation-internal dependency.",
            dormantFoundationForbiddenAuthorityPatterns.none { pattern ->
                pattern.regex.containsMatchIn(
                    "import dev.dmkr.screencaptureengine.internal.policy.ScreenCaptureEnginePolicyDefaults",
                )
            },
        )

        listOf(
            "import dev.dmkr.screencaptureengine.internal.encoding.*",
            "import dev.dmkr.screencaptureengine.internal.planning.*",
            "import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanner",
            "import dev.dmkr.screencaptureengine.internal.encoding.storage.SegmentedEncodedSink",
            "import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRetentionRole",
            "import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRetentionToken",
            "import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorForkResult",
            "dev.dmkr.screencaptureengine.internal.operation.OperationRecord",
        ).forEach { sample ->
            assertTrue(
                "Old-authority dependency guard missed: $sample",
                dormantFoundationReferencePattern.containsMatchIn(sample),
            )
        }
        (dormantSnapshotBoundaryNames.map { name ->
            "dev.dmkr.screencaptureengine.internal.control.$name"
        } + dormantTypedLedgerNames.map { name ->
            "dev.dmkr.screencaptureengine.internal.encoding.$name"
        } + listOf(
            "dev.dmkr.screencaptureengine.internal.control.ControllerDirectFact",
            "dev.dmkr.screencaptureengine.internal.control.ControllerGeometryAccumulator",
        )).forEach { sample ->
            assertTrue(
                "Old-authority dependency guard missed exact dormant symbol: $sample",
                dormantFoundationReferencePattern.containsMatchIn(sample),
            )
        }
    }

    private fun declaredInternalNames(root: Path, relativeFiles: Set<String>): Set<String> {
        val declaration = Regex(
            "(?m)^internal (?:data class|enum class|sealed interface|class|object) ([A-Za-z][A-Za-z0-9_]*)",
        )
        return relativeFiles.flatMapTo(mutableSetOf()) { relativeFile ->
            declaration.findAll(Files.readString(root.resolve(relativeFile)))
                .map { match -> match.groupValues[1] }
                .toList()
        }
    }

    @Test
    fun dormantPlanningStorageDependencyPatternsCoverBothDirectionsAndOptionalMechanisms() {
        listOf(
            "import dev.dmkr.screencaptureengine.internal.platform.MemorySampler",
            "import dev.dmkr.screencaptureengine.internal.control.SessionController",
            "import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparer",
        ).forEach { sample ->
            assertTrue(
                "Planning/storage authority guard missed: $sample",
                dormantPlanningStorageForbiddenPatterns.any { pattern -> pattern.regex.containsMatchIn(sample) },
            )
        }
        assertTrue(
            dormantPlanningToStoragePattern.regex.containsMatchIn(
                "import dev.dmkr.screencaptureengine.internal.encoding.storage.ImmutableEncodedPayload",
            ),
        )
        assertTrue(
            dormantStorageToPlanningPattern.regex.containsMatchIn(
                "import dev.dmkr.screencaptureengine.internal.planning.MemoryPlanning",
            ),
        )
        assertFalse(
            dormantStorageToPlanningPattern.regex.containsMatchIn(
                "import dev.dmkr.screencaptureengine.internal.planning.CheckedArithmetic",
            ),
        )
        listOf("PBO", "NativeJpegBackend", "EarlyDownscalePlan").forEach { sample ->
            assertTrue(
                "Baseline-only mechanism guard missed: $sample",
                dormantBaselineOptionalMechanismPattern.containsMatchIn(sample),
            )
        }
    }

    @Test
    fun oldAndNewPlanningRemainIsolatedDespiteSharingOnePackage() {
        val sourceFiles = runtimeSourceFiles()
        sourceFiles
            .filter { sourceFile -> sourceFile.runtimeRelativePath in legacyPlanningPaths }
            .forEach { sourceFile ->
                val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
                val references = newPlanningSimpleNamePattern.findAll(executableContent)
                    .map { match -> match.value }
                    .toList()

                assertTrue(
                    "${sourceFile.displayPath} uses dormant planning through an unqualified same-package symbol: " +
                            references.joinToString(),
                    references.isEmpty(),
                )
            }
        sourceFiles
            .filter { sourceFile -> sourceFile.runtimeRelativePath in dormantNewPlanningPaths }
            .forEach { sourceFile ->
                val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
                val references = legacyPlanningSimpleNamePattern.findAll(executableContent)
                    .map { match -> match.value }
                    .toList()

                assertTrue(
                    "${sourceFile.displayPath} uses legacy planning through an unqualified same-package symbol: " +
                            references.joinToString(),
                    references.isEmpty(),
                )
            }
    }

    @Test
    fun samePackagePlanningPatternsCoverDirectUnqualifiedReferences() {
        listOf(
            "BaselineOutputPlanner.planScaleFactor(size)",
            "MemoryPlanning.admit(allocation, evidence)",
            "ShaderPrecisionBounds.evaluate(evidence)",
            "CheckedArithmetic.addNonNegative(a, b)",
        ).forEach { sample ->
            assertTrue(
                "New-planning same-package guard missed: $sample",
                newPlanningSimpleNamePattern.containsMatchIn(sample),
            )
        }
        listOf(
            "ScreenCaptureOutputPlanner.plan(parameters)",
            "RuntimeParameterUpdateClassifier.classify(current, requested)",
        ).forEach { sample ->
            assertTrue(
                "Legacy-planning same-package guard missed: $sample",
                legacyPlanningSimpleNamePattern.containsMatchIn(sample),
            )
        }
    }

    private fun RuntimeSourceFile.isOldAuthoritySource(): Boolean =
        runtimeRelativePath == "DefaultScreenCaptureEngine.kt" ||
                runtimeRelativePath.startsWith("lifecycle/") ||
                runtimeRelativePath.startsWith("startup/") ||
                runtimeRelativePath.startsWith("session/")

    @Test
    fun startupAndPreparationFilesConservativelyStayBeforeRuntimeConsumptionEncodeAndPublication() {
        val sourceFiles = runtimeSourceFiles().filter { sourceFile ->
            sourceFile.role in startupPreparationRoles
        }

        assertTrue("No startup/preparation runtime files were classified.", sourceFiles.isNotEmpty())

        sourceFiles.forEach { sourceFile ->
            val executableContent = sourceFile.startupPreparationExecutableContent()

            startupPreparationForbiddenPatterns.forEach { pattern ->
                assertFalse(
                    "${sourceFile.displayPath} crosses startup/preparation runtime boundary: " +
                            pattern.description,
                    pattern.regex.containsMatchIn(executableContent),
                )
            }
        }
    }

    @Test
    fun defaultEnginePrePublicCommitWiringStaysBeforeRuntimeConsumptionEncodeAndPublication() {
        val sourceFile = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "DefaultScreenCaptureEngine.kt"
        }
        val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
        val matches = defaultEnginePrePublicCommitForbiddenPatterns.flatMap { pattern ->
            pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "${sourceFile.displayPath} crosses default-engine pre-public-commit boundary: " +
                    matches.joinToString(),
            matches.isEmpty(),
        )
    }

    private fun RuntimeSourceFile.startupPreparationExecutableContent(): String {
        val executableContent = Files.readString(path).withoutKotlinCommentsAndStrings()
        if (runtimeRelativePath != "lifecycle/InitialRuntimeResourceOwner.kt") return executableContent
        return executableContent.replace(
            "import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionTerminalCommit",
            "",
        )
    }

    @Test
    fun directGlesCallsStayInLowLevelAdapterAllowlist() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val directGlesUsages = directGlesUsagesIn(executableContent)
            val forbiddenDirectGlesUsages = directGlesUsages.filterNot { usage ->
                sourceFile.allowsDirectGlesUsage(usage)
            }

            assertTrue(
                "${sourceFile.displayPath} calls GLES directly outside low-level GLES adapters or " +
                        "explicit projection-target texture seams: " +
                        forbiddenDirectGlesUsages.joinToString { it.expression },
                forbiddenDirectGlesUsages.isEmpty(),
            )

            if (sourceFile.runtimeRelativePath == "target/ProjectionTargetOwner.kt") {
                assertEquals(
                    "${sourceFile.displayPath} changed its explicit direct GLES texture seam. " +
                            "Move new direct GLES work into a low-level adapter or update this guard deliberately.",
                    projectionTargetOwnerDirectGlesCallCounts,
                    directGlesUsages.groupingBy { usage -> usage.callName }.eachCount(),
                )
            }
        }
    }

    @Test
    fun screenCaptureSessionCoreUseStaysInActiveSessionIntegrationOwner() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = sessionCoreUsePatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertTrue(
                "${sourceFile.displayPath} imports or uses ScreenCaptureSessionCore outside active/session integration owner: " +
                        matches.joinToString(),
                matches.isEmpty() || sourceFile.runtimeRelativePath in sessionCoreIntegrationOwnerPaths,
            )
        }
    }

    @Test
    fun activeRuntimeProjectionStopSensitiveCoreMutationsStayInFencedPaths() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val failureFence = executableContent.functionBody("finishRuntimeFailureWithProjectionFence")
        val frameRatePolicy = executableContent.functionBody("admitFrameRatePolicy")

        assertEquals(
            "Runtime failure terminal commits must stay centralized behind the projection-stop fence.",
            1,
            Regex("""\.\s*finishFailed\s*\(""").findAll(executableContent).count(),
        )
        assertTrue(
            "Runtime failure terminal fence no longer commits the Failed state.",
            Regex("""\.\s*finishFailed\s*\(""").containsMatchIn(failureFence),
        )
        assertTrue(
            "Runtime failure terminal fence must arbitrate raw projection stop before committing.",
            failureFence.contains("arbitrateProjectionStopPublicOutcome"),
        )
        assertEquals(
            "Unmaterialized frame-rate drop accounting must stay centralized behind the projection-stop fence.",
            1,
            Regex("""\.\s*recordCurrentUnmaterializedProductionFrameDrop\s*\(""").findAll(executableContent).count(),
        )
        assertTrue(
            "Frame-rate policy drops must arbitrate raw projection stop before recording a public counter.",
            frameRatePolicy.contains("arbitrateProjectionStopPublicOutcome"),
        )
    }

    @Test
    fun runtimeParameterCommitBridgeStaysNonSuspendingAndAtomicOnly() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val bridgeBody = executableContent.functionBody("commitRuntimeParameterUpdate")
        val bridgeIsSuspending = Regex("""\bsuspend\s+fun\s+commitRuntimeParameterUpdate\s*\(""").containsMatchIn(executableContent)
        val ownerLockEntries = Regex("""\bsynchronized\s*\(\s*lock\s*\)""").findAll(bridgeBody).count()
        val coreCommitEntries = Regex("""\bcommitGate\s*\.\s*commit\s*\{""").findAll(bridgeBody).count()
        val ownerLockIndex = bridgeBody.indexOf("synchronized(lock)")
        val coreCommitIndex = bridgeBody.indexOf("commitGate.commit")
        val violations = runtimeParameterCommitBridgeBoundaryViolations(bridgeBody)

        assertFalse("Runtime parameter bridge must remain a non-suspending function.", bridgeIsSuspending)
        assertEquals("Runtime parameter bridge must enter ActiveRuntimeOwner.lock exactly once.", 1, ownerLockEntries)
        assertEquals("Runtime parameter bridge must enter the core commit gate exactly once.", 1, coreCommitEntries)
        assertTrue(
            "Runtime parameter bridge body may run only its deterministic test seam before owner-lock classification/commit work.",
            Regex(
                """^\s*\{\s*var\s+resumeProductionAdmissionAfterCommit\s*=\s*false\s*""" +
                        """beforeRuntimeParameterCommitOwnerLockForTesting\?\.invoke\s*\(\s*\)\s*""" +
                        """val\s+result\s*=\s*synchronized\s*\(\s*lock\s*\)\s*\{""",
            )
                .containsMatchIn(bridgeBody),
        )
        assertTrue(
            "Runtime parameter bridge must enter ActiveRuntimeOwner.lock before the core commit gate.",
            coreCommitIndex > ownerLockIndex,
        )
        assertTrue(
            "${activeRuntimeOwner.displayPath} runtime parameter bridge crosses the commit boundary: " +
                    violations.joinToString(),
            violations.isEmpty(),
        )
    }

    @Test
    fun runtimeParameterAtomicApplyHelpersStayBoundedAndHeavyWorkFree() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()

        runtimeParameterAtomicApplyHelperNames.forEach { helperName ->
            val helperBody = executableContent.functionBody(helperName)
            val helperIsSuspending = Regex("""\bsuspend\s+fun\s+$helperName\s*\(""")
                .containsMatchIn(executableContent)
            val violations = runtimeParameterAtomicApplyHelperBoundaryViolations(helperBody)

            assertFalse("Runtime parameter atomic helper $helperName must remain non-suspending.", helperIsSuspending)
            assertTrue(
                "${activeRuntimeOwner.displayPath} runtime parameter atomic helper $helperName crosses the " +
                        "bounded commit boundary: ${violations.joinToString()}",
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun materializedRuntimeEncodeUsesCapturedEncoderResourcesNotLiveAccessor() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val drainTick = executableContent.functionBody("drainRuntimeProductionTick")
        val encodeReadback = executableContent.functionBody("encodeReadback")
        val encodeWithTimeout = executableContent.functionBody("encodeWithTimeout")

        assertTrue(
            "Runtime production admission must capture encoder resources with the output generation.",
            drainTick.contains("encoderResources = resources.encoderResourcesForRuntime"),
        )
        assertTrue(
            "RuntimeProductionState must carry attempt-scoped encoder resources.",
            executableContent.contains("val encoderResources: PreparedImageEncoderResources"),
        )
        assertFalse(
            "Materialized encode must not resolve the live current encoder accessor after admission.",
            encodeReadback.contains("encoderResourcesForRuntime") || encodeWithTimeout.contains("encoderResourcesForRuntime"),
        )
    }

    @Test
    fun runtimeEncodeHealthAccountingStaysEncodeOnlyGenerationFencedAndTimeoutIndependent() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val hardFailure = executableContent.functionBody("completeRuntimeEncodeHardFailure")
        val success = executableContent.functionBody("recordRuntimeEncodeSuccessIfCurrent")
        val timeout = executableContent.functionBody("encodeWithTimeout")
        val encodeReadback = executableContent.functionBody("encodeReadback")
        val throwableCatchStart = encodeReadback.indexOf("catch (cause: Throwable)")
        val throwableCatchEnd = encodeReadback.indexOf(
            "if (isRuntimeTerminalOrClosed()",
            startIndex = throwableCatchStart.coerceAtLeast(0),
        )

        assertTrue(throwableCatchStart >= 0)
        assertTrue(throwableCatchEnd > throwableCatchStart)
        val throwableCatch = encodeReadback.substring(throwableCatchStart, throwableCatchEnd)
        assertTrue(throwableCatch.indexOf("val rejected = scratch.wasRejected") < throwableCatch.indexOf("scratch.finishDiscard()"))
        assertTrue(throwableCatch.indexOf("ProductionFrameDropKind.EncodedSizeLimit") < throwableCatch.indexOf("completeRuntimeEncodeHardFailure"))
        assertEquals(3, Regex("""\bcompleteRuntimeEncodeHardFailure\s*\(""").findAll(encodeReadback).count())
        assertEquals(4, Regex("""\bcompleteRuntimeEncodeHardFailure\s*\(""").findAll(executableContent).count())
        assertTrue(hardFailure.contains("attempt.completeDropAndResolve"))
        assertTrue(hardFailure.contains("isCurrentRuntimeProductionLocked(production)"))
        assertTrue(hardFailure.contains("encodeHardFailureTracker.recordHardFailure"))
        assertTrue(hardFailure.contains("runtimeFrameLoop.pauseProductionAdmission"))
        assertTrue(hardFailure.contains("production.core.updateOutputSuspended"))
        assertTrue(success.contains("encodeHardFailureTracker.recordSuccess"))
        assertTrue(
            encodeReadback.indexOf("recordRuntimeEncodeSuccessIfCurrent(production)") <
                    encodeReadback.indexOf("completeEncodedSuccessWithProjectionFence"),
        )
        assertTrue(executableContent.contains("encoderResources === other.encoderResources"))
        assertFalse(hardFailure.contains("ReadbackRepeatedFailure"))
        assertFalse(timeout.contains("encodeHardFailureTracker"))
    }

    @Test
    fun periodicRetentionAndSchedulingRemainGenerationFencedAtClosedCallSites() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val sourceRetention = executableContent.functionBody("rememberPeriodicRefreshFrameIfCurrent")
        val scheduler = executableContent.functionBody("scheduleNextPeriodicRefreshIfNeeded")

        assertEquals(
            2,
            Regex("""\blatestPeriodicRefreshFrame\s*=\s*PeriodicRefreshEncodedFrame\s*\(""")
                .findAll(executableContent)
                .count(),
        )
        assertTrue(sourceRetention.contains("isCurrentRuntimeProductionLocked(production)"))
        assertTrue(executableContent.contains("activeOutputGeneration != frame.generation"))
        assertTrue(executableContent.contains("core.currentOutputGeneration() != frame.generation"))
        assertTrue(executableContent.contains("!isProjectionStoppedLocked()"))
        assertEquals(
            4,
            Regex("""\bscheduleNextPeriodicRefreshIfNeeded\s*\(""").findAll(executableContent).count(),
        )
        assertEquals(1, Regex("""::scheduleNextPeriodicRefreshIfNeeded\b""").findAll(executableContent).count())
        assertFalse(executableContent.contains("scheduleNextPeriodicRefreshIfNeeded(core"))
        assertTrue(scheduler.contains("expectedOutputGeneration"))
        assertTrue(scheduler.contains("isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration)"))
        assertTrue(scheduler.contains("periodicRefreshScheduleToken == scheduleToken"))
        assertTrue(scheduler.contains("periodicRefreshScheduleToken == expectedWakeFenceToken"))
        assertTrue(
            scheduler.indexOf("periodicRefreshScheduleToken == expectedWakeFenceToken") <
                    scheduler.indexOf("runtimeFrameLoop.recordPeriodicRefreshWake()"),
        )
    }

    @Test
    fun fullRuntimeOutputPlanPreparationUsesNeutralFrameFreeNoRetargetBoundary() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val prepare = executableContent.functionBody("prepareFullRuntimeParameterUpdate")
        val begin = executableContent.functionBody("beginFullRuntimeParameterPreparation")
        val commitInstall = executableContent.functionBody("applyRuntimeFullOutputPlanUpdateLocked")
        val preparationPath = prepare + begin + commitInstall

        assertTrue(
            "Full runtime replacement must enter the neutral OutputPlanPreparer facade.",
            prepare.contains("outputPlanPreparer.prepareOutputPlan"),
        )
        assertTrue(
            "Runtime full replacement must preserve the live GL lane on candidate timeout.",
            prepare.contains("abandonGlLaneOnTimeout = false"),
        )
        listOf(
            "updateTexImage",
            "getTransformMatrix",
            "glReadPixels",
            ".encode(",
            "publishEncodedFrame",
            "createTarget(",
            "bindTarget(",
            "createVirtualDisplay(",
            "setDefaultBufferSize(",
            ".resize(",
            ".setSurface(",
        ).forEach { forbidden ->
            assertFalse(
                "Full runtime output-plan preparation must not perform frame/publication/retarget work: $forbidden",
                preparationPath.contains(forbidden),
            )
        }
    }

    @Test
    fun publicStateStatsEventsAndCountersAreNotOwnedInRuntimeFiles() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = publicStateOwnershipPatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }
            val forbiddenMatches = sourceFile.forbiddenPublicStateOwnershipMatches(matches)

            assertFalse(
                "${sourceFile.displayPath} owns public state/stats/events/counters outside ScreenCaptureSessionCore: " +
                        forbiddenMatches.joinToString(),
                forbiddenMatches.isNotEmpty(),
            )
        }
    }

    @Test
    fun startupOnlyGlAccessStaysInPreparationAndHandoffCode() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = startupRenderingGlAccessRegex.findAll(executableContent).toList()

            assertTrue(
                "${sourceFile.displayPath} uses startup-only StartupRenderingGlAccess outside preparation/handoff code: " +
                        matches.joinToString { it.value },
                matches.isEmpty() || sourceFile.runtimeRelativePath in startupRenderingGlAccessAllowlistPaths,
            )
        }
    }

    @Test
    fun defaultEngineAndRuntimeInternalsDoNotDependOnPublicBuiltInMetricsProviderFactories() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = publicBuiltInMetricsProviderPatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertTrue(
                "${sourceFile.displayPath} depends on public built-in CaptureMetricsProviders convenience factories. " +
                        "Default-engine/runtime internals must use caller-supplied CaptureMetricsProvider only: " +
                        matches.joinToString(),
                matches.isEmpty(),
            )
        }
    }

    @Test
    fun mainAndTestSourcesDoNotUseReflectionApis() {
        moduleKotlinSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = reflectionApiUsagePatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertTrue(
                "${sourceFile.displayPath} uses prohibited reflection APIs: ${matches.joinToString()}",
                matches.isEmpty(),
            )
        }
    }

    @Test
    fun mainAndTestSourcesDoNotRegressToRawCaptureMetricsStateFlowContract() {
        moduleKotlinSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = rawCaptureMetricsStateFlowPatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertTrue(
                "${sourceFile.displayPath} uses raw StateFlow<CaptureMetrics> provider contract: " +
                        matches.joinToString(),
                matches.isEmpty(),
            )
        }
    }

    @Test
    fun sanitizerIgnoresRuntimeBoundaryTokensInCommentsAndLiterals() {
        val source = listOf(
            "package synthetic",
            "/** Ignore updateTexImage(), glReadPixels, ImageEncoder.encode, and ScreenCaptureSessionCore. */",
            "internal fun ignoredTokens() {",
            "    // GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)",
            "    /* StartupRenderingGlAccess and MutableStateFlow<ScreenCaptureSessionState> are comments. */",
            "    val escaped = \"publishEncodedFrame(\\\"literal\\\") and surface.updateTexImage()\"",
            "    val raw = \"\"\"ScreenCaptureSessionCore and GLES20.glReadPixels are literal text\"\"\"",
            "    val quote = '\"'",
            "    val slash = '/'",
            "    val dollar = '$'",
            "    val executable = 1",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(executableContent.contains("val executable = 1"))
        listOf(
            "updateTexImage",
            "glReadPixels",
            "ImageEncoder.encode",
            "ScreenCaptureSessionCore",
            "StartupRenderingGlAccess",
            "MutableStateFlow",
            "publishEncodedFrame",
            "GLES20.glDrawArrays",
            "\"literal\"",
            "'\"'",
            "'/'",
            "'$'",
        ).forEach { forbidden ->
            assertFalse(
                "Sanitized executable content retained non-executable token $forbidden",
                executableContent.contains(forbidden),
            )
        }
    }

    @Test
    fun sanitizerPreservesExecutableStringTemplateExpressions() {
        val source = listOf(
            "package synthetic",
            "internal fun catchesForbiddenTemplates(buffer: java.nio.Buffer) {",
            "    val regular = \"literal \${GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val raw = \"\"\"literal \${GLES20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)} text\"\"\"",
            "    val multiLiteral = \$\$\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val multiExecutable = \$\$\"literal \$\${android.opengl.GLES20.glFinish()} text\"",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(executableContent.contains("GLES20.glDrawArrays"))
        assertTrue(executableContent.contains("GLES20.glReadPixels"))
        assertTrue(executableContent.contains("android.opengl.GLES20.glFinish"))
        assertFalse(executableContent.contains("android.opengl.GLES20.glDrawArrays"))
        assertFalse(executableContent.contains("literal"))
    }

    @Test
    fun directGlesGuardCatchesAliasImportedCalls() {
        val executableContent = listOf(
            "package synthetic",
            "import android.opengl.GLES20",
            "import android.opengl.GLES20 as GL",
            "import android.opengl.GLES20.glFinish",
            "import android.opengl.GLES20.glReadPixels as readback",
            "private typealias GL20 = GLES20",
            "private typealias GLFqn = android.opengl.GLES20",
            "internal fun directAliasCall() {",
            "    GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)",
            "    GL20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    readback(0, 0, 1, 1, 0, 0, buffer)",
            "    val draw = GL::glDrawArrays",
            "    val typeAliasDraw = GL20 :: glDrawArrays",
            "    val fqnTypeAliasRead = GLFqn::glReadPixels",
            "    val read = android.opengl.GLES20::glReadPixels",
            "    val aliasRead = ::readback",
            "    val staticFinish = ::glFinish",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        assertTrue(
            "Direct GLES20 class/static/typealias calls and callable references were not caught",
            directGlesUsagesIn(executableContent).map { it.expression }.containsAll(
                listOf(
                    "GL.glDrawArrays(",
                    "GL20.glReadPixels(",
                    "GL::glDrawArrays",
                    "GL20 :: glDrawArrays",
                    "GLFqn::glReadPixels",
                    "::glFinish",
                ),
            ),
        )
    }

    @Test
    fun startupPreparationGuardCatchesCallableReferencesToRuntimeTargetMethods() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun forbidden(target: RuntimeProjectionTargetGlAccess) {",
            "    val update = target::updateTexImage",
            "    val updateWithWhitespace = target :: updateTexImage",
            "    val castUpdate = (target as RuntimeProjectionTargetGlAccess)::updateTexImage",
            "    val matrix = target::getTransformMatrix",
            "    val matrixWithWhitespace = target :: getTransformMatrix",
            "    val castMatrix = (target as RuntimeProjectionTargetGlAccess)::getTransformMatrix",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = startupPreparationForbiddenPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        assertTrue(
            "Startup/preparation guard did not catch updateTexImage callable references.",
            matchedDescriptions.any { description ->
                description.startsWith("SurfaceTexture frame update callable reference")
            },
        )
        assertTrue(
            "Startup/preparation guard did not catch getTransformMatrix callable references.",
            matchedDescriptions.any { description ->
                description.startsWith("SurfaceTexture matrix acquisition callable reference")
            },
        )
    }

    @Test
    fun startupPreparationGuardCatchesSessionDeliveryImportsAndUses() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "internal fun forbidden() {",
            "    val dropKind: DeliveryDropKind? = null",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = startupPreparationForbiddenPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        assertTrue(
            "Startup/preparation guard did not catch session.delivery imports.",
            matchedDescriptions.any { description -> description.startsWith("internal session package import") },
        )
        assertTrue(
            "Startup/preparation guard did not catch fully-qualified session.delivery uses.",
            matchedDescriptions.any { description -> description.startsWith("fully-qualified internal session use") },
        )
    }

    @Test
    fun publicStateStatsEventsGuardCatchesTypealiases() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.ScreenCaptureDeliveryDropStats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureEvent",
            "import dev.dmkr.screencaptureengine.ScreenCaptureFrameDropStats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureStats",
            "import kotlinx.coroutines.flow.MutableSharedFlow",
            "import kotlinx.coroutines.flow.MutableStateFlow",
            "private typealias PublicState<T> = MutableStateFlow<T>",
            "private typealias PublicEvents<T> = MutableSharedFlow<T>",
            "private typealias Stats = ScreenCaptureStats",
            "private typealias FrameDrops = ScreenCaptureFrameDropStats",
            "private typealias DeliveryDrops = ScreenCaptureDeliveryDropStats",
            "private typealias Event = ScreenCaptureEvent",
            "private typealias FqnStats = dev.dmkr.screencaptureengine.ScreenCaptureStats",
            "private typealias FqnEvents<T> = kotlinx.coroutines.flow.MutableSharedFlow<T>",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = publicStateOwnershipPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        listOf(
            "mutable public state flow typealias",
            "mutable public event flow typealias",
            "public stats typealias",
            "public frame-drop stats typealias",
            "public delivery-drop stats typealias",
            "public event typealias",
        ).forEach { expectedDescription ->
            assertTrue(
                "Public state/stats/events guard did not catch $expectedDescription.",
                matchedDescriptions.any { description -> description.startsWith(expectedDescription) },
            )
        }
    }

    @Test
    fun publicStateStatsEventsGuardCatchesCounterOwnershipBypasses() {
        val executableContent = listOf(
            "package synthetic",
            "import java.util.concurrent.atomic.AtomicLong",
            "internal class CounterOwner {",
            "    private var framesEncoded: Long = 0",
            "    private var averageEncodeMs = 0.0",
            "    private val lastEncodedByteCount: AtomicLong = AtomicLong()",
            "    private val averageReadbackMs = java.util.concurrent.atomic.AtomicLong()",
            "    private fun record() {",
            "        framesEncoded++",
            "        ++droppedDeliveries",
            "        framesPublished += 1",
            "        activeFrameSubscriptions -= 1",
            "    }",
            "}",
            "private AtomicLong slowConsumers",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = publicStateOwnershipPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        listOf(
            "public stats counter declaration",
            "public stats atomic counter declaration",
            "public stats counter assignment",
            "public stats counter increment",
        ).forEach { expectedDescription ->
            assertTrue(
                "Public state/stats/events guard did not catch $expectedDescription.",
                matchedDescriptions.any { description -> description.startsWith(expectedDescription) },
            )
        }
    }

    @Test
    fun publicStateStatsEventsGuardDoesNotCatchUnrelatedInternalCounters() {
        val executableContent = listOf(
            "package synthetic",
            "import java.util.concurrent.atomic.AtomicLong",
            "internal class InternalCounterOwner {",
            "    private var retryCount: Long = 0",
            "    private val eventSequence: AtomicLong = AtomicLong()",
            "    private val nextSequence = AtomicLong()",
            "    private fun record() {",
            "        retryCount++",
            "        nextSequence.incrementAndGet()",
            "        eventSequence.addAndGet(1)",
            "    }",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matches = publicStateOwnershipPatterns.flatMap { pattern ->
            pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "Public state/stats/events guard caught unrelated internal counters: ${matches.joinToString()}",
            matches.isEmpty(),
        )
    }

    @Test
    fun boundaryPatternsCatchExecutableForbiddenRuntimeTokens() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionCore",
            "import dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken as AttemptToken",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "import dev.dmkr.screencaptureengine.ScreenCaptureStats as Stats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureFrameDropStats as FrameDrops",
            "import dev.dmkr.screencaptureengine.ScreenCaptureDeliveryDropStats as DeliveryDrops",
            "import dev.dmkr.screencaptureengine.ScreenCaptureEvent as Event",
            "import kotlinx.coroutines.flow.MutableStateFlow as PublicStateFlow",
            "import kotlinx.coroutines.flow.MutableSharedFlow as PublicEventFlow",
            "import kotlinx.coroutines.flow.MutableStateFlow",
            "private typealias StateAlias<T> = MutableStateFlow<T>",
            "private typealias EventFlowAlias<T> = MutableSharedFlow<T>",
            "private typealias StatsAlias = ScreenCaptureStats",
            "private typealias FrameDropsAlias = ScreenCaptureFrameDropStats",
            "private typealias DeliveryDropsAlias = ScreenCaptureDeliveryDropStats",
            "private typealias EventAlias = ScreenCaptureEvent",
            "internal fun forbidden(encoder: ImageEncoder, buffer: java.nio.Buffer) {",
            "    target.updateTexImage()",
            "    target.updateTexImage",
            "        ()",
            "    val update = target::updateTexImage",
            "    target.getTransformMatrix(floatArray)",
            "    target.getTransformMatrix",
            "        (floatArray)",
            "    val matrix = target::getTransformMatrix",
            "    gles.readPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    android.opengl.GLES20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    ImageEncoder.encode",
            "    ImageEncoder::encode",
            "    encoder::encode",
            "    encoder.encode(input, output)",
            "    publishEncodedFrame(frame)",
            "    publishFrame(frame)",
            "    publisher.publish(frame)",
            "    ScreenCaptureSessionCore(config, initialState, updater, trim)",
            "    dev.dmkr.screencaptureengine.internal.encoding.runtime.EncodedAttemptScratch(maxBytes)",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "    val token: AttemptToken? = null",
            "    val dropKind: DeliveryDropKind? = null",
            "    val state = MutableStateFlow(initialState)",
            "    val aliasedState = PublicStateFlow(initialState)",
            "    val events = MutableSharedFlow<ScreenCaptureEvent>()",
            "    val aliasedEvents = PublicEventFlow<Event>()",
            "    val stats = ScreenCaptureStats(framesEncoded = 1L)",
            "    val aliasedStats = Stats(framesPublished = 1L)",
            "    val frameDrops = ScreenCaptureFrameDropStats(total = 1L)",
            "    val aliasedFrameDrops = FrameDrops(total = 1L)",
            "    val deliveryDrops = ScreenCaptureDeliveryDropStats(total = 1L)",
            "    val aliasedDeliveryDrops = DeliveryDrops(total = 1L)",
            "    val event = ScreenCaptureEvent(sequence = 1L)",
            "    val aliasedEvent = Event(sequence = 1L)",
            "    framesEncoded++",
            "    framesPublished += 1",
            "    emitEvent(type)",
            "    StartupRenderingGlAccess",
            "}",
            "private var framesEncoded: Long = 0",
            "private val lastEncodedByteCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()",
            "private java.util.concurrent.atomic.AtomicLong slowConsumers",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        startupPreparationForbiddenPatterns.forEach { pattern ->
            assertTrue(
                "Executable forbidden source should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        publicStateOwnershipPatterns.forEach { pattern ->
            assertTrue(
                "Executable public state ownership should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        assertTrue(directGlesUsagesIn(executableContent).isNotEmpty())
        assertTrue(startupRenderingGlAccessRegex.containsMatchIn(executableContent))
    }

    @Test
    fun publicBuiltInMetricsProviderGuardCatchesExecutableDependencies() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProviders",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProviders as Providers",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProviders.bestEffort",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProviders.fromActivity as metricsFromActivity",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProviders.*",
            "internal fun forbidden(context: android.content.Context, activity: android.app.Activity) {",
            "    CaptureMetricsProviders.bestEffort(context)",
            "    Providers.fromUiContext(context)",
            "    bestEffort(context)",
            "    metricsFromActivity(activity)",
            "    val callable = CaptureMetricsProviders::fromDisplay",
            "    dev.dmkr.screencaptureengine.CaptureMetricsProviders.fromDisplay(context, display)",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        publicBuiltInMetricsProviderPatterns.forEach { pattern ->
            assertTrue(
                "Executable CaptureMetricsProviders dependency should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
    }

    @Test
    fun publicBuiltInMetricsProviderGuardAllowsCallerSuppliedProviderType() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.CaptureMetricsProvider",
            "internal fun allowed(provider: CaptureMetricsProvider) = provider",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matches = publicBuiltInMetricsProviderPatterns.flatMap { pattern ->
            pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "Caller-supplied CaptureMetricsProvider type must remain allowed: ${matches.joinToString()}",
            matches.isEmpty(),
        )
    }

    @Test
    fun runtimeParameterCommitBridgeGuardCatchesForbiddenTokens() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun forbidden() {",
            "    withContext(Dispatchers.Default) {}",
            "    currentCoroutineContext().ensureActive()",
            "    latch.await()",
            "    thread.join()",
            "    future.get()",
            "    LockSupport.parkNanos(1L)",
            "    provider.prepareEncoder()",
            "    ImageEncoderPreparer(context)",
            "    GLES20.glFinish()",
            "    gl.readPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    target.updateTexImage()",
            "    virtualDisplay.resize(1, 1, 1)",
            "    surfaceTexture.setDefaultBufferSize(1, 1)",
            "    resource.close()",
            "    closeRuntimeResources(true)",
            "    harmlessLookingHelper()",
            "    commit()",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        runtimeParameterCommitBridgeForbiddenPatterns.forEach { pattern ->
            assertTrue(
                "Runtime parameter bridge guard did not catch ${pattern.description}.",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        val violations = runtimeParameterCommitBridgeBoundaryViolations(executableContent)
        assertTrue(
            "Runtime parameter bridge guard did not catch unallowlisted helper calls.",
            violations.any { violation -> violation.contains("unallowlisted call: harmlessLookingHelper(") },
        )
        assertTrue(
            "Runtime parameter bridge guard allowed a helper named like an allowed receiver call.",
            violations.any { violation -> violation.contains("unallowlisted call: commit(") },
        )
    }

    @Test
    fun runtimeParameterAtomicApplyHelperGuardCatchesHiddenHeavyWork() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun forbidden() {",
            "    outputPlanPreparer.prepareOutputPlan(request)",
            "    encoderPrepare.prepare(token, provider, request)",
            "    frameRenderer.renderReadback(request)",
            "    target.updateTexImage()",
            "    virtualDisplay.setSurface(surface)",
            "    candidate.close()",
            "    scheduleStartupCleanup(scheduler, sink) {}",
            "    harmlessLookingHelper()",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val violations = runtimeParameterAtomicApplyHelperBoundaryViolations(executableContent)

        assertTrue(violations.any { violation -> violation.contains("output-plan preparation") })
        assertTrue(violations.any { violation -> violation.contains("encoder preparation") })
        assertTrue(violations.any { violation -> violation.contains("render/readback work") })
        assertTrue(violations.any { violation -> violation.contains("frame consumption") })
        assertTrue(violations.any { violation -> violation.contains("retarget platform call") })
        assertTrue(violations.any { violation -> violation.contains("cleanup or close") })
        assertTrue(
            violations.any { violation -> violation.contains("unallowlisted call: harmlessLookingHelper(") },
        )
    }

    @Test
    fun reflectionGuardCatchesActualReflectionApisButAllowsClassLiterals() {
        val executableContent = listOf(
            "package synthetic",
            "import java.lang.reflect.Method",
            "import kotlin.reflect.full.declaredMemberProperties",
            "internal fun forbidden(value: Any) {",
            "    val type = Class.forName(\"ignored.literal\")",
            "    value.javaClass.getDeclaredMethod(\"ignored\")",
            "    value.javaClass.methods",
            "    value.javaClass.declaredMethods",
            "    value.javaClass.declaredConstructors",
            "    value::class.members",
            "    String::class.java.getDeclaredField(\"ignored\")",
            "    String::class.java.fields",
            "    String::class.java.declaredFields",
            "    String::class.java.constructors",
            "    val method: Method? = null",
            "    declaredMemberProperties",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        reflectionApiUsagePatterns.forEach { pattern ->
            assertTrue(
                "Executable reflection API usage should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }

        val allowedContent = listOf(
            "package synthetic",
            "@RunWith(RobolectricTestRunner::class)",
            "@OptIn(ExperimentalCoroutinesApi::class)",
            "internal fun classLiteralsAreAllowed() {",
            "    assertThrows(IllegalStateException::class.java) {}",
            "    val token = Activity::class.java",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()
        val allowedMatches = reflectionApiUsagePatterns.flatMap { pattern ->
            pattern.regex.findAll(allowedContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "Class literals and test annotations must remain allowed: ${allowedMatches.joinToString()}",
            allowedMatches.isEmpty(),
        )
    }

    @Test
    fun rawCaptureMetricsStateFlowGuardCatchesOldProviderContractOnly() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.CaptureMetrics",
            "import kotlinx.coroutines.flow.StateFlow",
            "import kotlinx.coroutines.flow.StateFlow as MetricsFlow",
            "internal interface OldProvider {",
            "    val metrics: StateFlow<CaptureMetrics>",
            "    val spaced: StateFlow < CaptureMetrics >",
            "    val aliased: MetricsFlow<CaptureMetrics>",
            "    val fqn: kotlinx.coroutines.flow.StateFlow<dev.dmkr.screencaptureengine.CaptureMetrics>",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        rawCaptureMetricsStateFlowPatterns.forEach { pattern ->
            assertTrue(
                "Executable raw StateFlow<CaptureMetrics> contract should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }

        val allowedContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.CaptureMetricsState",
            "import kotlinx.coroutines.flow.StateFlow",
            "import kotlinx.coroutines.flow.StateFlow as MetricsFlow",
            "internal interface CurrentProvider {",
            "    val metrics: StateFlow<CaptureMetricsState>",
            "    val aliased: MetricsFlow<CaptureMetricsState>",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()
        val allowedMatches = rawCaptureMetricsStateFlowPatterns.flatMap { pattern ->
            pattern.regex.findAll(allowedContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "StateFlow<CaptureMetricsState> must remain allowed: ${allowedMatches.joinToString()}",
            allowedMatches.isEmpty(),
        )
    }

    private fun runtimeSourceFiles(): List<RuntimeSourceFile> {
        val sourceFiles = discoveredRuntimeSourceFiles()
        val unclassifiedPaths = sourceFiles
            .map { sourceFile -> sourceFile.runtimeRelativePath }
            .filterNot { relativePath -> relativePath in runtimeFileClassifications }

        assertTrue(
            "Runtime boundary guard classifications must cover discovered runtime files: " +
                    unclassifiedPaths.sorted().joinToString(),
            unclassifiedPaths.isEmpty(),
        )

        return sourceFiles
    }

    private fun moduleKotlinSourceFiles(): List<ModuleSourceFile> =
        listOf("src/main/java", "src/test/java").flatMap { sourceRootRelativePath ->
            kotlinSourceFilesUnder(sourceRootRelativePath)
        }

    private fun kotlinSourceFilesUnder(sourceRootRelativePath: String): List<ModuleSourceFile> {
        val moduleRoot = resolveModuleRoot()
        val sourceRoot = moduleRoot.resolve(sourceRootRelativePath)
        assertTrue("Source root is missing: $sourceRoot", Files.isDirectory(sourceRoot))

        val paths = mutableListOf<Path>()
        val stream = Files.walk(sourceRoot)
        try {
            stream
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.fileName.toString().endsWith(".kt") }
                .forEach { path -> paths.add(path) }
        } finally {
            stream.close()
        }

        return paths.sorted().map { path ->
            ModuleSourceFile(
                path = path,
                displayPath = moduleRoot.relativize(path).toDisplayPath(),
            )
        }
    }

    private fun discoveredRuntimeSourceFiles(): List<RuntimeSourceFile> {
        val sourceRoot = resolveSourceRoot()
        val runtimeRoot = sourceRoot.resolve(runtimePackagePath)
        assertTrue("Runtime source package is missing: $runtimeRoot", Files.isDirectory(runtimeRoot))

        val paths = mutableListOf<Path>()
        val stream = Files.walk(runtimeRoot)
        try {
            stream
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.fileName.toString().endsWith(".kt") }
                .forEach { path -> paths.add(path) }
        } finally {
            stream.close()
        }

        return paths.sorted().map { path ->
            val relativePath = runtimeRoot.relativize(path).toDisplayPath()
            RuntimeSourceFile(
                path = path,
                runtimeRelativePath = relativePath,
                displayPath = sourceRoot.relativize(path).toDisplayPath(),
            )
        }
    }

    private fun resolveSourceRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(start) { path -> path.parent }
            .mapNotNull { root ->
                when {
                    Files.isDirectory(root.resolve("screencaptureengine/src/main/java")) ->
                        root.resolve("screencaptureengine/src/main/java")

                    Files.isDirectory(root.resolve("src/main/java")) -> root.resolve("src/main/java")
                    else -> null
                }
            }
            .firstOrNull()
            ?: error("Could not resolve screencaptureengine source root from $start.")
    }

    private fun resolveModuleRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(start) { path -> path.parent }
            .firstOrNull { root ->
                Files.isDirectory(root.resolve("screencaptureengine/src/main/java")) ||
                        Files.isDirectory(root.resolve("src/main/java"))
            }
            ?.let { root ->
                if (Files.isDirectory(root.resolve("screencaptureengine/src/main/java"))) {
                    root.resolve("screencaptureengine")
                } else {
                    root
                }
            }
            ?: error("Could not resolve screencaptureengine module root from $start.")
    }

    private fun Path.toDisplayPath(): String =
        joinToString(separator = "/") { path -> path.toString() }

    private fun RuntimeSourceFile.allowsDirectGlesUsage(usage: DirectGlesUsage): Boolean =
        when (runtimeRelativePath) {
            in broadDirectGlesAdapterPaths -> true
            "target/ProjectionTargetOwner.kt" -> usage.callName in projectionTargetOwnerDirectGlesCallNames
            else -> false
        }

    private fun RuntimeSourceFile.forbiddenPublicStateOwnershipMatches(matches: List<String>): List<String> =
        when (runtimeRelativePath) {
            in publicStateOwnershipOwnerPaths -> emptyList()
            "platform/metrics/AndroidCaptureMetricsProvider.kt" ->
                matches.withSingleProviderMetricsStateFlowAllowed()

            "session/delivery/ScreenCaptureFrameDeliveryCoordinator.kt" -> matches.filterNot { match ->
                deliveryCoordinatorInternalSubscriptionStatsRegex.containsMatchIn(match)
            }

            else -> matches
        }

    private fun List<String>.withSingleProviderMetricsStateFlowAllowed(): List<String> {
        val providerMetricsStateFlowMatches = filter { match ->
            providerMetricsStateFlowRegex.matches(match)
        }
        if (providerMetricsStateFlowMatches.size != 1) return this
        return this - providerMetricsStateFlowMatches.toSet()
    }

    private fun directGlesUsagesIn(executableContent: String): List<DirectGlesUsage> =
        buildList {
            directGlesCallRegex(executableContent).findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            directGlesCallableReferenceRegex(executableContent).findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            bareDirectGlCallRegex.findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            addAll(directGlesStaticImportAliasUsagesIn(executableContent))
            addAll(directGlesStaticImportAliasCallableReferencesIn(executableContent))
        }

    private fun directGlesCallRegex(executableContent: String): Regex {
        val referencePattern = directGlesReferencePattern(executableContent)
        return Regex("""(?<![.\w])(?:$referencePattern)\.(gl[A-Z]\w*)\s*\(""")
    }

    private fun directGlesCallableReferenceRegex(executableContent: String): Regex {
        val referencePattern = directGlesReferencePattern(executableContent)
        return Regex("""(?<![.\w])(?:$referencePattern)\s*::\s*(gl[A-Z]\w*)\b""")
    }

    private fun directGlesReferencePattern(executableContent: String): String =
        directGlesReferences(executableContent).joinToString(separator = "|") { reference ->
            Regex.escape(reference)
        }

    private fun directGlesReferences(executableContent: String): List<String> =
        buildSet {
            add("android.opengl.GLES20")
            add("android.opengl.GLES30")
            add("android.opengl.GLES31")
            add("android.opengl.GLES32")
            add("GLES20")
            add("GLES30")
            add("GLES31")
            add("GLES32")
            Regex("""\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(executableContent)
                .map { match -> match.groupValues[1] }
                .forEach(::add)
            var addedAlias: Boolean
            do {
                addedAlias = false
                Regex(
                    """\btypealias\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*""" +
                            """((?:android\.opengl\.)?(?:GLES20|GLES30|GLES31|GLES32)|[A-Za-z_][A-Za-z0-9_]*)\b""",
                )
                    .findAll(executableContent)
                    .forEach { match ->
                        val alias = match.groupValues[1]
                        val aliasedReference = match.groupValues[2]
                        if ((aliasedReference in this) && add(alias)) {
                            addedAlias = true
                        }
                    }
            } while (addedAlias)
        }.toList()

    private fun directGlesStaticImportAliasUsagesIn(executableContent: String): List<DirectGlesUsage> {
        val aliasesByCallName = directGlesStaticImportAliasesByCallName(executableContent)

        if (aliasesByCallName.isEmpty()) return emptyList()

        val aliasPattern = aliasesByCallName.keys.joinToString(separator = "|") { alias -> Regex.escape(alias) }
        return Regex("""(?<![.\w])($aliasPattern)\s*\(""")
            .findAll(executableContent)
            .map { match ->
                DirectGlesUsage(
                    callName = aliasesByCallName.getValue(match.groupValues[1]),
                    expression = match.value,
                )
            }
            .toList()
    }

    private fun directGlesStaticImportAliasCallableReferencesIn(executableContent: String): List<DirectGlesUsage> {
        val aliasesByCallName = directGlesStaticImportAliasesByCallName(executableContent)

        if (aliasesByCallName.isEmpty()) return emptyList()

        val aliasPattern = aliasesByCallName.keys.joinToString(separator = "|") { alias -> Regex.escape(alias) }
        return Regex("""(?<![.\w])::\s*($aliasPattern)\b""")
            .findAll(executableContent)
            .map { match ->
                DirectGlesUsage(
                    callName = aliasesByCallName.getValue(match.groupValues[1]),
                    expression = match.value,
                )
            }
            .toList()
    }

    private fun directGlesStaticImportAliasesByCallName(executableContent: String): Map<String, String> =
        buildMap {
            Regex("""\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\.(gl[A-Z]\w*)\b(?!\s+as\b)""")
                .findAll(executableContent)
                .forEach { match -> put(match.groupValues[1], match.groupValues[1]) }
            Regex(
                """\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\.(gl[A-Z]\w*)\s+as\s+""" +
                        """([A-Za-z_][A-Za-z0-9_]*)""",
            )
                .findAll(executableContent)
                .forEach { match -> put(match.groupValues[2], match.groupValues[1]) }
        }

    private fun String.withoutKotlinCommentsAndStrings(): String {
        val stripped = StringBuilder(length)

        fun appendWhitespace(start: Int, endExclusive: Int) {
            repeat(endExclusive - start) {
                stripped.append(' ')
            }
        }

        fun appendLineComment(start: Int): Int {
            val end = indexOf('\n', startIndex = start).let { if (it < 0) length else it }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendBlockComment(start: Int): Int {
            var end = start + 2
            var depth = 1
            while ((end < length) && (depth > 0)) {
                when {
                    startsWith("/*", startIndex = end) -> {
                        depth += 1
                        end += 2
                    }

                    startsWith("*/", startIndex = end) -> {
                        depth -= 1
                        end += 2
                    }

                    else -> end += 1
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendCharLiteral(start: Int): Int {
            var end = start + 1
            var escaped = false
            while (end < length) {
                val char = this[end]
                end += 1
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '\'') {
                    break
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        lateinit var appendCode: (start: Int, endExclusive: Int, stopAtTemplateEnd: Boolean) -> Int

        fun dollarRunEnd(start: Int, endExclusive: Int): Int {
            var end = start
            while ((end < endExclusive) && (this[end] == '$')) {
                end += 1
            }
            return end
        }

        fun templateExpressionMarkerEnd(start: Int, endExclusive: Int, interpolationThreshold: Int): Int? {
            if (this[start] != '$') return null
            val dollarEnd = dollarRunEnd(start = start, endExclusive = endExclusive)
            return if (
                (dollarEnd < endExclusive) &&
                (this[dollarEnd] == '{') &&
                (dollarEnd - start >= interpolationThreshold)
            ) {
                dollarEnd + 1
            } else {
                null
            }
        }

        fun appendTemplateExpression(start: Int, markerEndExclusive: Int, endExclusive: Int): Int {
            appendWhitespace(start = start, endExclusive = markerEndExclusive)
            return appendCode(markerEndExclusive, endExclusive, true)
        }

        fun appendRegularString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 1
            appendWhitespace(start = start, endExclusive = current)
            var escaped = false
            while (current < endExclusive) {
                val markerEnd = if (!escaped) {
                    templateExpressionMarkerEnd(
                        start = current,
                        endExclusive = endExclusive,
                        interpolationThreshold = interpolationThreshold,
                    )
                } else {
                    null
                }
                when {
                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        val char = this[current]
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                        if (escaped) {
                            escaped = false
                        } else if (char == '\\') {
                            escaped = true
                        } else if (char == '"') {
                            return current
                        }
                    }
                }
            }
            return current
        }

        fun appendRawString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 3
            appendWhitespace(start = start, endExclusive = current)
            while (current < endExclusive) {
                val markerEnd = templateExpressionMarkerEnd(
                    start = current,
                    endExclusive = endExclusive,
                    interpolationThreshold = interpolationThreshold,
                )
                when {
                    startsWith("\"\"\"", startIndex = current) -> {
                        appendWhitespace(start = current, endExclusive = current + 3)
                        return current + 3
                    }

                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                    }
                }
            }
            return current
        }

        appendCode = appendCode@{ start, endExclusive, stopAtTemplateEnd ->
            var current = start
            var nestedBraceDepth = 0
            while (current < endExclusive) {
                when {
                    stopAtTemplateEnd && (this[current] == '}') && (nestedBraceDepth == 0) -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        return@appendCode current + 1
                    }

                    startsWith("//", startIndex = current) -> {
                        current = appendLineComment(start = current)
                    }

                    startsWith("/*", startIndex = current) -> {
                        current = appendBlockComment(start = current)
                    }

                    startsWith("\"\"\"", startIndex = current) -> {
                        current = appendRawString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '"' -> {
                        current = appendRegularString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '\'' -> {
                        current = appendCharLiteral(start = current)
                    }

                    this[current] == '$' -> {
                        val dollarEnd = dollarRunEnd(start = current, endExclusive = endExclusive)
                        current = when {
                            startsWith("\"\"\"", startIndex = dollarEnd) -> appendRawString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            (dollarEnd < endExclusive) && (this[dollarEnd] == '"') -> appendRegularString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            else -> {
                                stripped.append(this[current])
                                current + 1
                            }
                        }
                    }

                    stopAtTemplateEnd && (this[current] == '{') -> {
                        stripped.append(this[current])
                        nestedBraceDepth += 1
                        current += 1
                    }

                    stopAtTemplateEnd && (this[current] == '}') -> {
                        stripped.append(this[current])
                        nestedBraceDepth -= 1
                        current += 1
                    }

                    else -> {
                        stripped.append(this[current])
                        current += 1
                    }
                }
            }
            current
        }

        appendCode(0, length, false)
        return stripped.toString()
    }

    private fun String.functionBody(functionName: String): String {
        val functionMatch = Regex("""\bfun\s+$functionName\s*\(""").find(this)
            ?: error("Function $functionName was not found.")
        val bodyStart = indexOf('{', startIndex = functionMatch.range.last)
        check(bodyStart >= 0) { "Function $functionName has no block body." }
        var depth = 0
        var index = bodyStart
        while (index < length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
            index += 1
        }
        error("Function $functionName body is not closed.")
    }

    private fun runtimeParameterCommitBridgeBoundaryViolations(bridgeBody: String): List<String> {
        val forbiddenMatches = runtimeParameterCommitBridgeForbiddenPatterns.flatMap { pattern ->
            pattern.regex.findAll(bridgeBody).map { match -> "${pattern.description}: ${match.value}" }
        }
        val unallowlistedCalls = executableCallsIn(bridgeBody)
            .filterNot { call -> call in runtimeParameterCommitBridgeAllowedCalls }
            .map { call -> "unallowlisted call: $call(" }

        return forbiddenMatches + unallowlistedCalls
    }

    private fun runtimeParameterAtomicApplyHelperBoundaryViolations(helperBody: String): List<String> {
        val forbiddenMatches = runtimeParameterAtomicApplyHelperForbiddenPatterns.flatMap { pattern ->
            pattern.regex.findAll(helperBody).map { match -> "${pattern.description}: ${match.value}" }
        }
        val unallowlistedCalls = executableCallsIn(helperBody)
            .filterNot { call -> call in runtimeParameterAtomicApplyHelperAllowedCalls }
            .map { call -> "unallowlisted call: $call(" }

        return forbiddenMatches + unallowlistedCalls
    }

    private fun executableCallsIn(executableContent: String): List<String> =
        Regex("""(?<![\w.])((?:[A-Za-z_][A-Za-z0-9_]*\s*(?:\?\.|\.)\s*)?[A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|\{)""")
            .findAll(executableContent)
            .map { match -> match.groupValues[1].replace(Regex("""\s+"""), "") }
            .filterNot { call -> call in kotlinControlCallNames }
            .toList()

    private data class BoundaryPattern(
        val description: String,
        val regex: Regex,
    )

    private data class RuntimeSourceFile(
        val path: Path,
        val runtimeRelativePath: String,
        val displayPath: String,
    ) {
        val role: RuntimeFileRole
            get() = runtimeFileClassifications.getValue(runtimeRelativePath)
    }

    private data class ModuleSourceFile(
        val path: Path,
        val displayPath: String,
    )

    private data class DirectGlesUsage(
        val callName: String,
        val expression: String,
    )

    @Suppress("unused") // Entries are consumed as classification values in the guards above.
    private enum class RuntimeFileRole {
        ActiveSessionIntegration,
        DormantFoundation,
        DormantMechanicalPlatformLeaf,
        PlatformLifecycleSupport,
        ProjectionTargetOwner,
        RuntimeProduction,
        SharedGlInfrastructure,
        StartupPreparation,
        StartupRuntimeHandoff,
    }

    private companion object {
        private val runtimePackagePath = Path.of("dev/dmkr/screencaptureengine/internal")

        private val startupPreparationRoles = setOf(
            RuntimeFileRole.StartupPreparation,
            RuntimeFileRole.StartupRuntimeHandoff,
        )

        private val dormantFoundationPaths = setOf(
            "control/ControllerEvidence.kt",
            "control/ControllerIdentity.kt",
            "control/ControllerIngress.kt",
            "control/CommittedRevisions.kt",
            "control/ControlSequenceAllocator.kt",
            "control/PacingState.kt",
            "control/ParameterTransaction.kt",
            "control/ProductionAttemptOutcomeSlot.kt",
            "control/ReconfigurationArbiter.kt",
            "control/TargetRetrySchedule.kt",
            "control/ControllerSnapshot.kt",
            "control/ControllerSnapshotStore.kt",
            "control/ControllerTargetSnapshot.kt",
            "control/ControllerDirectFact.kt",
            "control/ControllerGeometryAccumulator.kt",
            "control/ControllerState.kt",
            "control/ControllerPreparedTurn.kt",
            "diagnostics/DiagnosticMessageSanitizer.kt",
            "encoding/DescriptorSyntax.kt",
            "encoding/LiveProviderDescriptorLedger.kt",
            "encoding/storage/ImmutableEncodedPayload.kt",
            "encoding/storage/LatestEncodedPayloadSlot.kt",
            "encoding/storage/SegmentedEncodedSink.kt",
            "operation/OperationRecord.kt",
            "operation/UnresolvedPredecessorLedger.kt",
            "operation/WorkIdentity.kt",
            "planning/BaselineOutputPlan.kt",
            "planning/BaselineOutputPlanner.kt",
            "planning/CheckedArithmetic.kt",
            "planning/MemoryPlanning.kt",
            "planning/ShaderPrecisionBounds.kt",
            "policy/ScreenCaptureEnginePolicyDefaults.kt",
        )

        private val dormantMechanicalPlatformLeafPaths = setOf(
            "EngineAdmission.kt",
            "platform/metrics/SessionMetricsCollector.kt",
            "platform/metrics/SessionMetricsFact.kt",
        )

        private val dormantSnapshotBoundaryNames = setOf(
            "NormalizedOutputValues",
            "NormalizedSourceRegion",
            "NormalizedCrop",
            "NormalizedOutputSize",
            "NormalizedContentMode",
            "NormalizedRotation",
            "NormalizedMirror",
            "NormalizedColorMode",
            "NormalizedFrameRate",
            "ControllerProviderReference",
            "ControllerDesiredSnapshot",
            "ControllerCandidateSnapshot",
            "ControllerEffectiveSnapshot",
            "ControllerParameterClassification",
            "ControllerParameterClassifier",
            "ControllerTargetSnapshot",
            "TargetAssignmentEvidence",
            "TargetHealthEvidence",
            "TargetRetention",
            "TargetReplacementReason",
            "ControllerCandidateOwnership",
            "ControllerCandidatePrevalidation",
            "ControllerCurrentCandidatePrevalidation",
            "CandidateDispositionTrigger",
            "CandidateDispositionAction",
            "ControllerCandidateDispositionPrevalidation",
            "ControllerTerminalCandidateDispositionPrevalidation",
            "ControllerCandidateCommitPrevalidation",
            "ControllerCleanupOwnership",
            "ControllerPreparationQuarantineOwnership",
            "CandidateOwnershipAdmission",
            "CandidateCommitDisposition",
            "QuarantineReturnDisposition",
            "CleanupTransitionDisposition",
            "ActiveOwnerReturnDisposition",
            "TerminalOwnershipDisposition",
            "CandidateDispositionOutcome",
            "ControllerTargetAcknowledgementPrevalidation",
            "TargetAcknowledgementDisposition",
            "ControllerSnapshotOwnershipView",
            "ControllerSnapshotStore",
        )

        private val dormantTypedLedgerNames = setOf(
            "ProviderDescriptorRetentionRole",
            "ProviderDescriptorRetentionToken",
            "ProviderDescriptorReserveResult",
            "ProviderDescriptorForkResult",
            "ProviderDescriptorRecordResult",
            "LiveProviderDescriptorSnapshot",
            "LiveProviderDescriptorLedger",
        )

        private val controllerProtocolPaths = setOf(
            "control/ControllerEvidence.kt",
            "control/ControllerIdentity.kt",
            "control/ControllerIngress.kt",
            "control/ParameterTransaction.kt",
            "control/ReconfigurationArbiter.kt",
            "control/ControllerDirectFact.kt",
            "control/ControllerGeometryAccumulator.kt",
            "control/ControllerState.kt",
            "control/ControllerPreparedTurn.kt",
        )

        private val dormantPlanningStoragePaths = setOf(
            "encoding/storage/ImmutableEncodedPayload.kt",
            "encoding/storage/LatestEncodedPayloadSlot.kt",
            "encoding/storage/SegmentedEncodedSink.kt",
            "planning/BaselineOutputPlan.kt",
            "planning/BaselineOutputPlanner.kt",
            "planning/MemoryPlanning.kt",
            "planning/ShaderPrecisionBounds.kt",
        )

        private val dormantNewPlanningPaths = setOf(
            "planning/BaselineOutputPlan.kt",
            "planning/BaselineOutputPlanner.kt",
            "planning/CheckedArithmetic.kt",
            "planning/MemoryPlanning.kt",
            "planning/ShaderPrecisionBounds.kt",
        )

        private val legacyPlanningPaths = setOf(
            "planning/RuntimeParameterUpdateClassifier.kt",
            "planning/ScreenCaptureOutputPlanner.kt",
        )

        private val dormantFoundationAllowedPublicImports = mapOf(
            "control/ControllerSnapshot.kt" to setOf(
                "import dev.dmkr.screencaptureengine.ColorMode",
                "import dev.dmkr.screencaptureengine.ContentMode",
                "import dev.dmkr.screencaptureengine.CropInsetsPx",
                "import dev.dmkr.screencaptureengine.FrameRate",
                "import dev.dmkr.screencaptureengine.ImageEncoderProvider",
                "import dev.dmkr.screencaptureengine.Mirror",
                "import dev.dmkr.screencaptureengine.OutputSize",
                "import dev.dmkr.screencaptureengine.Rotation",
                "import dev.dmkr.screencaptureengine.ScreenCaptureParameters",
                "import dev.dmkr.screencaptureengine.SourceRegion",
            ),
            "control/ControllerTargetSnapshot.kt" to setOf(
                "import dev.dmkr.screencaptureengine.Size",
            ),
            "encoding/storage/SegmentedEncodedSink.kt" to setOf(
                "import dev.dmkr.screencaptureengine.EncodedImageSink",
            ),
            "planning/BaselineOutputPlan.kt" to setOf(
                "import dev.dmkr.screencaptureengine.ImageRect",
                "import dev.dmkr.screencaptureengine.Size",
            ),
            "planning/BaselineOutputPlanner.kt" to setOf(
                "import dev.dmkr.screencaptureengine.ContentMode",
                "import dev.dmkr.screencaptureengine.CropInsetsPx",
                "import dev.dmkr.screencaptureengine.ImageRect",
                "import dev.dmkr.screencaptureengine.Rotation",
                "import dev.dmkr.screencaptureengine.Size",
                "import dev.dmkr.screencaptureengine.SourceRegion",
            ),
        )

        private val newPlanningSimpleNamePattern = Regex(
            """\b(?:BaselineOutputPlan|SamplingDemand|PositiveRatio|BaselineOutputPlanFact|""" +
                    """RequestNonrepresentability|BaselineDeviceLimits|BaselineCapabilityFact|""" +
                    """BaselineDeviceLimit|BaselineOutputPlanner|CheckedArithmetic|CheckedLongFact|""" +
                    """CheckedIntFact|MemoryFootprint|ReplacementMemoryEstimate|MemoryRuntimeEvidence|""" +
                    """MemoryHeadroom|MemoryFootprintFact|ReplacementMemoryFact|MemoryHeadroomFact|""" +
                    """MemoryAdmissionFact|MemoryAdmissionDenial|MemoryPlanning|CoordinatePrecisionEvidence|""" +
                    """ShaderPrecisionEvidence|ShaderPrecisionErrorBounds|ShaderPrecisionFact|""" +
                    """ShaderPrecisionBounds)\b""",
        )

        private val legacyPlanningSimpleNamePattern = Regex(
            """\b(?:ScreenCaptureOutputPlanner|RuntimeParameterUpdateClassifier)\b""",
        )

        private val publicRootImportPattern = Regex(
            """(?m)^\s*import\s+dev\.dmkr\.screencaptureengine\.(?!internal(?:\.|\b))[^\r\n]+""",
        )

        private val dormantPlanningStorageForbiddenPatterns = listOf(
            BoundaryPattern(
                description = "old or future active authority",
                regex = Regex(
                    """\bdev\.dmkr\.screencaptureengine\.internal\.""" +
                            """(?:lifecycle|startup|session|target|gl|rendering|platform|runtime|control|""" +
                            """encoding\.(?:provider|runtime))(?:\.|\b)""",
                ),
            ),
        )

        private val dormantPlanningToStoragePattern = BoundaryPattern(
            description = "planning-to-storage coupling",
            regex = Regex(
                """\bdev\.dmkr\.screencaptureengine\.internal\.encoding\.storage(?:\.|\b)""",
            ),
        )

        private val dormantStorageToPlanningPattern = BoundaryPattern(
            description = "storage-to-planning coupling other than checked arithmetic",
            regex = Regex(
                """\bdev\.dmkr\.screencaptureengine\.internal\.planning\.(?!""" +
                        """CheckedArithmetic|CheckedLongFact|CheckedIntFact\b)""",
            ),
        )

        private val dormantBaselineOptionalMechanismPattern = Regex(
            """\b(?:PBO|Pbo|NativeJpeg|EarlyDownscale|DownscaledTarget|PreferredTarget)\w*\b""",
        )

        private val dormantFoundationForbiddenAuthorityPatterns = listOf(
            BoundaryPattern(
                description = "old lifecycle/startup/session authority",
                regex = Regex(
                    """\bdev\.dmkr\.screencaptureengine\.internal\.""" +
                            """(?:lifecycle|startup|session)(?:\.|\b)""",
                ),
            ),
            BoundaryPattern(
                description = "public engine authority",
                regex = Regex(
                    """\bdev\.dmkr\.screencaptureengine\.(?!internal(?:\.|\b))(?:[A-Za-z_]|\*)""",
                ),
            ),
        )

        private val controllerProtocolForbiddenDependencyPatterns = listOf(
            BoundaryPattern(
                description = "controller protocol to facility or non-control implementation",
                regex = Regex(
                    """\bdev\.dmkr\.screencaptureengine\.internal\.""" +
                            """(?:diagnostics|encoding|gl|lifecycle|operation|planning|platform|policy|""" +
                            """rendering|runtime|session|startup|target)(?:\.|\b)""",
                ),
            ),
            BoundaryPattern(
                description = "controller protocol to Android, coroutine/Flow, or byte storage",
                regex = Regex(
                    """\b(?:android(?:\.|\b)|kotlinx\.coroutines(?:\.|\b)|java\.nio(?:\.|\b)|""" +
                            """ByteArray\b|ByteBuffer\b|StateFlow\b|SharedFlow\b)""",
                ),
            ),
        )

        private val dormantFoundationReferencePattern = Regex(
            """\bdev\.dmkr\.screencaptureengine\.internal\.(?:""" +
                    """control(?:\.|\b)|diagnostics(?:\.|\b)|operation(?:\.|\b)|policy(?:\.|\b)|""" +
                    """encoding\.(?:\*|DescriptorSyntax|EncodedFormatDescriptorSnapshot|""" +
                    """ProviderDescriptorSnapshot|LiveProviderDescriptor(?:Ledger|Snapshot)|""" +
                    """ProviderDescriptor(?:RetentionRole|RetentionToken|ReserveResult|ForkResult|RecordResult))|""" +
                    """planning\.(?:\*|CheckedArithmetic|CheckedLongFact|CheckedIntFact|""" +
                    """Baseline\w+|SamplingDemand|PositiveRatio|RequestNonrepresentability|""" +
                    """Memory\w+|ReplacementMemory\w+|ShaderPrecision\w+|""" +
                    """CoordinatePrecisionEvidence)|""" +
                    """encoding\.storage(?:\.|\b))""",
        )

        private val runtimeFileClassifications = mapOf(
            "DefaultScreenCaptureEngine.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "encoding/jpeg/FrameworkJpegEncoder.kt" to RuntimeFileRole.RuntimeProduction,
            "encoding/runtime/EncodedAttemptScratch.kt" to RuntimeFileRole.RuntimeProduction,
            "lifecycle/ActiveRuntimeOwner.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "lifecycle/RuntimeEncodeHardFailureTracker.kt" to RuntimeFileRole.RuntimeProduction,
            "platform/metrics/AndroidCaptureMetricsProvider.kt" to RuntimeFileRole.PlatformLifecycleSupport,
            "platform/metrics/CaptureMetricsObservation.kt" to RuntimeFileRole.StartupPreparation,
            "gl/CleanupFailure.kt" to RuntimeFileRole.PlatformLifecycleSupport,
            "rendering/es2/Es2RenderingPipelinePreparer.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/Es2RenderingReadbackResourcePreparer.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/FirstPlanRenderTransformPackage.kt" to RuntimeFileRole.StartupPreparation,
            "gl/Gles20Api.kt" to RuntimeFileRole.StartupPreparation,
            "gl/GlLaneContextOwner.kt" to RuntimeFileRole.SharedGlInfrastructure,
            "encoding/provider/ImageEncoderPreparer.kt" to RuntimeFileRole.StartupPreparation,
            "lifecycle/InitialRuntimeHandoffGate.kt" to RuntimeFileRole.StartupRuntimeHandoff,
            "lifecycle/InitialRuntimeResourceOwner.kt" to RuntimeFileRole.StartupRuntimeHandoff,
            "platform/projection/MediaProjectionCallbackAdapter.kt" to RuntimeFileRole.PlatformLifecycleSupport,
            "lifecycle/PlanPreparationToken.kt" to RuntimeFileRole.StartupPreparation,
            "lifecycle/PreActiveRuntimeOwner.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/PreparedEs2RenderingReadbackResources.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/pipeline/OutputPlanPreparation.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/pipeline/PreparedRenderingPipelineResources.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/ProjectionStopArbiter.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "target/ProjectionTargetOwner.kt" to RuntimeFileRole.ProjectionTargetOwner,
            "target/ProjectionTargetRuntimeExtensions.kt" to RuntimeFileRole.RuntimeProduction,
            "encoding/provider/ProviderPreparationContext.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/pipeline/RenderingPipelinePreparationResult.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/RuntimeEs2FrameRenderer.kt" to RuntimeFileRole.RuntimeProduction,
            "lifecycle/RuntimeFrameLoop.kt" to RuntimeFileRole.RuntimeProduction,
            "lifecycle/RuntimeFrameSignalSource.kt" to RuntimeFileRole.RuntimeProduction,
            "gl/RuntimeGles20Api.kt" to RuntimeFileRole.RuntimeProduction,
            "target/RuntimeProjectionTargetGlAccess.kt" to RuntimeFileRole.RuntimeProduction,
            "startup/ScreenCaptureStartupResources.kt" to RuntimeFileRole.StartupPreparation,
            "startup/ScreenCaptureStartupTransaction.kt" to RuntimeFileRole.StartupPreparation,
            "gl/StartupCleanup.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupFactories.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupGeometryArbiter.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/StartupProjectionCallbackRouter.kt" to RuntimeFileRole.StartupPreparation,
            "target/StartupRenderingGlAccess.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/StartupResourceFacades.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupRuntimeSignalMailbox.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/VirtualDisplayOwner.kt" to RuntimeFileRole.StartupPreparation,
            "planning/RuntimeParameterUpdateClassifier.kt" to RuntimeFileRole.StartupPreparation,
            "planning/ScreenCaptureOutputPlanner.kt" to RuntimeFileRole.StartupPreparation,
            "session/core/ScreenCaptureSessionCore.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "session/delivery/ScreenCaptureFrameDeliveryCoordinator.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "session/delivery/ScreenCaptureFrameDeliveryDispatcher.kt" to RuntimeFileRole.ActiveSessionIntegration,
        ) + dormantFoundationPaths.associateWith { RuntimeFileRole.DormantFoundation } +
                dormantMechanicalPlatformLeafPaths.associateWith { RuntimeFileRole.DormantMechanicalPlatformLeaf }

        private val broadDirectGlesAdapterPaths = setOf(
            "gl/Gles20Api.kt",
            "gl/GlLaneContextOwner.kt",
            "gl/RuntimeGles20Api.kt",
        )

        private val projectionTargetOwnerDirectGlesCallNames = setOf(
            "glBindTexture",
            "glDeleteTextures",
            "glGenTextures",
            "glTexParameteri",
        )

        private val projectionTargetOwnerDirectGlesCallCounts = mapOf(
            "glBindTexture" to 5,
            "glDeleteTextures" to 1,
            "glGenTextures" to 1,
            "glTexParameteri" to 4,
        )

        private val sessionCoreIntegrationOwnerPaths = setOf(
            "lifecycle/ActiveRuntimeOwner.kt",
            "session/core/ScreenCaptureSessionCore.kt",
            "InitialActiveSessionOwner.kt",
            "InitialActiveSessionCommitter.kt",
            "InitialActivationCommitter.kt",
            "RuntimeSessionOwner.kt",
            "RuntimeSessionIntegration.kt",
        )

        private val publicStateOwnershipOwnerPaths = setOf(
            "session/core/ScreenCaptureSessionCore.kt",
        )

        private val deliveryCoordinatorInternalSubscriptionStatsRegex = Regex(
            """: (?:val )?(?:activeFrameSubscriptions|slowConsumers)(?:\s*=|:)""",
        )

        private val providerMetricsStateFlowRegex = Regex("""^mutable public state flow: .*: MutableStateFlow\($""")

        private val startupRenderingGlAccessAllowlistPaths = setOf(
            "rendering/es2/Es2RenderingPipelinePreparer.kt",
            "lifecycle/InitialRuntimeHandoffGate.kt",
            "lifecycle/PreActiveRuntimeOwner.kt",
            "rendering/pipeline/OutputPlanPreparation.kt",
            "rendering/pipeline/PreparedRenderingPipelineResources.kt",
            "target/ProjectionTargetOwner.kt",
            "target/StartupRenderingGlAccess.kt",
            "platform/projection/StartupResourceFacades.kt",
        )

        private val internalSessionProductionTypeNames = setOf(
            "EncodedAttemptScratch",
            "ProductionAttemptToken",
            "ScreenCaptureSessionCore",
        )

        private val publicStateFlowTypeNames = setOf("MutableStateFlow")

        private val publicEventFlowTypeNames = setOf("MutableSharedFlow")

        private val publicStatsTypeNames = setOf("ScreenCaptureStats")

        private val publicFrameDropStatsTypeNames = setOf("ScreenCaptureFrameDropStats")

        private val publicDeliveryDropStatsTypeNames = setOf("ScreenCaptureDeliveryDropStats")

        private val publicEventTypeNames = setOf("ScreenCaptureEvent")

        private val publicStatsCounterNames = setOf(
            "framesEncoded",
            "framesPublished",
            "droppedFrames",
            "droppedDeliveries",
            "publishedFps",
            "averageEncodeMs",
            "averageReadbackMs",
            "lastEncodedByteCount",
            "averageEncodedByteCount",
            "activeFrameSubscriptions",
            "slowConsumers",
        )

        private val startupPreparationForbiddenPatterns = listOf(
            regexPattern("SurfaceTexture frame update", methodCallPattern("updateTexImage")),
            regexPattern("SurfaceTexture frame update callable reference", callableReferencePattern("updateTexImage")),
            regexPattern("SurfaceTexture matrix acquisition", methodCallPattern("getTransformMatrix")),
            regexPattern(
                "SurfaceTexture matrix acquisition callable reference",
                callableReferencePattern("getTransformMatrix"),
            ),
            literalPattern("direct runtime readback", "glReadPixels"),
            regexPattern(
                "runtime readback seam call",
                Regex("""(?:^|[^\w])(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?readPixels\s*\("""),
            ),
            literalPattern("ImageEncoder direct encode", "ImageEncoder.encode"),
            literalPattern("ImageEncoder callable reference encode", "ImageEncoder::encode"),
            regexPattern("conservative encode callable reference", Regex("""::\s*encode\b""")),
            regexPattern("conservative direct encode call", Regex("""\.\s*encode\s*\(""")),
            regexPattern("internal session package import", Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.internal\.session\.""")),
            regexPattern(
                "fully-qualified internal session use",
                Regex("""\bdev\.dmkr\.screencaptureengine\.internal\.session\.[A-Za-z_][A-Za-z0-9_.]*\b"""),
            ),
            regexPattern(
                "internal session production type alias import",
                aliasImportPattern(
                    packageName = "dev.dmkr.screencaptureengine.internal.session.core",
                    typeNames = internalSessionProductionTypeNames,
                ),
            ),
            regexPattern("internal session production type/use", simpleNamePattern(internalSessionProductionTypeNames)),
            literalPattern("session core", "ScreenCaptureSessionCore"),
            regexPattern("encoded frame publication", Regex("""\bpublishEncodedFrame\s*\(""")),
            regexPattern("frame publication", Regex("""\bpublishFrame\s*\(""")),
            regexPattern("conservative generic publication call", Regex("""\.\s*publish\s*\(""")),
        )

        private val defaultEnginePrePublicCommitForbiddenPatterns = listOf(
            regexPattern("SurfaceTexture frame update", methodCallPattern("updateTexImage")),
            regexPattern("SurfaceTexture frame update callable reference", callableReferencePattern("updateTexImage")),
            regexPattern("SurfaceTexture matrix acquisition", methodCallPattern("getTransformMatrix")),
            regexPattern(
                "SurfaceTexture matrix acquisition callable reference",
                callableReferencePattern("getTransformMatrix"),
            ),
            literalPattern("direct runtime readback", "glReadPixels"),
            regexPattern(
                "runtime readback seam call",
                Regex("""(?:^|[^\w])(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?readPixels\s*\("""),
            ),
            literalPattern("ImageEncoder direct encode", "ImageEncoder.encode"),
            literalPattern("ImageEncoder callable reference encode", "ImageEncoder::encode"),
            regexPattern("conservative encode callable reference", Regex("""::\s*encode\b""")),
            regexPattern("conservative direct encode call", Regex("""\.\s*encode\s*\(""")),
            regexPattern("encoded frame publication", Regex("""\bpublishEncodedFrame\s*\(""")),
            regexPattern("frame publication", Regex("""\bpublishFrame\s*\(""")),
            regexPattern("conservative generic publication call", Regex("""\.\s*publish\s*\(""")),
        )

        private val runtimeParameterCommitBridgeForbiddenPatterns = listOf(
            regexPattern(
                "cancellable suspension or coroutine context check",
                Regex(
                    """\b(?:currentCoroutineContext|ensureActive|yield|delay|withTimeout|""" +
                            """suspendCancellableCoroutine|suspendCoroutine)\s*\(""",
                ),
            ),
            regexPattern(
                "dispatcher hop or coroutine launch",
                Regex("""\b(?:withContext|async|launch|runBlocking)\s*\("""),
            ),
            regexPattern("coroutine or latch await", Regex("""\.\s*await\s*\(""")),
            regexPattern("thread join", Regex("""\.\s*join\s*\(""")),
            regexPattern(
                "blocking wait, sleep, park, or future get",
                Regex(
                    """\b(?:CountDownLatch|CyclicBarrier|Semaphore|Future)\b|""" +
                            """\bThread\s*\.\s*sleep\s*\(|\bLockSupport\s*\.\s*park\w*\s*\(|\.\s*get\s*\(""",
                ),
            ),
            regexPattern(
                "provider, encoder, GL, or readback work",
                Regex(
                    """\b(?:ImageEncoderPreparer|ProviderPreparationContext|RenderingPipelinePreparer|""" +
                            """prepare[A-Za-z0-9_]*|provider|Provider|encoder|Encoder|readback|Readback|""" +
                            """GLES|gl[A-Z]\w*|RuntimeEs2FrameRenderer|updateTexImage|""" +
                            """getTransformMatrix|readPixels)\b""",
                ),
            ),
            regexPattern(
                "retarget platform call",
                Regex("""\b(?:resize|setSurface|setDefaultBufferSize|createVirtualDisplay|release)\s*\("""),
            ),
            regexPattern(
                "cleanup, close, or resource retirement",
                Regex(
                    """\.\s*close\s*\(|\bclose[A-Za-z0-9_]*\s*\(|""" +
                            """\b(?:cleanup|Cleanup)\b|\b(?:release|retire)[A-Za-z0-9_]*\s*\(""",
                ),
            ),
        )

        private val runtimeParameterCommitBridgeAllowedCalls = setOf(
            "synchronized",
            "beforeRuntimeParameterCommitOwnerLockForTesting?.invoke",
            "beforeRuntimeParameterCommitBridgeForTesting?.invoke",
            "checkNotNull",
            "classifyRuntimeParameterUpdateLocked",
            "runtimeFrameLoop.pauseProductionAdmission",
            "commitGate.commit",
            "runtimeTerminalRejectionLocked",
            "applyRuntimeParameterUpdateClassificationLocked",
            "also",
            "runtimeFrameLoop.resumeProductionAdmission",
        )

        private val runtimeParameterAtomicApplyHelperNames = setOf(
            "applyRuntimeParameterUpdateClassificationLocked",
            "applyRuntimeFullOutputPlanUpdateLocked",
            "applyRuntimeProviderOnlyUpdateLocked",
            "applyRuntimeFrameRateOnlyUpdateLocked",
        )

        private val runtimeParameterAtomicApplyHelperForbiddenPatterns = listOf(
            regexPattern(
                "cancellable suspension or dispatcher hop",
                Regex(
                    """\b(?:currentCoroutineContext|ensureActive|yield|delay|withTimeout|withContext|""" +
                            """async|launch|runBlocking|suspendCancellableCoroutine|suspendCoroutine)\s*\(""",
                ),
            ),
            regexPattern(
                "blocking wait",
                Regex(
                    """\.\s*(?:await|join|get)\s*\(|\bThread\s*\.\s*sleep\s*\(|""" +
                            """\bLockSupport\s*\.\s*park\w*\s*\(""",
                ),
            ),
            regexPattern("output-plan preparation", Regex("""\bprepareOutputPlan\s*\(""")),
            regexPattern("encoder preparation", Regex("""\b(?:encoderPrepare|ImageEncoderPreparer)\b|\.\s*prepare\s*\(""")),
            regexPattern("render/readback work", Regex("""\b(?:renderReadback|readPixels|glReadPixels)\s*\(""")),
            regexPattern("frame consumption", Regex("""\b(?:updateTexImage|getTransformMatrix|consumeLatestFrame)\s*\(""")),
            regexPattern(
                "retarget platform call",
                Regex("""\b(?:resize|setSurface|setDefaultBufferSize|createVirtualDisplay|createTarget|bindTarget)\s*\("""),
            ),
            regexPattern(
                "cleanup or close",
                Regex("""\.\s*close\s*\(|\b(?:scheduleStartupCleanup|closeRuntimeResources|retireGlResources)\s*\("""),
            ),
        )

        private val runtimeParameterAtomicApplyHelperAllowedCalls = setOf(
            "RuntimeParameterCommitBridgeOutcome",
            "ScreenCaptureParameterUpdateResult.Rejected",
            "applyRuntimeFrameRateOnlyUpdateLocked",
            "applyRuntimeProviderOnlyUpdateLocked",
            "applyRuntimeFullOutputPlanUpdateLocked",
            "unavailableRuntimeParameterUpdate",
            "core.newProblem",
            "runtimeFrameLoop.pauseProductionAdmission",
            "checkNotNull",
            "core.currentOutputGeneration",
            "candidate.planPreparationToken.consumeForHandoff",
            "Math.addExact",
            "classification.candidatePlan.toEffectiveParameters",
            "candidate.moveToActiveRuntimeOwner",
            "clearRetainedPeriodicRefreshStateLocked",
            "core.updateOutputActive",
            "check",
            "isRuntimeWorkInFlightLocked",
            "resources.replaceEncoderResourcesOnly",
            "candidate.markCommitted",
            "encodeHardFailureTracker.reset",
        )

        private val kotlinControlCallNames = setOf(
            "catch",
            "do",
            "else",
            "finally",
            "for",
            "if",
            "try",
            "when",
            "while",
        )

        private val sessionCoreUsePatterns = listOf(
            regexPattern(
                "session core import",
                Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.internal\.session\.core\.ScreenCaptureSessionCore\b"""),
            ),
            regexPattern("session core type/use", Regex("""\bScreenCaptureSessionCore\b""")),
        )

        private val publicStateOwnershipPatterns = listOf(
            regexPattern("mutable public state flow", Regex("""\bMutableStateFlow\s*(?:<|\()""")),
            regexPattern(
                "mutable public state flow alias import",
                aliasImportPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicStateFlowTypeNames),
            ),
            regexPattern(
                "mutable public state flow typealias",
                typeAliasPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicStateFlowTypeNames),
            ),
            regexPattern("mutable public event flow", Regex("""\bMutableSharedFlow\s*(?:<|\()""")),
            regexPattern(
                "mutable public event flow alias import",
                aliasImportPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicEventFlowTypeNames),
            ),
            regexPattern(
                "mutable public event flow typealias",
                typeAliasPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicEventFlowTypeNames),
            ),
            regexPattern("public stats construction", Regex("""\bScreenCaptureStats\s*\(""")),
            regexPattern(
                "public stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicStatsTypeNames),
            ),
            regexPattern(
                "public stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicStatsTypeNames),
            ),
            regexPattern("public frame-drop stats construction", Regex("""\bScreenCaptureFrameDropStats\s*\(""")),
            regexPattern(
                "public frame-drop stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicFrameDropStatsTypeNames),
            ),
            regexPattern(
                "public frame-drop stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicFrameDropStatsTypeNames),
            ),
            regexPattern("public delivery-drop stats construction", Regex("""\bScreenCaptureDeliveryDropStats\s*\(""")),
            regexPattern(
                "public delivery-drop stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicDeliveryDropStatsTypeNames),
            ),
            regexPattern(
                "public delivery-drop stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicDeliveryDropStatsTypeNames),
            ),
            regexPattern("public event construction", Regex("""\bScreenCaptureEvent\s*\(""")),
            regexPattern(
                "public event alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicEventTypeNames),
            ),
            regexPattern(
                "public event typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicEventTypeNames),
            ),
            regexPattern(
                "public stats counter assignment",
                publicStatsCounterAssignmentPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats counter declaration",
                publicStatsCounterDeclarationPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats atomic counter declaration",
                publicStatsAtomicCounterDeclarationPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats counter increment",
                publicStatsCounterIncrementPattern(publicStatsCounterNames),
            ),
            regexPattern("public lifecycle event emission", Regex("""\bemitEvent\s*\(""")),
        )

        private val startupRenderingGlAccessRegex = Regex("""\bStartupRenderingGlAccess\b""")

        private val publicBuiltInMetricsProviderPatterns = listOf(
            regexPattern(
                "CaptureMetricsProviders import",
                Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.CaptureMetricsProviders(?:\b|\.)"""),
            ),
            regexPattern(
                "CaptureMetricsProviders fully-qualified use",
                Regex("""\bdev\.dmkr\.screencaptureengine\.CaptureMetricsProviders\b"""),
            ),
            regexPattern(
                "CaptureMetricsProviders simple-name use",
                Regex("""\bCaptureMetricsProviders\b"""),
            ),
        )

        private val reflectionApiUsagePatterns = listOf(
            regexPattern("java reflection package import", Regex("""\bimport\s+java\.lang\.reflect(?:\.|\b)""")),
            regexPattern("kotlin reflection package import", Regex("""\bimport\s+kotlin\.reflect(?:\.|\b)""")),
            regexPattern("Class.forName reflection lookup", Regex("""\bClass\s*\.\s*forName\s*\(""")),
            regexPattern("javaClass reflective member lookup", Regex("""\bjavaClass\s*\.\s*get(?:Declared)?(?:Method|Field|Constructor)s?\s*\(""")),
            regexPattern(
                "javaClass reflective member property",
                Regex("""\bjavaClass\s*\.\s*(?:${reflectionMemberPropertyPattern})\b"""),
            ),
            regexPattern(
                "class-literal reflective member lookup",
                Regex("""::\s*class\s*\.\s*java\s*\.\s*get(?:Declared)?(?:Method|Field|Constructor)s?\s*\("""),
            ),
            regexPattern(
                "class-literal reflective member property",
                Regex("""::\s*class\s*\.\s*java\s*\.\s*(?:${reflectionMemberPropertyPattern})\b"""),
            ),
            regexPattern(
                "KClass reflective member access",
                Regex("""::\s*class\s*\.\s*(?:members|declaredMembers|memberProperties|declaredMemberProperties)\b"""),
            ),
        )

        private val rawCaptureMetricsStateFlowPatterns = listOf(
            regexPattern("raw StateFlow<CaptureMetrics>", Regex("""\bStateFlow\s*<\s*CaptureMetrics\s*>""")),
            regexPattern(
                "raw aliased StateFlow<CaptureMetrics>",
                Regex(
                    """\bimport\s+kotlinx\.coroutines\.flow\.StateFlow\s+as\s+([A-Za-z_][A-Za-z0-9_]*)\b""" +
                            """[\s\S]*\b\1\s*<\s*(?:dev\.dmkr\.screencaptureengine\.)?CaptureMetrics\s*>""",
                ),
            ),
            regexPattern(
                "raw fully-qualified StateFlow<CaptureMetrics>",
                Regex(
                    """\bkotlinx\.coroutines\.flow\.StateFlow\s*<\s*""" +
                            """(?:dev\.dmkr\.screencaptureengine\.)?CaptureMetrics\s*>""",
                ),
            ),
        )

        private const val reflectionMemberPropertyPattern =
            "methods|declaredMethods|fields|declaredFields|constructors|declaredConstructors"

        private val bareDirectGlCallRegex = Regex(
            "(?<![.\\w])(gl(?:ActiveTexture|AttachShader|Bind|Blend|Buffer|Check|Clear|ClientWait|" +
                    "Compile|Create|Delete|Detach|Disable|Draw|Enable|Fence|Finish|Flush|Framebuffer|" +
                    "Gen|Get|Link|Map|PixelStore|ReadPixels|Renderbuffer|Shader|Tex|Uniform|Unmap|" +
                    "Use|Validate|Viewport|Wait)\\w*)\\s*\\(",
        )

        private fun methodCallPattern(methodName: String): Regex =
            Regex("""(?:^|[^\w])(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?${Regex.escape(methodName)}\s*\(""")

        private fun callableReferencePattern(methodName: String): Regex =
            Regex("""::\s*${Regex.escape(methodName)}\b""")

        private fun aliasImportPattern(packageName: String, typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex(
                """\bimport\s+${Regex.escape(packageName)}\.(?:$typePattern)\s+as\s+""" +
                        """[A-Za-z_][A-Za-z0-9_]*\b""",
            )
        }

        private fun typeAliasPattern(packageName: String, typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex(
                """\btypealias\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>\n]+>)?\s*=\s*""" +
                        """(?:${Regex.escape(packageName)}\.)?(?:$typePattern)\b""",
            )
        }

        private fun publicStatsCounterAssignmentPattern(counterNames: Set<String>): Regex =
            Regex("""(?<![.\w])(?:${simpleAlternation(counterNames)})\s*(?:[-+*/%]?=)(?!=|>)""")

        private fun publicStatsCounterDeclarationPattern(counterNames: Set<String>): Regex =
            Regex("""\b(?:var|val)\s+(?:${simpleAlternation(counterNames)})\s*(?::|=)""")

        private fun publicStatsAtomicCounterDeclarationPattern(counterNames: Set<String>): Regex {
            val counterPattern = simpleAlternation(counterNames)
            val atomicTypePattern = """(?:java\.util\.concurrent\.atomic\.)?Atomic(?:Integer|Long)\b"""
            return Regex(
                """\b(?:var|val)\s+(?:$counterPattern)\s*:\s*$atomicTypePattern|""" +
                        """\b(?:(?:private|protected|internal|public)\s+)*$atomicTypePattern\s+""" +
                        """(?:$counterPattern)\b""",
            )
        }

        private fun publicStatsCounterIncrementPattern(counterNames: Set<String>): Regex {
            val counterPattern = simpleAlternation(counterNames)
            return Regex(
                """(?<![.\w])(?:(?:$counterPattern)\s*(?:\+\+|--)|""" +
                        """(?:\+\+|--)\s*(?:$counterPattern)\b)""",
            )
        }

        private fun simpleNamePattern(typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex("""\b(?:$typePattern)\b""")
        }

        private fun simpleAlternation(identifiers: Set<String>): String =
            identifiers.joinToString(separator = "|") { identifier -> Regex.escape(identifier) }

        private fun literalPattern(description: String, literal: String): BoundaryPattern =
            BoundaryPattern(description = "$description: $literal", regex = Regex(Regex.escape(literal)))

        private fun regexPattern(description: String, regex: Regex): BoundaryPattern =
            BoundaryPattern(description = "$description: ${regex.pattern}", regex = regex)
    }
}
