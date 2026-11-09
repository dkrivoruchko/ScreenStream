package io.screenstream.mjpeg

import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Settings files, address inputs and observable event receipts shared by controller histories. */
internal class MjpegControllerTestInputs(private val temporaryFolder: TemporaryFolder) {
    suspend fun createSettings(data: MjpegSettings.Data = MjpegSettings.Data(web = WebPageSettings(title = "Fixture device"))): MjpegSettings {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(data))
        return MjpegSettings(context)
    }

    suspend fun createSettingsWithBroadIpv4Filter(): MjpegSettings {
        val data = MjpegSettings.Data(web = WebPageSettings(title = "Fixture device"))
        return createSettings(
            data.copy(
                network = data.network.copy(
                    filter = NetworkAddressFilter(
                        families = setOf(AddressFamily.Ipv4),
                        interfaceTypes = InterfaceType.entries.toSet(),
                        categories = AddressCategory.entries.toSet(),
                    ),
                ),
            ),
        )
    }

    suspend fun startModuleWithReadyListener(host: MjpegServiceTestHost) {
        host.controller.startModule()
        awaitReadyListener(host, address())
    }

    suspend fun awaitReadyListener(host: MjpegServiceTestHost, address: NetworkAddress, port: Int = 8080) {
        host.http.configuration.awaitControllerValue { it != null }
        host.monitor.state.value = observedAddressState(listOf(address))
        host.http.planApplications.awaitControllerValue { it.lastOrNull()?.addresses == listOf(address) }
        publishDeliveryCheckpoint(host, address, port)
    }

    suspend fun publishDeliveryCheckpoint(host: MjpegServiceTestHost, address: NetworkAddress, port: Int = 8080) {
        val serverId = Uuid.random()
        host.http.info.value = host.http.info.value.copy(
            servers = listOf(HttpDelivery.ServerInfo(serverId, address, port, HttpDelivery.ServerState.Listening)),
        )
        host.controller.state.awaitControllerValue { it.addressServers.singleOrNull()?.serverId == serverId }
    }

    suspend fun publishDiscovery(host: MjpegServiceTestHost, state: NetworkAddressMonitor.State) {
        if (host.monitor.state.value == state) return
        val applicationsBefore = host.http.planApplications.value.size
        host.monitor.state.value = state
        // Every distinct discovery state applies a plan: Loading keeps the last selection, and Observed is reselected with the current filter.
        // Ready-listener setup already observed the distinct plan; no earlier discovery can trail it.
        host.http.planApplications.awaitControllerValue { it.size > applicationsBefore }
    }

    fun publishServerState(
        host: MjpegServiceTestHost,
        address: NetworkAddress,
        port: Int = 8080,
        state: HttpDelivery.ServerState = HttpDelivery.ServerState.Listening
    ) {
        host.http.info.value = HttpDelivery.Info(servers = listOf(HttpDelivery.ServerInfo(Uuid.random(), address, port, state)))
    }

    suspend fun applySettingsCheckpoint(settings: MjpegSettings, host: MjpegServiceTestHost, title: String) {
        // The public Start/Stop command was enqueued before this distinct settings observation.
        settings.updateData { copy(web = web.copy(title = title)) }
        host.http.configuration.awaitControllerValue { it?.web?.title == title }
    }

    fun assertConsentPending(host: MjpegServiceTestHost, attempt: StreamingModule.CaptureAttemptId) {
        assertEquals(attempt to MjpegCaptureSession.State.Status.WaitingForPermission, host.http.captureStatus.value)
        assertTrue(host.access.projectionCreationFinishes.value.isEmpty())
        assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
    }

    fun address(id: Long = 1, ip: String = "192.168.1.${id + 1}", type: InterfaceType = InterfaceType.Wifi): NetworkAddress =
        NetworkAddress(id, InetAddress.getByName(ip), "fixture$id", type)

    fun vpnNonPrivateIpv4Filter(): NetworkAddressFilter =
        NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Vpn), setOf(AddressCategory.NonPrivate))
}

internal suspend fun <T> StateFlow<T>.awaitControllerValue(predicate: (T) -> Boolean): T = withTimeout(5.seconds) { first(predicate) }
