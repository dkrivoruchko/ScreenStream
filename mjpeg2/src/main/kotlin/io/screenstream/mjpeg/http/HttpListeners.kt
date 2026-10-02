package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.Address
import java.io.IOException
import java.net.BindException
import java.net.Inet6Address
import java.net.SocketException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Listener transitions share delivery admission; physical attempts independently own retirement. */
internal class HttpListeners(
    private val gate: Any,
    parentJob: Job,
    private val createServer: (Address, Int) -> AddressServer,
    private val changed: () -> Unit,
    private val cleanupFailed: () -> Unit,
) {
    private val scope = CoroutineScope(parentJob + Dispatchers.IO)
    private val records = LinkedHashMap<Long, PlannedServer>()
    private val owned = LinkedHashSet<Attempt>()
    private var started = false
    private var closing = false
    private var currentAdmission: (Address, Int) -> Boolean = { _, _ -> false }

    fun start(isAllowed: (Address, Int) -> Boolean) {
        val jobs = synchronized(gate) {
            if (closing) return
            check(!started)
            currentAdmission = isAllowed
            started = true
            records.values.mapNotNull(::schedule)
        }
        jobs.forEach(Job::start)
    }

    fun configure(servers: List<HttpDelivery.DesiredServer>, port: Int) {
        require(port in 1..65535)
        val desired = servers.toList()
        val (retired, jobs) = synchronized(gate) {
            if (closing) return
            val remaining = records.toMutableMap()
            val planned = desired.map { desiredServer ->
                val previous = remaining.remove(desiredServer.address.id)
                if (previous != null && previous.port == port) {
                    previous.address = desiredServer.address
                    previous.admission = desiredServer.admission
                    previous
                } else {
                    previous?.let { remaining[it.address.id] = it }
                    PlannedServer(desiredServer.address, port, desiredServer.admission)
                }
            }
            val retired = remaining.values.mapNotNull { record ->
                record.selected = false
                revoke(record)
            }.toMutableList()
            records.clear()
            planned.forEach { records[it.address.id] = it }
            planned.forEach { record ->
                if (!admitted(record)) {
                    revoke(record)?.let(retired::add)
                    record.state = HttpDelivery.ServerState.PermissionRequired
                } else if (record.state == HttpDelivery.ServerState.PermissionRequired) {
                    record.state = if (record.attempts >= MAX_ATTEMPTS) HttpDelivery.ServerState.Failed(record.lastFailure, record.attempts) else HttpDelivery.ServerState.Pending
                }
            }
            val jobs = planned.mapNotNull(::schedule)
            changed()
            retired to jobs
        }
        retired.forEach(AddressServer::requestStop)
        jobs.forEach(Job::start)
    }

    fun retry(id: HttpDelivery.ServerId) {
        val job = synchronized(gate) {
            if (!started || closing) return
            val record = records.values.firstOrNull { it.id === id && it.state is HttpDelivery.ServerState.Failed && admitted(it) } ?: return
            record.attempts = 0
            record.state = HttpDelivery.ServerState.Pending
            changed()
            schedule(record)
        }
        job?.start()
    }

    /** Exact physical identity plus the current planned address/permission decision. Caller holds gate. */
    fun isActive(server: AddressServer): Boolean = !closing && records.values.any {
        it.server === server && it.selected && admitted(it)
    }

    /** Immutable presentation snapshot, called under the delivery gate. */
    fun snapshot(): List<HttpDelivery.AddressInfo> = if (closing) emptyList() else records.values.map {
        HttpDelivery.AddressInfo(it.id, it.address, it.port, it.state)
    }

    /** Gate-only cutoff lets HTTP close listener admission in the same terminal transaction. */
    fun closeAdmission(): List<AddressServer> {
        if (closing) return emptyList()
        closing = true
        return records.values.mapNotNull {
            it.selected = false
            revoke(it)
        }
    }

    fun requestStop() {
        val servers = synchronized(gate) { closeAdmission().also { changed() } }
        servers.forEach(AddressServer::requestStop)
    }

    suspend fun awaitCleanup() {
        // Closing prevents new attempts, including attempts scheduled by completion callbacks.
        synchronized(gate) { owned.map { it.job } }.joinAll()
    }

    private fun admitted(record: PlannedServer): Boolean = record.admission == HttpDelivery.ServerAdmission.Allowed &&
        (!started || currentAdmission(record.address, record.port))

    private fun revoke(record: PlannedServer): AddressServer? = record.server.also { record.server = null }

    /** Gate-only reservation. No worker exists for permission suppression or an exhausted budget. */
    private fun schedule(record: PlannedServer): Job? {
        if (!started || closing || !record.selected || record.job != null || !admitted(record) || record.attempts >= MAX_ATTEMPTS) return null
        val key = socketKey(record.address, record.port)
        val predecessor = owned.lastOrNull { it.key == key }
        val attempt = Attempt(key)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            predecessor?.job?.join()
            runAttempt(record)
        }
        attempt.job = job
        record.job = job
        owned.add(attempt)
        job.invokeOnCompletion {
            val next = synchronized(gate) {
                owned.remove(attempt)
                if (record.job === job) record.job = null
                if (!closing && record.selected && record.server == null && admitted(record) && record.attempts >= MAX_ATTEMPTS) {
                    record.state = HttpDelivery.ServerState.Failed(record.lastFailure, record.attempts)
                    changed()
                }
                schedule(record)
            }
            next?.start()
        }
        return job
    }

    private suspend fun runAttempt(record: PlannedServer) {
        val selected = synchronized(gate) {
            if (closing || !record.selected || !admitted(record)) return
            record.attempts++
            record.state = HttpDelivery.ServerState.Pending
            changed()
            record.address
        }
        var server: AddressServer? = null
        var failed = false
        try {
            server = createServer(selected, record.port)
            val installed = synchronized(gate) {
                if (closing || !record.selected || !admitted(record)) {
                    record.attempts--
                    false
                } else {
                    record.server = server
                    true
                }
            }
            if (!installed) return
            server.start()
            val listening = synchronized(gate) {
                if (!isActive(server)) false else {
                    record.state = HttpDelivery.ServerState.Listening
                    changed()
                    true
                }
            }
            if (!listening) server.requestStop()
            server.awaitTermination()
            synchronized(gate) {
                if (isActive(server)) {
                    record.lastFailure = HttpDelivery.ServerFailure.IoFailure
                    record.state = HttpDelivery.ServerState.Failed(record.lastFailure, record.attempts)
                    failed = true
                    changed()
                }
            }
        } catch (cause: Exception) {
            synchronized(gate) {
                if (!closing && record.selected && admitted(record)) {
                    record.lastFailure = failure(cause)
                    record.state = HttpDelivery.ServerState.Failed(record.lastFailure, record.attempts)
                    failed = true
                    changed()
                }
            }
        } finally {
            synchronized(gate) {
                if (record.server === server) record.server = null
            }
            // Local ownership survives revocation of active identity and cancellation of its parent.
            withContext(NonCancellable) {
                try { server?.stopAndJoin() } catch (_: Exception) { synchronized(gate) { cleanupFailed() } }
            }
        }
        if (failed && synchronized(gate) { !closing && record.selected && record.attempts < MAX_ATTEMPTS }) delay(RETRY_DELAY_MILLIS.milliseconds)
    }

    private fun socketKey(selected: Address, port: Int): SocketKey {
        val address = selected.ip
        return SocketKey(address.address.toList(), (address as? Inet6Address)?.scopeId, (address as? Inet6Address)?.scopedInterface?.name, port)
    }

    private fun failure(cause: Exception): HttpDelivery.ServerFailure = when (cause) {
        is BindException -> HttpDelivery.ServerFailure.AddressInUse
        is SecurityException -> HttpDelivery.ServerFailure.PermissionDenied
        is SocketException -> HttpDelivery.ServerFailure.AddressUnavailable
        is IOException -> HttpDelivery.ServerFailure.IoFailure
        else -> HttpDelivery.ServerFailure.Unknown
    }

    private class PlannedServer(var address: Address, val port: Int, var admission: HttpDelivery.ServerAdmission) {
        val id = HttpDelivery.ServerId()
        var selected = true
        var attempts = 0
        var lastFailure = HttpDelivery.ServerFailure.IoFailure
        var state: HttpDelivery.ServerState = if (admission == HttpDelivery.ServerAdmission.Allowed) HttpDelivery.ServerState.Pending else HttpDelivery.ServerState.PermissionRequired
        var server: AddressServer? = null
        var job: Job? = null
    }
    private data class SocketKey(val bytes: List<Byte>, val scopeId: Int?, val scopeInterface: String?, val port: Int)
    private class Attempt(val key: SocketKey) { lateinit var job: Job }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L
    }
}
