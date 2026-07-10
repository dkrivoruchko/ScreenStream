package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.encoding.EncodedFormatDescriptorSnapshot
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.PositiveRatio
import dev.dmkr.screencaptureengine.internal.planning.SamplingDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerStateTest {
    @Test
    fun controllerCreationIsOnlyPreActiveAndDerivesEveryGate() {
        val state = initial()

        assertSame(ControllerLifecycle.PreActive, state.lifecycle)
        assertEquals(CommittedRevisions(), state.revisions)
        assertNull(state.geometry.lastAcceptedGeometry)
        assertNull(state.capturedContentVisible)
        assertFalse(state.pause.blocksActive)
        assertTrue(state.acceptsIngress)
        assertFalse(state.admitsProduction)
        assertFalse(state.admitsDelivery)
        assertFalse(state.hasPublicState)
        assertNull(state.publicPrincipal)
    }

    @Test
    fun initialActiveRequiresCompleteTrustedGeometryAndExactProviderFreeEffectiveSnapshot() {
        val geometry = GeometrySnapshot(100, 80, 320)
        val accumulated = acceptedGeometry(geometry)
        val effective = effective(geometry)
        val active = initial().evolve(
            revisions = committed(),
            geometry = accumulated,
            lifecycle = ControllerLifecycle.Running(ControllerRunningOutput.Active(effective)),
        )

        assertTrue(active.lifecycle is ControllerLifecycle.Running)
        assertTrue(active.admitsProduction)
        assertTrue(active.admitsDelivery)
        assertTrue(active.hasPublicState)
        assertTrue(active.acceptsIngress)
        assertNull(active.capturedContentVisible)
        assertNull(active.publicPrincipal)

        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                revisions = committed(),
                geometry = accumulated,
                lifecycle = ControllerLifecycle.Running(
                    ControllerRunningOutput.Active(effective(GeometrySnapshot(99, 80, 320))),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                geometry = accumulated,
                lifecycle = ControllerLifecycle.Running(ControllerRunningOutput.Active(effective)),
            )
        }
    }

    @Test
    fun visibilityAndGeometryModeRemainBoundToTheCreationApiBand() {
        val metricsGeometry = acceptedGeometry(GeometrySnapshot(100, 80, 320))
        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(geometry = metricsGeometry, capturedContentVisible = true)
        }

        val capturedGeometry = acceptedCapturedGeometry(GeometrySnapshot(100, 80, 320))
        val captured = initial(ControllerGeometryMode.CapturedResizeAuthoritative).evolve(
            revisions = committed(),
            geometry = capturedGeometry,
            capturedContentVisible = true,
            lifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Active(effective(GeometrySnapshot(100, 80, 320))),
            ),
        )
        assertEquals(true, captured.capturedContentVisible)

        assertThrows(IllegalArgumentException::class.java) {
            runningActive().evolve(geometry = capturedGeometry)
        }
    }

    @Test
    fun activeRejectsPhysicalPauseFreshnessDebtAndEveryLiveReason() {
        val geometry = GeometrySnapshot(100, 80, 320)
        val accumulated = acceptedGeometry(geometry)
        val active = ControllerLifecycle.Running(ControllerRunningOutput.Active(effective(geometry)))

        listOf(
            PauseEvidence(physicalPaused = true, debtSequence = IngressSequence(8)),
            PauseEvidence(physicalPaused = false, debtSequence = IngressSequence(8)),
        ).forEach { evidence ->
            assertThrows(IllegalArgumentException::class.java) {
                initial().evolve(
                    revisions = committed(),
                    geometry = accumulated,
                    pause = ControllerPauseState.from(evidence),
                    lifecycle = active,
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                revisions = committed(),
                geometry = accumulated,
                arbiter = reasonArbiter(ReconfigurationReasonSpec.FreshTarget),
                lifecycle = active,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPauseState.from(PauseEvidence(physicalPaused = true, debtSequence = null))
        }

        val untrusted = (accumulated.acceptSourceTrust(SourceTrustEvidence.NoLongerAvailable) as
                ControllerGeometryFact.Untrusted).accumulator
        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                revisions = committed(),
                geometry = untrusted,
                lifecycle = active,
            )
        }

        val pending = ReconfigurationArbiter().replacePending(
            PendingReconfiguration(DesiredSnapshotIdentity(9), geometry, IngressSequence(9)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                revisions = committed(),
                geometry = accumulated,
                arbiter = pending,
                lifecycle = active,
            )
        }
    }

    @Test
    fun suspendedKeepsLastEffectiveLatestGeometryAndSemanticPrincipal() {
        val oldGeometry = GeometrySnapshot(100, 80, 320)
        val currentGeometry = GeometrySnapshot(120, 90, 360)
        val accumulated = acceptedGeometry(currentGeometry)
        val active = runningActive(oldGeometry)
        val previous = active.runningOutput().previousEffective
        val arbiter = reasonArbiter(ReconfigurationReasonSpec.PlatformPause)
            .add(ReasonToken(2), ReconfigurationReasonSpec.EncodingOwner, IngressSequence(2))
        val principal = checkNotNull(arbiter.principal)
        val suspended = active.evolve(
            geometry = accumulated,
            pause = ControllerPauseState.from(PauseEvidence(false, IngressSequence(7))),
            arbiter = arbiter,
            lifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Suspended(
                    previousEffective = previous,
                    currentGeometry = currentGeometry,
                    principal = principal,
                ),
            ),
        )

        assertFalse(suspended.admitsProduction)
        assertFalse(suspended.admitsDelivery)
        assertEquals(ReconfigurationReasonSpec.EncodingOwner, suspended.publicPrincipal)
        val output = (suspended.lifecycle as ControllerLifecycle.Running).output as ControllerRunningOutput.Suspended
        assertSame(previous, output.previousEffective)
        assertEquals(currentGeometry, output.currentGeometry)

        val sameSemanticPrincipal = arbiter.add(
            ReasonToken(3),
            ReconfigurationReasonSpec.ResourceAdmission,
            IngressSequence(3),
        )
        val changedSecondary = suspended.evolve(arbiter = sameSemanticPrincipal)
        assertEquals(suspended.publicPrincipal, changedSecondary.publicPrincipal)
        assertSame(previous, (changedSecondary.lifecycle as ControllerLifecycle.Running).output.previousEffective)
    }

    @Test
    fun suspendedRejectsMissingWrongPrincipalAndStaleGeometry() {
        val geometry = GeometrySnapshot(100, 80, 320)
        val accumulated = acceptedGeometry(geometry)
        val active = runningActive(geometry)
        val previous = active.runningOutput().previousEffective

        val foreignPrincipal = ReconfigurationReason(
            ReasonToken(9),
            ReconfigurationReasonSpec.FreshTarget,
            IngressSequence(9),
        )
        listOf(
            ReconfigurationArbiter() to foreignPrincipal,
            reasonArbiter(ReconfigurationReasonSpec.EncodingOwner) to foreignPrincipal,
        ).forEach { (arbiter, principal) ->
            assertThrows(IllegalArgumentException::class.java) {
                active.evolve(
                    geometry = accumulated,
                    arbiter = arbiter,
                    lifecycle = ControllerLifecycle.Running(
                        ControllerRunningOutput.Suspended(previous, geometry, principal),
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(
                geometry = accumulated,
                arbiter = reasonArbiter(ReconfigurationReasonSpec.FreshTarget),
                lifecycle = ControllerLifecycle.Running(
                    ControllerRunningOutput.Suspended(
                        previous,
                        GeometrySnapshot(101, 80, 320),
                        checkNotNull(reasonArbiter(ReconfigurationReasonSpec.FreshTarget).principal),
                    ),
                ),
            )
        }
    }

    @Test
    fun suspensionCannotReplaceTheLastCommittedActiveSnapshot() {
        val active = runningActive()
        val geometry = checkNotNull(active.geometry.lastAcceptedGeometry)
        val arbiter = reasonArbiter(ReconfigurationReasonSpec.FreshTarget)
        val principal = checkNotNull(arbiter.principal)
        val retained = ((active.lifecycle as ControllerLifecycle.Running).output as ControllerRunningOutput.Active)
            .effective
        val suspended = active.evolve(
            arbiter = arbiter,
            lifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Suspended(retained, geometry, principal),
            ),
        )
        assertSame(
            retained,
            ((suspended.lifecycle as ControllerLifecycle.Running).output as ControllerRunningOutput.Suspended)
                .previousEffective,
        )

        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(
                arbiter = arbiter,
                lifecycle = ControllerLifecycle.Running(
                    ControllerRunningOutput.Suspended(effective(geometry), geometry, principal),
                ),
            )
        }
    }

    @Test
    fun preActiveRetirementAndPostActiveTerminalAreDistinctFinalShapes() {
        val retiredOutcome = ControllerTerminalOutcome.from(TerminalEvidence.ProjectionStopped)
        val retired = initial().evolve(
            lifecycle = ControllerLifecycle.RetiredBeforePublic,
        )
        assertFalse(retired.acceptsIngress)
        assertFalse(retired.hasPublicState)
        assertSame(retired, retired.evolve())

        val active = runningActive()
        val failedOutcome = ControllerTerminalOutcome.from(TerminalEvidence.InternalControllerInvariant)
        val terminal = active.evolve(
            revisions = CommittedRevisions(1, 1, 2),
            lifecycle = ControllerLifecycle.Terminal(failedOutcome),
        )
        assertFalse(terminal.acceptsIngress)
        assertTrue(terminal.hasPublicState)
        assertFalse(terminal.admitsProduction)
        assertSame(terminal, terminal.evolve())

        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(lifecycle = ControllerLifecycle.Terminal(retiredOutcome))
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(lifecycle = ControllerLifecycle.RetiredBeforePublic)
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminal.evolve(capturedContentVisible = true)
        }
    }

    @Test
    fun terminalAndRetirementRejectLiveLogicalWork() {
        val outcome = ControllerTerminalOutcome.from(TerminalEvidence.OwnerStopped)
        val liveArbiter = reasonArbiter(ReconfigurationReasonSpec.FreshTarget)
        val transaction = transaction()

        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(
                arbiter = liveArbiter,
                lifecycle = ControllerLifecycle.RetiredBeforePublic,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningActive().evolve(
                revisions = CommittedRevisions(1, 1, 2),
                parameterTransaction = transaction,
                lifecycle = ControllerLifecycle.Terminal(outcome),
            )
        }
    }

    @Test
    fun parameterTransactionIsSingleLiveRuntimeStateAndArbiterAssociationMustMatchIt() {
        assertThrows(IllegalArgumentException::class.java) {
            initial().evolve(parameterTransaction = transaction())
        }

        val active = runningActive()
        val transaction = transaction()
        val associated = ReconfigurationArbiter().begin(
            identity = ReconfigurationIdentity(1),
            candidate = transaction.candidate,
            parameter = transaction.identity,
        )
        val withTransaction = active.evolve(
            arbiter = associated,
            parameterTransaction = transaction,
        )
        assertSame(transaction, withTransaction.parameterTransaction)

        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(arbiter = associated)
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(
                arbiter = associated,
                parameterTransaction = transaction.copy(identity = TransactionIdentity(99)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(
                arbiter = ReconfigurationArbiter().begin(
                    identity = ReconfigurationIdentity(1),
                    candidate = transaction.candidate,
                    parameter = transaction.identity,
                ),
                parameterTransaction = transaction.copy(previousEffective = EffectiveSnapshotIdentity(99)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            active.evolve(
                parameterTransaction = transaction.copy(stage = ParameterTransactionStage.Completed),
            )
        }
    }

    @Test
    fun committedRevisionsNeverRollBackAndTerminalPreservesGeometryAndTarget() {
        val active = runningActive().evolve(revisions = CommittedRevisions(3, 4, 5))
        val outcome = ControllerTerminalOutcome.from(TerminalEvidence.OwnerStopped)

        listOf(
            CommittedRevisions(2, 4, 5),
            CommittedRevisions(3, 3, 5),
            CommittedRevisions(3, 4, 4),
        ).forEach { revisions ->
            assertThrows(IllegalArgumentException::class.java) { active.evolve(revisions = revisions) }
        }
        listOf(
            CommittedRevisions(4, 4, 6),
            CommittedRevisions(3, 5, 6),
            CommittedRevisions(0, 0, 6),
        ).forEach { revisions ->
            assertThrows(IllegalArgumentException::class.java) {
                active.evolve(revisions = revisions, lifecycle = ControllerLifecycle.Terminal(outcome))
            }
        }

        val terminal = active.evolve(
            revisions = CommittedRevisions(3, 4, 6),
            lifecycle = ControllerLifecycle.Terminal(outcome),
        )
        assertEquals(CommittedRevisions(3, 4, 6), terminal.revisions)
    }

    @Test
    fun liveParameterStageAndLifecycleCompatibilityIsExhaustive() {
        val active = runningActive()
        val geometry = checkNotNull(active.geometry.lastAcceptedGeometry)
        val arbiter = reasonArbiter(ReconfigurationReasonSpec.FreshTarget)
        val principal = checkNotNull(arbiter.principal)
        val suspended = active.evolve(
            arbiter = arbiter,
            lifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Suspended(
                    previousEffective = active.runningOutput().previousEffective,
                    currentGeometry = geometry,
                    principal = principal,
                ),
            ),
        )

        structurallyValidLiveTransactions().forEach { transaction ->
            val activeArbiter = ReconfigurationArbiter().begin(
                identity = ReconfigurationIdentity(1),
                candidate = transaction.candidate,
                parameter = transaction.identity,
            )
            if (transaction.isAllowedWhileActive()) {
                assertSame(
                    transaction,
                    active.evolve(arbiter = activeArbiter, parameterTransaction = transaction).parameterTransaction,
                )
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    active.evolve(arbiter = activeArbiter, parameterTransaction = transaction)
                }
            }

            val suspendedArbiter = arbiter.begin(
                identity = ReconfigurationIdentity(1),
                candidate = transaction.candidate,
                parameter = transaction.identity,
            )
            val suspendedLifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Suspended(
                    previousEffective = suspended.runningOutput().previousEffective,
                    currentGeometry = geometry,
                    principal = checkNotNull(suspendedArbiter.principal),
                ),
            )
            if (transaction.isAllowedWhileSuspended()) {
                assertSame(
                    transaction,
                    suspended.evolve(
                        arbiter = suspendedArbiter,
                        parameterTransaction = transaction,
                        lifecycle = suspendedLifecycle,
                    ).parameterTransaction,
                )
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    suspended.evolve(
                        arbiter = suspendedArbiter,
                        parameterTransaction = transaction,
                        lifecycle = suspendedLifecycle,
                    )
                }
            }
        }
    }

    @Test
    fun preActiveRetirementIsAResultNeutralMarkerForCancellationAndOrdinaryFailure() {
        val cancellation = initial().evolve(lifecycle = ControllerLifecycle.RetiredBeforePublic)
        val ordinaryFailure = initial().evolve(
            revisions = CommittedRevisions(1, 1, 0),
            geometry = acceptedGeometry(GeometrySnapshot(100, 80, 320)),
            lifecycle = ControllerLifecycle.RetiredBeforePublic,
        )

        assertSame(ControllerLifecycle.RetiredBeforePublic, cancellation.lifecycle)
        assertSame(ControllerLifecycle.RetiredBeforePublic, ordinaryFailure.lifecycle)
        assertFalse(cancellation.hasPublicState)
        assertFalse(ordinaryFailure.hasPublicState)
    }

    @Test
    fun terminalOutcomeClassCannotContradictWinnerSeverity() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerTerminalOutcome.Stopped(TerminalEvidence.StartedEncoderStall)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerTerminalOutcome.Failed(TerminalEvidence.OwnerStopped)
        }
        assertTrue(
            ControllerTerminalOutcome.from(TerminalEvidence.DisplayStopped) is ControllerTerminalOutcome.Stopped,
        )
        assertTrue(
            ControllerTerminalOutcome.from(TerminalEvidence.UnsafeRenderingState) is ControllerTerminalOutcome.Failed,
        )
    }

    @Test
    fun stateEvolutionCannotAliasOrRewriteEarlierImmutableSnapshots() {
        val state = runningActive(geometryMode = ControllerGeometryMode.CapturedResizeAuthoritative)
        val changed = state.evolve(capturedContentVisible = false)

        assertNull(state.capturedContentVisible)
        assertEquals(false, changed.capturedContentVisible)
        assertSame(state.revisions, changed.revisions)
        assertSame(state.geometry, changed.geometry)
        assertSame(state.arbiter, changed.arbiter)
        assertSame(state.pause, changed.pause)
        assertSame(state.lifecycle, changed.lifecycle)
    }

    @Test
    fun callerOwnedReasonMapCannotRewriteStateOrItsPublicPrincipal() {
        val geometry = GeometrySnapshot(100, 80, 320)
        val reason = ReconfigurationReason(
            token = ReasonToken(1),
            spec = ReconfigurationReasonSpec.FreshTarget,
            acceptedAt = IngressSequence(1),
        )
        val source = linkedMapOf(reason.spec.key to reason)
        val arbiter = ReconfigurationArbiter(reasons = source)
        val active = runningActive(geometry)
        val state = active.evolve(
            arbiter = arbiter,
            lifecycle = ControllerLifecycle.Running(
                ControllerRunningOutput.Suspended(
                    previousEffective = active.runningOutput().previousEffective,
                    currentGeometry = geometry,
                    principal = reason,
                ),
            ),
        )

        source.clear()

        assertEquals(ReconfigurationReasonSpec.FreshTarget, state.publicPrincipal)
        assertEquals(setOf(ReconfigurationReasonKey.FreshTarget), state.arbiter.reasons.keys)
    }

    private fun initial(
        geometryMode: ControllerGeometryMode = ControllerGeometryMode.MetricsAuthoritative,
    ): ControllerState = ControllerState.create(
        controllerIdentity = ControllerIdentity(1),
        sessionIdentity = SessionIdentity(2),
        geometryMode = geometryMode,
    )

    private fun runningActive(
        geometry: GeometrySnapshot = GeometrySnapshot(100, 80, 320),
        geometryMode: ControllerGeometryMode = ControllerGeometryMode.MetricsAuthoritative,
    ): ControllerState {
        val accumulated = when (geometryMode) {
            ControllerGeometryMode.MetricsAuthoritative -> acceptedGeometry(geometry)
            ControllerGeometryMode.CapturedResizeAuthoritative -> acceptedCapturedGeometry(geometry)
        }
        return initial(geometryMode).evolve(
            revisions = committed(),
            geometry = accumulated,
            lifecycle = ControllerLifecycle.Running(ControllerRunningOutput.Active(effective(geometry))),
        )
    }

    private fun committed(): CommittedRevisions = CommittedRevisions(1, 1, 1)

    private fun acceptedGeometry(geometry: GeometrySnapshot): ControllerGeometryAccumulator {
        val fact = ControllerGeometryAccumulator.create(ControllerGeometryMode.MetricsAuthoritative)
            .acceptMetrics(MetricsEvidence(geometry.width, geometry.height, geometry.densityDpi))
        return (fact as ControllerGeometryFact.Accepted).accumulator
    }

    private fun acceptedCapturedGeometry(geometry: GeometrySnapshot): ControllerGeometryAccumulator {
        val metrics = ControllerGeometryAccumulator.create(ControllerGeometryMode.CapturedResizeAuthoritative)
            .acceptMetrics(MetricsEvidence(999, 999, geometry.densityDpi))
            .accumulator
        val fact = metrics.acceptCapturedResize(CapturedResizeEvidence(geometry.width, geometry.height))
        return (fact as ControllerGeometryFact.Accepted).accumulator
    }

    private fun reasonArbiter(spec: ReconfigurationReasonSpec): ReconfigurationArbiter =
        ReconfigurationArbiter().add(ReasonToken(1), spec, IngressSequence(1))

    private fun effective(geometry: GeometrySnapshot): ControllerEffectiveSnapshot = ControllerEffectiveSnapshot(
        identity = EffectiveSnapshotIdentity(1),
        desiredIdentity = DesiredSnapshotIdentity(2),
        targetIdentity = TargetIdentity(3),
        completeOwnerIdentity = CompleteOwnerIdentity(4),
        output = output,
        geometry = geometry,
        plan = BaselineOutputPlan(
            appliedSourceRect = ImageRect(0, 0, geometry.width, geometry.height),
            orientedContentSize = Size(geometry.width, geometry.height),
            finalImageSize = Size(geometry.width, geometry.height),
            projectionTargetSize = Size(geometry.width, geometry.height),
            samplingDemand = SamplingDemand(PositiveRatio(1, 1), PositiveRatio(1, 1)),
            rowStrideBytes = geometry.width * 4,
            requiredRgbaBytes = geometry.width * geometry.height * 4,
        ),
        encodedFormat = EncodedFormatDescriptorSnapshot.copy("JPEG", "image/jpeg"),
    )

    private fun transaction(): ParameterTransaction = ParameterTransaction(
        identity = TransactionIdentity(1),
        desired = DesiredSnapshotIdentity(2),
        previousEffective = EffectiveSnapshotIdentity(1),
        candidate = CandidateIdentity(4),
        candidateClass = PreparedParameterCandidateClass.TargetReplan,
    )

    private fun ControllerState.runningOutput(): ControllerRunningOutput =
        (lifecycle as ControllerLifecycle.Running).output

    private fun structurallyValidLiveTransactions(): List<ParameterTransaction> = buildList {
        add(transactionFor(PreparedParameterCandidateClass.FrameRateOnly, ParameterTransactionStage.Preparing))
        listOf(
            ParameterTransactionStage.Preparing,
            ParameterTransactionStage.CandidateReady,
        ).forEach { stage ->
            add(transactionFor(PreparedParameterCandidateClass.SameTargetReplacement, stage))
        }
        ParameterTransactionStage.entries
            .filterNot { it == ParameterTransactionStage.Completed }
            .forEach { stage -> add(transactionFor(PreparedParameterCandidateClass.TargetReplan, stage)) }
    }

    private fun transactionFor(
        candidateClass: PreparedParameterCandidateClass,
        stage: ParameterTransactionStage,
    ): ParameterTransaction {
        val hasCandidate = stage != ParameterTransactionStage.Preparing
        return ParameterTransaction(
            identity = TransactionIdentity(1),
            desired = DesiredSnapshotIdentity(2),
            previousEffective = EffectiveSnapshotIdentity(1),
            candidate = CandidateIdentity(4),
            candidateClass = candidateClass,
            stage = stage,
            candidateOwner = if (hasCandidate) CompleteOwnerIdentity(5) else null,
            candidateTarget = if (hasCandidate && candidateClass == PreparedParameterCandidateClass.TargetReplan) {
                TargetIdentity(6)
            } else {
                null
            },
        )
    }

    private fun ParameterTransaction.isAllowedWhileActive(): Boolean = when (candidateClass) {
        PreparedParameterCandidateClass.FrameRateOnly -> stage == ParameterTransactionStage.Preparing
        PreparedParameterCandidateClass.SameTargetReplacement -> stage in preparationStages
        PreparedParameterCandidateClass.TargetReplan ->
            stage in preparationStages || stage == ParameterTransactionStage.ReadyPermit
    }

    private fun ParameterTransaction.isAllowedWhileSuspended(): Boolean = when (candidateClass) {
        PreparedParameterCandidateClass.FrameRateOnly -> false
        PreparedParameterCandidateClass.SameTargetReplacement -> stage in preparationStages
        PreparedParameterCandidateClass.TargetReplan ->
            stage in preparationStages || stage in postSuspensionStages
    }

    private companion object {
        val preparationStages = setOf(
            ParameterTransactionStage.Preparing,
            ParameterTransactionStage.CandidateReady,
        )

        val postSuspensionStages = setOf(
            ParameterTransactionStage.Suspended,
            ParameterTransactionStage.Draining,
            ParameterTransactionStage.RetargetArmed,
            ParameterTransactionStage.RetargetStarted,
            ParameterTransactionStage.TargetAcknowledged,
            ParameterTransactionStage.Converging,
            ParameterTransactionStage.Restoring,
        )

        val output = NormalizedOutputValues(
            sourceRegion = NormalizedSourceRegion.Full,
            crop = NormalizedCrop(0, 0, 0, 0),
            outputSize = NormalizedOutputSize.ScaleFactor(1.0),
            rotation = NormalizedRotation.Degrees0,
            mirror = NormalizedMirror.None,
            colorMode = NormalizedColorMode.Original,
            frameRate = NormalizedFrameRate.Auto,
        )
    }
}
