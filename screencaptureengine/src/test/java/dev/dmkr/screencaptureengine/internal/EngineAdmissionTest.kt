package dev.dmkr.screencaptureengine.internal

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineAdmissionTest {
    @Test
    fun twoConcurrentAcquiresHaveExactlyOneWinner() {
        val admission = EngineAdmission()
        val start = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = List(2) {
                executor.submit<ActiveAdmission?> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    admission.acquire()
                }
            }
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val results = attempts.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it != null })
            assertNull(admission.acquire())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun activeRetiringAndPoisonedStatesRejectAcquireImmediately() {
        val activeEngine = EngineAdmission()
        assertNotNull(activeEngine.acquire())
        assertNull(activeEngine.acquire())

        val retiringEngine = EngineAdmission()
        retiringEngine.markRetiring(checkNotNull(retiringEngine.acquire()))
        assertNull(retiringEngine.acquire())

        val poisonedEngine = EngineAdmission()
        poisonedEngine.poison(checkNotNull(poisonedEngine.acquire()))
        assertNull(poisonedEngine.acquire())
    }

    @Test
    fun exactActiveAdmissionTransitionsToRetiringExactlyOnce() {
        val engine = EngineAdmission()
        val active = checkNotNull(engine.acquire())

        val retiring = engine.markRetiring(active)

        assertNotNull(retiring)
        assertThrows(IllegalStateException::class.java) { engine.markRetiring(active) }
        assertNull(engine.acquire())
    }

    @Test
    fun staleCrossEngineReplayAndWrongPhaseTokensCannotMutateState() {
        val first = EngineAdmission()
        val firstActive = checkNotNull(first.acquire())
        val second = EngineAdmission()
        val secondActive = checkNotNull(second.acquire())

        assertThrows(IllegalStateException::class.java) { first.markRetiring(secondActive) }
        val firstRetiring = first.markRetiring(firstActive)
        assertThrows(IllegalStateException::class.java) { first.markRetiring(firstActive) }
        assertThrows(IllegalStateException::class.java) { first.poison(firstActive) }
        assertThrows(IllegalStateException::class.java) { second.poison(firstRetiring) }

        assertNull(first.acquire())
        assertNull(second.acquire())
        second.poison(secondActive)
        first.poison(firstRetiring)
        assertThrows(IllegalStateException::class.java) { first.poison(firstRetiring) }
    }

    @Test
    fun constructedLookalikeTokensCannotForgeAuthority() {
        val activeEngine = EngineAdmission()
        val exactActive = checkNotNull(activeEngine.acquire())
        assertThrows(IllegalStateException::class.java) { activeEngine.markRetiring(ActiveAdmission()) }
        val exactRetiring = activeEngine.markRetiring(exactActive)
        assertThrows(IllegalStateException::class.java) { activeEngine.poison(RetiringAdmission()) }
        activeEngine.poison(exactRetiring)
    }

    @Test
    fun tokensExposeOnlyOpaqueReferenceIdentity() {
        val first = checkNotNull(EngineAdmission().acquire())
        val second = checkNotNull(EngineAdmission().acquire())

        assertNotSame(first, second)
        assertNotEquals(first, second)
    }

    @Test
    fun poisonRacingRetirementHasOneLinearizedWinnerAndRemainsSticky() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val engine = EngineAdmission()
                val active = checkNotNull(engine.acquire())
                val start = CyclicBarrier(3)
                val retire = executor.submit<RetiringAdmission?> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    runCatching { engine.markRetiring(active) }.getOrNull()
                }
                val poison = executor.submit<Boolean> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    runCatching { engine.poison(active) }.isSuccess
                }
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val retiring = retire.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val poisonedFromActive = poison.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(1, listOf(retiring != null, poisonedFromActive).count { it })
                if (retiring != null) engine.poison(retiring)
                assertNull(engine.acquire())
                assertThrows(IllegalStateException::class.java) { engine.poison(active) }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun twoConcurrentRetiringPoisonsHaveExactlyOneWinner() {
        val engine = EngineAdmission()
        val retiring = engine.markRetiring(checkNotNull(engine.acquire()))
        val start = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = List(2) {
                executor.submit<Boolean> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    runCatching { engine.poison(retiring) }.isSuccess
                }
            }
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(1, attempts.count { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) })
            assertNull(engine.acquire())
            assertThrows(IllegalStateException::class.java) { engine.poison(retiring) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun everyInvalidOperationPreservesItsExactPreState() {
        assertVacantPreserved { markRetiring(ActiveAdmission()) }
        assertVacantPreserved { poison(ActiveAdmission()) }
        assertVacantPreserved { poison(RetiringAdmission()) }

        assertOccupiedPreserved { _ -> markRetiring(ActiveAdmission()) }
        assertOccupiedPreserved { _ -> poison(ActiveAdmission()) }
        assertOccupiedPreserved { _ -> markRetiring(crossActiveToken()) }
        assertOccupiedPreserved { _ -> poison(crossActiveToken()) }
        assertOccupiedPreserved { _ -> poison(crossRetiringToken()) }

        assertRetiringPreserved { active, _ -> markRetiring(active) }
        assertRetiringPreserved { active, _ -> poison(active) }
        assertRetiringPreserved { _, _ -> markRetiring(crossActiveToken()) }
        assertRetiringPreserved { _, _ -> poison(crossActiveToken()) }
        assertRetiringPreserved { _, _ -> poison(RetiringAdmission()) }
        assertRetiringPreserved { _, _ -> poison(crossRetiringToken()) }

        assertPoisonedPreserved { active, _ -> markRetiring(active) }
        assertPoisonedPreserved { active, _ -> poison(active) }
        assertPoisonedPreserved { _, retiring -> poison(retiring) }
        assertPoisonedPreserved { _, _ -> poison(ActiveAdmission()) }
        assertPoisonedPreserved { _, _ -> poison(RetiringAdmission()) }
        assertPoisonedPreserved { _, _ -> markRetiring(crossActiveToken()) }
        assertPoisonedPreserved { _, _ -> poison(crossActiveToken()) }
        assertPoisonedPreserved { _, _ -> poison(crossRetiringToken()) }
    }

    private fun assertVacantPreserved(invalid: EngineAdmission.() -> Unit) {
        val engine = EngineAdmission()
        assertThrows(IllegalStateException::class.java) { engine.invalid() }
        assertNotNull(engine.acquire())
    }

    private fun assertOccupiedPreserved(invalid: EngineAdmission.(ActiveAdmission) -> Unit) {
        val engine = EngineAdmission()
        val active = checkNotNull(engine.acquire())
        assertThrows(IllegalStateException::class.java) { engine.invalid(active) }
        engine.poison(active)
    }

    private fun assertRetiringPreserved(
        invalid: EngineAdmission.(ActiveAdmission, RetiringAdmission) -> Unit,
    ) {
        val engine = EngineAdmission()
        val active = checkNotNull(engine.acquire())
        val retiring = engine.markRetiring(active)
        assertThrows(IllegalStateException::class.java) { engine.invalid(active, retiring) }
        engine.poison(retiring)
    }

    private fun assertPoisonedPreserved(
        invalid: EngineAdmission.(ActiveAdmission, RetiringAdmission) -> Unit,
    ) {
        val engine = EngineAdmission()
        val active = checkNotNull(engine.acquire())
        val retiring = engine.markRetiring(active)
        engine.poison(retiring)
        assertThrows(IllegalStateException::class.java) { engine.invalid(active, retiring) }
        assertNull(engine.acquire())
    }

    private fun crossActiveToken(): ActiveAdmission = checkNotNull(EngineAdmission().acquire())

    private fun crossRetiringToken(): RetiringAdmission {
        val engine = EngineAdmission()
        return engine.markRetiring(checkNotNull(engine.acquire()))
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
