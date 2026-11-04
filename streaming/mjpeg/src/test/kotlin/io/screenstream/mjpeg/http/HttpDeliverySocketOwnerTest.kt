package io.screenstream.mjpeg.http

import android.os.SystemClock
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.asByteWriteChannel
import io.screenstream.mjpeg.http.HttpDelivery.ServerFailure
import io.screenstream.mjpeg.http.HttpDelivery.ServerState
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.net.BindException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@LooperMode(LooperMode.Mode.PAUSED)
internal class HttpDeliverySocketOwnerTest {
    // Cases: MJPEG-SRV-01, MJPEG-SRV-04
    @Test(timeout = 30_000)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun listenerFailuresMapIndependentlyAndAnotherAddressKeepsServing() = runBlocking {
        val failures = listOf(
            BindException("occupied") to ServerFailure.AddressInUse,
            SecurityException("denied") to ServerFailure.PermissionDenied,
            SocketException("unavailable") to ServerFailure.AddressUnavailable,
            IOException("io") to ServerFailure.IoFailure,
            IllegalStateException("unknown") to ServerFailure.Unknown,
        )
        for ((cause, expected) in failures) {
            val fixture = Fixture()
            fixture.onCreate = { if (it.host == "127.0.0.1") it.startFailure = cause }
            try {
                fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")), 8080)
                assertTrue(fixture.created.isEmpty)
                fixture.start()
                val failed = fixture.delivery.awaitHttpInfo {
                    it.servers.first().state is ServerState.Failed && it.servers.last().state == ServerState.Listening
                }
                assertEquals(expected, (failed.servers.first().state as ServerState.Failed).reason)
                assertNull(failed.fatalCause)
            } finally {
                fixture.close()
            }
        }
    }

    // Cases: MJPEG-SRV-04, MJPEG-SRV-05
    @Test(timeout = 30_000)
    fun retryBudgetCountsEveryAttemptAndManualRetryKeepsTheDeadline() = runBlocking {
        val fixture = Fixture()
        fixture.onCreate = { it.startFailure = BindException("occupied") }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
            fixture.start()
            val first = fixture.next()
            fixture.delivery.awaitHttpInfo { (it.servers.single().state as? ServerState.Failed)?.attempts == 1 }
            first.cleanupFinished.await()
            val id = fixture.delivery.info.value.servers.single().id
            assertNull(withTimeoutOrNull(100.milliseconds) { fixture.created.receive() })
            fixture.delivery.retryServer(id)
            val second = fixture.nextRetry(first)
            assertTrue(second.createdAtElapsedRealtimeMillis - first.listener.cleanupFinishedAtElapsedRealtimeMillis >= 250)
            val third = fixture.nextRetry(second)
            val fourth = fixture.nextRetry(third)
            assertTrue(third.createdAtElapsedRealtimeMillis - second.listener.cleanupFinishedAtElapsedRealtimeMillis >= 250)
            assertTrue(fourth.createdAtElapsedRealtimeMillis - third.listener.cleanupFinishedAtElapsedRealtimeMillis >= 250)
            val exhausted = fixture.delivery.awaitHttpInfo { (it.servers.single().state as? ServerState.Failed)?.attempts == 3 }
            fixture.delivery.retryServer(Uuid.random())
            assertEquals(exhausted.servers, fixture.delivery.info.value.servers)
            assertNull(withTimeoutOrNull(300.milliseconds) { fixture.created.receive() })
            fixture.onCreate = {}
            fixture.delivery.retryServer(id)
            val recovered = fixture.next()
            fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            fixture.delivery.retryServer(id)
            assertNull(withTimeoutOrNull(100.milliseconds) { fixture.created.receive() })
            recovered.listener.ended.complete(Unit)
            val ended = fixture.delivery.awaitHttpInfo { it.servers.single().state is ServerState.Failed }
            assertEquals(ServerState.Failed(ServerFailure.IoFailure, 1), ended.servers.single().state)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-SRV-05, MJPEG-SRV-06
    @Test(timeout = 30_000)
    fun sameSocketReplacementWaitsForCleanupAndSkipsIntermediatePlans() = runBlocking {
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        fixture.onCreate = { if (fixture.records.isEmpty()) it.cleanupGate = cleanup }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
            fixture.start()
            val old = fixture.next()
            fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            fixture.delivery.configureServers(emptyList(), 8080)
            old.listener.cleanupEntered.await()
            fixture.delivery.configureServers(listOf(httpTestAddress(2)), 8080)
            val skipped = fixture.delivery.info.value.servers.single().id
            fixture.delivery.configureServers(emptyList(), 8080)
            fixture.delivery.configureServers(listOf(httpTestAddress(3)), 8080)
            val latest = fixture.delivery.info.value.servers.single().id
            fixture.delivery.retryServer(skipped)
            assertNull(withTimeoutOrNull(100.milliseconds) { fixture.created.receive() })
            cleanup.complete(Unit)
            val successor = fixture.next()
            assertTrue(old.listener.cleanupFinished.isCompleted)
            fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            assertEquals(latest, fixture.delivery.info.value.servers.single().id)
            assertTrue(successor.listener !== old.listener)
        } finally {
            cleanup.complete(Unit)
            fixture.close()
        }
    }

