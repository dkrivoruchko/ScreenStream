package dev.dmkr.screencaptureengine.internal.encoding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProviderDescriptorLedgerTest {
    @Test
    fun oneProviderSharesStickyDescriptorAcrossAllSevenClosedRoles() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val desired = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, first = true)
        assertRecorded(ledger.recordDescriptor(desired, JPEG), first = true)

        ProviderDescriptorRetentionRole.entries.drop(1).forEach { role ->
            val token = fork(ledger, desired, role)
            assertRecorded(ledger.recordDescriptor(token, JPEG), first = false)
        }

        val snapshot = ledger.snapshot(provider) ?: error("Missing descriptor")
        assertSame(JPEG, snapshot.descriptor)
        assertEquals(7, snapshot.liveReferenceCount)
        ProviderDescriptorRetentionRole.entries.forEach { role ->
            assertEquals(1, snapshot.retentionCounts.getValue(role))
        }
        assertEquals(1, ledger.retainedDescriptorCount)
    }

    @Test
    fun providerAndTokenIdentityNeverUseCallerEqualityHashOrText() {
        val ledger = LiveProviderDescriptorLedger()
        val firstProvider = HostileIdentity()
        val secondProvider = HostileIdentity()
        val first = reserve(ledger, firstProvider, ProviderDescriptorRetentionRole.Desired, true)
        val second = reserve(ledger, secondProvider, ProviderDescriptorRetentionRole.Desired, true)
        assertRecorded(ledger.recordDescriptor(first, JPEG), true)
        assertRecorded(ledger.recordDescriptor(second, JPEG), true)

        assertEquals(2, ledger.retainedDescriptorCount)
        assertTrue(ledger.release(first))
        assertNull(ledger.snapshot(firstProvider))
        assertTrue(ledger.snapshot(secondProvider) != null)
    }

    @Test
    fun forkCreatesIndependentCapabilityAndRekeyNeverCreatesRetentionGap() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val candidate = reserve(ledger, provider, ProviderDescriptorRetentionRole.Candidate, true)
        val cleanup = fork(ledger, candidate, ProviderDescriptorRetentionRole.Cleanup)
        assertNotSame(candidate, cleanup)

        assertTrue(ledger.rekey(candidate, ProviderDescriptorRetentionRole.Active))
        assertTrue(ledger.rekey(candidate, ProviderDescriptorRetentionRole.Active))
        assertEquals(
            mapOf(
                ProviderDescriptorRetentionRole.Active to 1,
                ProviderDescriptorRetentionRole.Cleanup to 1,
            ),
            ledger.snapshot(provider)?.retentionCounts,
        )
        assertTrue(ledger.release(candidate))
        assertEquals(1, ledger.snapshot(provider)?.liveReferenceCount)
        assertTrue(ledger.release(cleanup))
        assertNull(ledger.snapshot(provider))
    }

    @Test
    fun sameProviderHandoffInstallsSuccessorAndInvalidatesPredecessorExactlyOnce() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val desired = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, true)
        val candidate = fork(ledger, desired, ProviderDescriptorRetentionRole.Candidate)
        assertRecorded(ledger.recordDescriptor(desired, JPEG), true)
        assertRecorded(ledger.recordDescriptor(candidate, JPEG), false)

        assertTrue(ledger.handoff(desired, candidate, ProviderDescriptorRetentionRole.Active))
        assertFalse(ledger.release(desired))
        assertFalse(ledger.rekey(desired, ProviderDescriptorRetentionRole.Cleanup))
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Active to 1),
            ledger.snapshot(provider)?.retentionCounts,
        )
        assertTrue(ledger.release(candidate))
        assertNull(ledger.snapshot(provider))
    }

    @Test
    fun differentProviderHandoffRetiresOldOnlyAfterNewWasAlreadyReserved() {
        val ledger = LiveProviderDescriptorLedger()
        val oldProvider = HostileIdentity()
        val newProvider = HostileIdentity()
        val oldDesired = reserve(ledger, oldProvider, ProviderDescriptorRetentionRole.Desired, true)
        val candidate = reserve(ledger, newProvider, ProviderDescriptorRetentionRole.Candidate, true)
        assertRecorded(ledger.recordDescriptor(oldDesired, JPEG), true)
        assertRecorded(ledger.recordDescriptor(candidate, PNG), true)

        assertTrue(ledger.handoff(oldDesired, candidate, ProviderDescriptorRetentionRole.Desired))
        assertNull(ledger.snapshot(oldProvider))
        assertSame(PNG, ledger.snapshot(newProvider)?.descriptor)
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Desired to 1),
            ledger.snapshot(newProvider)?.retentionCounts,
        )
    }

    @Test
    fun selfHandoffIsAtomicRekeyWithoutInvalidatingCapability() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val token = reserve(ledger, provider, ProviderDescriptorRetentionRole.Candidate, true)

        assertTrue(ledger.handoff(token, token, ProviderDescriptorRetentionRole.Quarantine))
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Quarantine to 1),
            ledger.snapshot(provider)?.retentionCounts,
        )
        assertTrue(ledger.release(token))
    }

    @Test
    fun wrongLedgerReleasedAndInvalidHandoffTokensCannotMutateLiveOwnership() {
        val firstLedger = LiveProviderDescriptorLedger()
        val secondLedger = LiveProviderDescriptorLedger()
        val firstProvider = HostileIdentity()
        val secondProvider = HostileIdentity()
        val first = reserve(firstLedger, firstProvider, ProviderDescriptorRetentionRole.Desired, true)
        val second = reserve(secondLedger, secondProvider, ProviderDescriptorRetentionRole.Candidate, true)

        assertFalse(firstLedger.rekey(second, ProviderDescriptorRetentionRole.Active))
        assertFalse(firstLedger.release(second))
        assertEquals(
            ProviderDescriptorForkResult.InvalidSource,
            firstLedger.fork(second, ProviderDescriptorRetentionRole.Late),
        )
        assertEquals(ProviderDescriptorRecordResult.InvalidToken, firstLedger.recordDescriptor(second, JPEG))
        assertFalse(firstLedger.handoff(first, second, ProviderDescriptorRetentionRole.Active))
        assertEquals(1, firstLedger.snapshot(firstProvider)?.liveReferenceCount)

        assertTrue(firstLedger.release(first))
        assertFalse(firstLedger.release(first))
        assertFalse(firstLedger.handoff(first, first, ProviderDescriptorRetentionRole.Active))
        assertEquals(1, secondLedger.snapshot(secondProvider)?.liveReferenceCount)
    }

    @Test
    fun descriptorMutationIsStickyUntilFinalReleaseThenReintroductionIsFresh() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val desired = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, true)
        val candidate = fork(ledger, desired, ProviderDescriptorRetentionRole.Candidate)
        assertRecorded(ledger.recordDescriptor(desired, JPEG), true)
        assertEquals(
            ProviderDescriptorRecordResult.DescriptorViolation(JPEG, PNG, firstViolation = true),
            ledger.recordDescriptor(candidate, PNG),
        )

        val late = fork(ledger, desired, ProviderDescriptorRetentionRole.Late)
        assertEquals(
            ProviderDescriptorRecordResult.DescriptorViolation(JPEG, JPEG, firstViolation = false),
            ledger.recordDescriptor(late, JPEG),
        )
        assertTrue(ledger.snapshot(provider)?.violated == true)

        listOf(candidate, late, desired).forEach { assertTrue(ledger.release(it)) }
        assertNull(ledger.snapshot(provider))
        val reintroduced = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, true)
        assertRecorded(ledger.recordDescriptor(reintroduced, PNG), true)
        assertSame(PNG, ledger.snapshot(provider)?.descriptor)
    }

    @Test
    fun initialInvalidObservationIsAlsoStickyAndCannotBeRecordedTwice() {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val invalid = DescriptorSyntax.copySnapshot("provider", "JPEG", "IMAGE/JPEG")
        val first = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, true)
        assertEquals(
            ProviderDescriptorRecordResult.InvalidDescriptor(invalid),
            ledger.recordDescriptor(first, invalid),
        )
        assertEquals(ProviderDescriptorRecordResult.AlreadyRecorded, ledger.recordDescriptor(first, invalid))

        val same = fork(ledger, first, ProviderDescriptorRetentionRole.Candidate)
        assertEquals(
            ProviderDescriptorRecordResult.InvalidDescriptor(invalid),
            ledger.recordDescriptor(same, invalid),
        )
        val changed = fork(ledger, first, ProviderDescriptorRetentionRole.Late)
        assertEquals(
            ProviderDescriptorRecordResult.DescriptorViolation(invalid, JPEG, firstViolation = true),
            ledger.recordDescriptor(changed, JPEG),
        )
        assertSame(invalid, ledger.snapshot(provider)?.descriptor)
    }

    @Test
    fun eighthDistinctIdentityIsDeniedButExistingIdentityCanStillFork() {
        val ledger = LiveProviderDescriptorLedger()
        val live = List(MAXIMUM_DESCRIPTORS) { index ->
            val provider = HostileIdentity()
            val token = reserve(
                ledger,
                provider,
                ProviderDescriptorRetentionRole.entries[index],
                first = true,
            )
            provider to token
        }
        assertEquals(MAXIMUM_DESCRIPTORS, ledger.retainedDescriptorCount)
        assertEquals(
            ProviderDescriptorReserveResult.CapacityExceeded,
            ledger.reserve(HostileIdentity(), ProviderDescriptorRetentionRole.Candidate),
        )

        val additional = reserve(
            ledger,
            live.first().first,
            ProviderDescriptorRetentionRole.Candidate,
            first = false,
        )
        val fork = fork(ledger, additional, ProviderDescriptorRetentionRole.Late)
        assertEquals(3, ledger.snapshot(live.first().first)?.liveReferenceCount)
        assertTrue(ledger.release(fork))
        assertTrue(ledger.release(additional))
        assertTrue(ledger.release(live.first().second))
        reserve(ledger, HostileIdentity(), ProviderDescriptorRetentionRole.Quarantine, first = true)
        assertEquals(MAXIMUM_DESCRIPTORS, ledger.retainedDescriptorCount)
    }

    @Test
    fun concurrentDistinctReservationsNeverExceedStructuralCapacity() {
        val ledger = LiveProviderDescriptorLedger()
        val admitted = AtomicInteger()
        runConcurrently(
            List(24) {
                {
                    if (
                        ledger.reserve(
                            HostileIdentity(),
                            ProviderDescriptorRetentionRole.Quarantine,
                        ) is ProviderDescriptorReserveResult.Reserved
                    ) {
                        admitted.incrementAndGet()
                    }
                }
            },
        )

        assertEquals(MAXIMUM_DESCRIPTORS, admitted.get())
        assertEquals(MAXIMUM_DESCRIPTORS, ledger.retainedDescriptorCount)
    }

    @Test
    fun concurrentDifferentFirstObservationsAcceptExactlyOneAndPoisonIdentity() {
        repeat(100) {
            val ledger = LiveProviderDescriptorLedger()
            val provider = HostileIdentity()
            val jpeg = reserve(ledger, provider, ProviderDescriptorRetentionRole.Desired, true)
            val png = fork(ledger, jpeg, ProviderDescriptorRetentionRole.Candidate)
            val jpegResult = AtomicReference<ProviderDescriptorRecordResult>()
            val pngResult = AtomicReference<ProviderDescriptorRecordResult>()
            runConcurrently(
                listOf(
                    { jpegResult.set(ledger.recordDescriptor(jpeg, JPEG)) },
                    { pngResult.set(ledger.recordDescriptor(png, PNG)) },
                ),
            )

            val results = listOf(jpegResult.get(), pngResult.get())
            assertEquals(1, results.count { it == ProviderDescriptorRecordResult.Recorded(true) })
            assertEquals(1, results.count { it is ProviderDescriptorRecordResult.DescriptorViolation })
            assertTrue(ledger.snapshot(provider)?.violated == true)
        }
    }

    @Test
    fun concurrentRecordAndReleaseHasOneLinearizedOutcomeAndNoOrphanEntry() {
        repeat(100) {
            val ledger = LiveProviderDescriptorLedger()
            val provider = HostileIdentity()
            val token = reserve(ledger, provider, ProviderDescriptorRetentionRole.Late, true)
            val result = AtomicReference<ProviderDescriptorRecordResult>()
            runConcurrently(
                listOf(
                    { result.set(ledger.recordDescriptor(token, JPEG)) },
                    { ledger.release(token) },
                ),
            )

            assertTrue(
                result.get() == ProviderDescriptorRecordResult.Recorded(true) ||
                        result.get() == ProviderDescriptorRecordResult.InvalidToken,
            )
            assertNull(ledger.snapshot(provider))
            assertEquals(0, ledger.retainedDescriptorCount)
            assertFalse(ledger.release(token))
        }
    }

    @Test
    fun finalReleaseAndSameProviderReserveAreLinearizableInBothOrders() {
        val retainedLedger = LiveProviderDescriptorLedger()
        val retainedProvider = HostileIdentity()
        val retainedOld = reserve(
            retainedLedger,
            retainedProvider,
            ProviderDescriptorRetentionRole.Desired,
            first = true,
        )
        assertRecorded(retainedLedger.recordDescriptor(retainedOld, JPEG), first = true)
        val retainedResult = AtomicReference<ProviderDescriptorReserveResult>()

        runOnThreadsInOrder(
            first = {
                retainedResult.set(
                    retainedLedger.reserve(
                        retainedProvider,
                        ProviderDescriptorRetentionRole.Candidate,
                    ),
                )
            },
            second = { assertTrue(retainedLedger.release(retainedOld)) },
        )

        val retainedNew = (retainedResult.get() as ProviderDescriptorReserveResult.Reserved).also {
            assertFalse(it.firstAdmission)
        }.token
        assertEquals(
            ProviderDescriptorRecordResult.DescriptorViolation(
                expected = JPEG,
                observed = PNG,
                firstViolation = true,
            ),
            retainedLedger.recordDescriptor(retainedNew, PNG),
        )
        assertEquals(1, retainedLedger.snapshot(retainedProvider)?.liveReferenceCount)
        assertTrue(retainedLedger.release(retainedNew))
        assertNull(retainedLedger.snapshot(retainedProvider))

        val freshLedger = LiveProviderDescriptorLedger()
        val freshProvider = HostileIdentity()
        val freshOld = reserve(
            freshLedger,
            freshProvider,
            ProviderDescriptorRetentionRole.Desired,
            first = true,
        )
        assertRecorded(freshLedger.recordDescriptor(freshOld, JPEG), first = true)
        val freshResult = AtomicReference<ProviderDescriptorReserveResult>()

        runOnThreadsInOrder(
            first = { assertTrue(freshLedger.release(freshOld)) },
            second = {
                freshResult.set(
                    freshLedger.reserve(
                        freshProvider,
                        ProviderDescriptorRetentionRole.Candidate,
                    ),
                )
            },
        )

        val freshNew = (freshResult.get() as ProviderDescriptorReserveResult.Reserved).also {
            assertTrue(it.firstAdmission)
        }.token
        assertNull(freshLedger.snapshot(freshProvider)?.descriptor)
        assertRecorded(freshLedger.recordDescriptor(freshNew, PNG), first = true)
        assertEquals(1, freshLedger.snapshot(freshProvider)?.liveReferenceCount)
        assertTrue(freshLedger.release(freshNew))
        assertEquals(0, freshLedger.retainedDescriptorCount)
    }

    @Test
    fun handoffAndEitherTokenReleaseAreLinearizableInBothOrders() {
        val predecessorFirst = handoffFixture()
        val predecessorFirstResult = AtomicReference<Boolean>()
        runOnThreadsInOrder(
            first = { assertTrue(predecessorFirst.ledger.release(predecessorFirst.predecessor)) },
            second = {
                predecessorFirstResult.set(
                    predecessorFirst.ledger.handoff(
                        predecessorFirst.predecessor,
                        predecessorFirst.successor,
                        ProviderDescriptorRetentionRole.Active,
                    ),
                )
            },
        )
        assertFalse(predecessorFirstResult.get())
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Candidate to 1),
            predecessorFirst.ledger.snapshot(predecessorFirst.provider)?.retentionCounts,
        )
        assertTrue(predecessorFirst.ledger.release(predecessorFirst.successor))
        assertNull(predecessorFirst.ledger.snapshot(predecessorFirst.provider))

        val handoffBeforePredecessor = handoffFixture()
        val predecessorReleaseResult = AtomicReference<Boolean>()
        runOnThreadsInOrder(
            first = {
                assertTrue(
                    handoffBeforePredecessor.ledger.handoff(
                        handoffBeforePredecessor.predecessor,
                        handoffBeforePredecessor.successor,
                        ProviderDescriptorRetentionRole.Active,
                    ),
                )
            },
            second = {
                predecessorReleaseResult.set(
                    handoffBeforePredecessor.ledger.release(handoffBeforePredecessor.predecessor),
                )
            },
        )
        assertFalse(predecessorReleaseResult.get())
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Active to 1),
            handoffBeforePredecessor.ledger.snapshot(handoffBeforePredecessor.provider)?.retentionCounts,
        )
        assertTrue(handoffBeforePredecessor.ledger.release(handoffBeforePredecessor.successor))
        assertNull(handoffBeforePredecessor.ledger.snapshot(handoffBeforePredecessor.provider))

        val successorFirst = handoffFixture()
        val successorFirstResult = AtomicReference<Boolean>()
        runOnThreadsInOrder(
            first = { assertTrue(successorFirst.ledger.release(successorFirst.successor)) },
            second = {
                successorFirstResult.set(
                    successorFirst.ledger.handoff(
                        successorFirst.predecessor,
                        successorFirst.successor,
                        ProviderDescriptorRetentionRole.Active,
                    ),
                )
            },
        )
        assertFalse(successorFirstResult.get())
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Desired to 1),
            successorFirst.ledger.snapshot(successorFirst.provider)?.retentionCounts,
        )
        assertTrue(successorFirst.ledger.release(successorFirst.predecessor))
        assertNull(successorFirst.ledger.snapshot(successorFirst.provider))

        val handoffBeforeSuccessor = handoffFixture()
        val successorReleaseResult = AtomicReference<Boolean>()
        runOnThreadsInOrder(
            first = {
                assertTrue(
                    handoffBeforeSuccessor.ledger.handoff(
                        handoffBeforeSuccessor.predecessor,
                        handoffBeforeSuccessor.successor,
                        ProviderDescriptorRetentionRole.Active,
                    ),
                )
            },
            second = {
                successorReleaseResult.set(
                    handoffBeforeSuccessor.ledger.release(handoffBeforeSuccessor.successor),
                )
            },
        )
        assertTrue(successorReleaseResult.get())
        assertNull(handoffBeforeSuccessor.ledger.snapshot(handoffBeforeSuccessor.provider))
    }

    private fun reserve(
        ledger: LiveProviderDescriptorLedger,
        provider: Any,
        role: ProviderDescriptorRetentionRole,
        first: Boolean,
    ): ProviderDescriptorRetentionToken {
        val result = ledger.reserve(provider, role)
        assertTrue(result is ProviderDescriptorReserveResult.Reserved)
        result as ProviderDescriptorReserveResult.Reserved
        assertEquals(first, result.firstAdmission)
        return result.token
    }

    private fun fork(
        ledger: LiveProviderDescriptorLedger,
        source: ProviderDescriptorRetentionToken,
        role: ProviderDescriptorRetentionRole,
    ): ProviderDescriptorRetentionToken {
        val result = ledger.fork(source, role)
        assertTrue(result is ProviderDescriptorForkResult.Forked)
        return (result as ProviderDescriptorForkResult.Forked).token
    }

    private fun assertRecorded(result: ProviderDescriptorRecordResult, first: Boolean) {
        assertEquals(ProviderDescriptorRecordResult.Recorded(first), result)
    }

    private fun handoffFixture(): HandoffFixture {
        val ledger = LiveProviderDescriptorLedger()
        val provider = HostileIdentity()
        val predecessor = reserve(
            ledger,
            provider,
            ProviderDescriptorRetentionRole.Desired,
            first = true,
        )
        val successor = fork(ledger, predecessor, ProviderDescriptorRetentionRole.Candidate)
        assertRecorded(ledger.recordDescriptor(predecessor, JPEG), first = true)
        assertRecorded(ledger.recordDescriptor(successor, JPEG), first = false)
        return HandoffFixture(ledger, provider, predecessor, successor)
    }

    private fun runOnThreadsInOrder(
        first: () -> Unit,
        second: () -> Unit,
    ) {
        val firstCompleted = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val firstThread = Thread {
            try {
                first()
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            } finally {
                firstCompleted.countDown()
            }
        }.apply { isDaemon = true }
        val secondThread = Thread {
            try {
                check(firstCompleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "First ordered action did not complete"
                }
                second()
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }.apply { isDaemon = true }
        firstThread.start()
        secondThread.start()
        joinOrFail(firstThread)
        joinOrFail(secondThread)
        failure.get()?.let { throw it }
    }

    private fun runConcurrently(actions: List<() -> Unit>) {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val threads = actions.map { action ->
            Thread {
                try {
                    check(start.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "Concurrent actions did not start"
                    }
                    action()
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }.apply { isDaemon = true }
        }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(::joinOrFail)
        failure.get()?.let { throw it }
    }

    private fun joinOrFail(thread: Thread) {
        thread.join(TEST_TIMEOUT_MILLIS)
        check(!thread.isAlive) { "Worker thread did not complete" }
    }

    private class HandoffFixture(
        val ledger: LiveProviderDescriptorLedger,
        val provider: Any,
        val predecessor: ProviderDescriptorRetentionToken,
        val successor: ProviderDescriptorRetentionToken,
    )

    private class HostileIdentity {
        @Suppress("unused")
        val descriptor: Nothing
            get() = error("provider getter must not be called")

        override fun equals(other: Any?): Boolean = error("equals must not be called")

        override fun hashCode(): Int = error("hashCode must not be called")

        override fun toString(): String = error("toString must not be called")
    }

    private companion object {
        const val MAXIMUM_DESCRIPTORS = 7
        const val TEST_TIMEOUT_SECONDS = 5L
        const val TEST_TIMEOUT_MILLIS = TEST_TIMEOUT_SECONDS * 1_000L
        val JPEG = DescriptorSyntax.snapshotOrNull("provider", "JPEG", "image/jpeg")
            ?: error("Invalid fixture")
        val PNG = DescriptorSyntax.snapshotOrNull("provider", "PNG", "image/png")
            ?: error("Invalid fixture")
    }
}