    // Cases: MJPEG-SRV-07
    @Test(timeout = 30_000)
    fun lateStartAndFailureCannotRestoreAuthorityOrOverwriteSuccessor() = runBlocking {
        val fixture = Fixture()
        val start = CompletableDeferred<Unit>()
        fixture.onCreate = {
            if (fixture.records.isEmpty()) {
                it.startGate = start
                it.startFailure = IOException("obsolete start")
            }
        }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
            fixture.start()
            val old = fixture.next()
            old.listener.startEntered.await()
            assertTrue(fixture.delivery.isListenerActive(old.listener))
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8081)
            val successor = fixture.next()
            val current = fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            assertFalse(fixture.delivery.isListenerActive(old.listener))
            assertTrue(fixture.delivery.isListenerActive(successor.listener))
            start.complete(Unit)
            old.listener.cleanupFinished.await()
            assertEquals(current, fixture.delivery.info.value.servers.single())
        } finally {
            start.complete(Unit)
            fixture.close()
        }
    }

    // Cases: MJPEG-SRV-08, MJPEG-OBS-08
    @Test(timeout = 30_000)
    fun sharedTerminalCleanupSurvivesCanceledWaiterAndPublishesFinalSampleAfterSettlement() = runBlocking {
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        fixture.onCreate = { it.cleanupGate = cleanup }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
            fixture.start()
            val physical = fixture.next()
            fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val before = fixture.delivery.info.value.sampledAtElapsedRealtimeMillis
            fixture.delivery.requestStop()
            fixture.delivery.requestStop()
            physical.listener.cleanupEntered.await()
            val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.delivery.stop() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.delivery.stop() }
            first.cancelAndJoin()
            assertTrue(fixture.delivery.info.value.servers.isEmpty())
            assertTrue(fixture.delivery.info.value.clients.isEmpty())
            assertNull(fixture.delivery.webState.value)
            assertFalse(second.isCompleted)
            fixture.delivery.configureServers(listOf(httpTestAddress()), -1)
            assertFalse(fixture.start())
            ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS)
            cleanup.complete(Unit)
            withTimeout(5.seconds) { second.await() }
            assertTrue(physical.listener.cleanupFinished.isCompleted)
            assertTrue(fixture.delivery.info.value.sampledAtElapsedRealtimeMillis >= physical.listener.cleanupFinishedAtElapsedRealtimeMillis)
            assertTrue(fixture.delivery.info.value.sampledAtElapsedRealtimeMillis >= before)
        } finally {
            cleanup.complete(Unit)
            fixture.close()
        }
    }

    // Cases: MJPEG-SRV-09
    @Test(timeout = 30_000)
    fun cleanupFailureStopsSuccessorsAndWaitsForOtherCleanupObligations() = runBlocking {
        val fixture = Fixture()
        val failure = IOException("cleanup failed")
        val held = CompletableDeferred<Unit>()
        fixture.onCreate = {
            if (it.host == "127.0.0.1") it.cleanupFailure = failure else it.cleanupGate = held
        }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")), 8080)
            fixture.start()
            val first = fixture.next()
            val second = fixture.next()
            fixture.delivery.awaitHttpInfo { it.servers.all { server -> server.state == ServerState.Listening } }
            fixture.delivery.configureServers(listOf(httpTestAddress(3), httpTestAddress(2, "127.0.0.2")), 8080)
            fixture.delivery.awaitHttpInfo { it.fatalCause != null && it.servers.isEmpty() }
            assertSame(failure, fixture.delivery.info.value.fatalCause)
            assertTrue(fixture.delivery.info.value.servers.isEmpty())
            val other = if (first.listener.host == "127.0.0.1") second else first
            other.listener.cleanupEntered.await()
            val waiter = async { runCatching { fixture.delivery.stop() } }
            assertFalse(waiter.isCompleted)
            assertEquals(2, fixture.records.size)
            held.complete(Unit)
            assertHttpFailure(failure, withTimeout(5.seconds) { waiter.await() }.exceptionOrNull())
            assertTrue(other.listener.cleanupFinished.isCompleted)
        } finally {
            held.complete(Unit)
            fixture.close(expectFailure = failure)
        }
    }

    // Cases: MJPEG-SRV-09
    @Test(timeout = 30_000)
    fun firstOperationalFatalCauseRemainsSeparateFromLaterCleanupFailure() = runBlocking {
        for (failCleanup in listOf(false, true)) {
            val fixture = Fixture()
            val fatal = AssertionError("operational failure")
            val cleanupFailure = IOException("cleanup failed later")
            val releaseCleanup = CompletableDeferred<Unit>()
            fixture.onCreate = {
                if (it.host == "127.0.0.2") {
                    it.cleanupGate = releaseCleanup
                    if (failCleanup) it.cleanupFailure = cleanupFailure
                }
            }
            try {
                fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")), 8080)
                fixture.start()
                val records = listOf(fixture.next(), fixture.next())
                fixture.delivery.awaitHttpInfo { it.servers.all { server -> server.state == ServerState.Listening } }
                val failing = records.single { it.listener.host == "127.0.0.1" }.listener
                failing.terminationFailure = fatal
                failing.ended.complete(Unit)
                fixture.delivery.awaitHttpInfo { it.fatalCause != null }
                assertSame(fatal, fixture.delivery.info.value.fatalCause)
                records.single { it.listener.host == "127.0.0.2" }.listener.cleanupEntered.await()
                releaseCleanup.complete(Unit)
                val outcome = runCatching { withTimeout(5.seconds) { fixture.delivery.stop() } }
                if (failCleanup) assertHttpFailure(cleanupFailure, outcome.exceptionOrNull()) else assertTrue(outcome.isSuccess)
                assertSame(fatal, fixture.delivery.info.value.fatalCause)
            } finally {
                releaseCleanup.complete(Unit)
                fixture.close(if (failCleanup) cleanupFailure else null)
            }
        }
    }

    // Cases: MJPEG-SRV-05
    @Test(timeout = 30_000)
    fun manualRetryWhileCleanupIsHeldCannotStartAnEngineBeforeRetirement() = runBlocking {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.onCreate = {
            if (fixture.records.isEmpty()) {
                it.startFailure = BindException("occupied")
                it.cleanupGate = release
            }
        }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
            fixture.start()
            val first = fixture.next()
            first.listener.cleanupEntered.await()
            val id = fixture.delivery.info.value.servers.single().id
            fixture.delivery.retryServer(id)
            fixture.delivery.retryServer(id)
            assertEquals(ServerState.Pending, fixture.delivery.info.value.servers.single().state)
            assertNull(withTimeoutOrNull(100.milliseconds) { fixture.created.receive() })
            release.complete(Unit)
            val second = fixture.nextRetry(first)
            assertTrue(first.listener.cleanupFinished.isCompleted)
            assertTrue(second.createdAtElapsedRealtimeMillis - first.listener.cleanupFinishedAtElapsedRealtimeMillis >= 250)
            val ready = fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            assertEquals(id, ready.servers.single().id)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    // Cases: MJPEG-SRV-07, MJPEG-SRV-08
    @Test(timeout = 30_000)
    fun lateConstructionAfterReplacementOrStopNeverInstallsAnObsoleteListener() = runBlocking {
        for (stop in listOf(false, true)) {
            val fixture = Fixture()
            val entered = CompletableDeferred<ControlledHttpListener>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.onCreate = {
                if (it.port == 8080) {
                    entered.complete(it)
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                fixture.delivery.configureServers(listOf(httpTestAddress()), 8080)
                fixture.start()
                val constructing = withTimeout(5.seconds) { entered.await() }
                assertFalse(fixture.delivery.isListenerActive(constructing))
                if (stop) fixture.delivery.requestStop() else fixture.delivery.configureServers(listOf(httpTestAddress()), 8081)
                val successor = if (stop) null else fixture.next()
                val ready = if (stop) null else fixture.delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
                release.countDown()
                withTimeout(5.seconds) { constructing.cleanupFinished.await() }
                assertFalse(fixture.delivery.isListenerActive(constructing))
                if (stop) assertTrue(fixture.delivery.info.value.servers.isEmpty()) else {
                    assertEquals(ready, fixture.delivery.info.value.servers.single())
                    assertTrue(fixture.delivery.isListenerActive(checkNotNull(successor).listener))
                }
            } finally {
                release.countDown()
                fixture.close()
            }
        }
    }

    // Cases: MJPEG-SRV-02, MJPEG-SRV-03
    @Test(timeout = 30_000)
    fun activePlansValidateWithoutReplacingMembershipAndTerminalPlansAreIgnored() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            for (port in listOf(1, 65_535)) {
                fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")), port)
                val before = fixture.delivery.awaitHttpInfo { it.servers.all { server -> server.state == ServerState.Listening } }.servers
                for (invalidPort in listOf(0, 65_536)) {
                    val failure = runCatching { fixture.delivery.configureServers(listOf(httpTestAddress()), invalidPort) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertEquals(before.map { it.id }, fixture.delivery.info.value.servers.map { it.id })
                }
                for (bad in listOf(
                    listOf(httpTestAddress(), httpTestAddress(1, "127.0.0.2")),
                    listOf(httpTestAddress(), httpTestAddress(2)),
                )) {
                    assertTrue(runCatching { fixture.delivery.configureServers(bad, port) }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(before, fixture.delivery.info.value.servers)
                }
                val failure = runCatching {
                    fixture.delivery.configureServers(listOf(httpTestAddress(name = "earlier metadata"), httpTestAddress(2, "127.0.0.3")), port)
                }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals(before.map { it.id }, fixture.delivery.info.value.servers.map { it.id })
                assertEquals(before.map { it.address.ip }, fixture.delivery.info.value.servers.map { it.address.ip })
                fixture.delivery.awaitHttpInfo { it.servers.first().address.interfaceName == "earlier metadata" }
            }
            fixture.delivery.stop()
            fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress()), -1)
            assertTrue(fixture.delivery.info.value.servers.isEmpty())
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-OBS-01, MJPEG-OBS-03, MJPEG-OBS-08, MJPEG-SRV-09
    @Test(timeout = 30_000)
    fun controlPublicationsKeepNonzeroSampleUntilRotationAndFinalCleanupSamples() = runBlocking {
        val fixture = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        fixture.onCreate = { if (it.host == "127.0.0.2") it.cleanupGate = cleanup }
        try {
            fixture.delivery.configureServers(listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")), 8080)
            fixture.start()
            val physical = listOf(fixture.next(), fixture.next())
            fixture.delivery.awaitHttpInfo { it.servers.all { server -> server.state == ServerState.Listening } }
            val first = physical.single { it.listener.host == "127.0.0.1" }
            io.ktor.server.testing.testApplication {
                val routingCompleted = CompletableDeferred<Unit>()
                application {
                    monitor.subscribe(RoutingRoot.RoutingCallStarted) { routingCall: io.ktor.server.routing.RoutingCall ->
                        checkNotNull(routingCall.coroutineContext[Job]).invokeOnCompletion { routingCompleted.complete(Unit) }
                    }
                    sendPipeline.intercept(io.ktor.server.response.ApplicationSendPipeline.After) {
                        val content = subject
                        if (content is io.ktor.http.content.OutgoingContent.WriteChannelContent) {
                            val bytes = kotlinx.io.Buffer()
                            content.writeTo(bytes.asByteWriteChannel())
                            val body = bytes.readByteArray()
                            assertEquals(1L, content.contentLength)
                            assertEquals(1, body.size)
                            proceedWith(io.ktor.http.content.ByteArrayContent(body, content.contentType, content.status))
                        }
                    }
                    routing {
                        get("/") {
                            fixture.delivery.respondMedia(call, first.listener, HttpDelivery.MediaFormat.Jpeg, null, null)
                        }
                    }
                }
                org.junit.Assert.assertArrayEquals(byteArrayOf(1), client.get("/").body<ByteArray>())
                withTimeout(5.seconds) { routingCompleted.await() }
            }
            val sample = fixture.delivery.awaitHttpInfo { it.completedJpegs == 1L && it.clients.isNotEmpty() }
            assertEquals(1L, sample.completedJpegBytes)
            assertTrue(sample.hasConsumer)
            fixture.delivery.configureServers(listOf(httpTestAddress(name = "control edit"), httpTestAddress(2, "127.0.0.2")), 8080)
            val control = fixture.delivery.info.value
            assertEquals("control edit", control.servers.first().address.interfaceName)
            assertEquals(sample.clients, control.clients)
            assertEquals(sample.completedJpegs, control.completedJpegs)
            assertEquals(sample.completedJpegBytes, control.completedJpegBytes)
            assertEquals(sample.sampledAtElapsedRealtimeMillis, control.sampledAtElapsedRealtimeMillis)
            fixture.delivery.updateConfiguration(httpTestProtection(), PostStopImage.Placeholder, httpTestPage())
            val rotation = fixture.delivery.info.value
            assertTrue(rotation.clients.isEmpty())
            assertEquals(1L, rotation.completedJpegs)
            assertEquals(1L, rotation.completedJpegBytes)
            assertTrue(rotation.sampledAtElapsedRealtimeMillis >= sample.sampledAtElapsedRealtimeMillis)
            val fatal = AssertionError("terminal operational failure")
            first.listener.terminationFailure = fatal
            first.listener.ended.complete(Unit)
            val cutoff = fixture.delivery.awaitHttpInfo { it.fatalCause != null && it.servers.isEmpty() }
            assertSame(fatal, cutoff.fatalCause)
            assertEquals(rotation.sampledAtElapsedRealtimeMillis, cutoff.sampledAtElapsedRealtimeMillis)
            assertEquals(1L, cutoff.completedJpegBytes)
            physical.single { it.listener.host == "127.0.0.2" }.listener.cleanupEntered.await()
            ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS)
            cleanup.complete(Unit)
            fixture.delivery.stop()
            assertEquals(1L, fixture.delivery.info.value.completedJpegs)
            assertTrue(fixture.delivery.info.value.sampledAtElapsedRealtimeMillis >= rotation.sampledAtElapsedRealtimeMillis)
        } finally {
            cleanup.complete(Unit)
            fixture.close()
        }
    }

    private data class Record(val listener: ControlledHttpListener, val createdAtElapsedRealtimeMillis: Long) {
        val cleanupFinished get() = listener.cleanupFinished
    }

    private class Fixture {
        val created = Channel<Record>(Channel.UNLIMITED)
        val records: MutableList<Record> = java.util.Collections.synchronizedList(mutableListOf())

        @Volatile
        var onCreate: (ControlledHttpListener) -> Unit = {}
        val delivery = KtorHttpDelivery(httpTestContext()) { parentJob, host, port, _ ->
            val createdAtElapsedRealtimeMillis = SystemClock.elapsedRealtime()
            ControlledHttpListener(parentJob, host, port).also { listener ->
                onCreate(listener)
                val record = Record(listener, createdAtElapsedRealtimeMillis)
                records += record
                created.trySend(record)
            }
        }

        suspend fun start(): Boolean = delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
        suspend fun next(): Record = withTimeout(5.seconds) { created.receive() }
        suspend fun nextRetry(after: Record): Record = withTimeout(5.seconds) {
            after.cleanupFinished.await()
            var record = withTimeoutOrNull(50.milliseconds) { created.receive() }
            while (record == null) {
                ShadowSystemClock.advanceBy(25, TimeUnit.MILLISECONDS)
                record = withTimeoutOrNull(50.milliseconds) { created.receive() }
            }
            record
        }

        suspend fun close(expectFailure: Throwable? = null) {
            synchronized(records) { records.toList() }.forEach {
                it.listener.startGate?.complete(Unit)
                it.listener.cleanupGate?.complete(Unit)
            }
            val result = runCatching { withTimeout(10.seconds) { delivery.stop() } }
            if (expectFailure == null) result.getOrThrow() else assertHttpFailure(expectFailure, result.exceptionOrNull())
        }
    }
}
