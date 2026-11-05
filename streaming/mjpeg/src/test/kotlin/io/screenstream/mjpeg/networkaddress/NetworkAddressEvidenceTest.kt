package io.screenstream.mjpeg.networkaddress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NetworkAddressEvidenceTest {
    // Cases: MJPEG-NET-03
    @Test
    fun interfaceInferenceRecognizesAndroidNamesAndLeavesUnknownNamesUnclassified() {
        val fixtures = mapOf(
            "tun0" to InterfaceType.Vpn, "tap1" to InterfaceType.Vpn, "ppp0" to InterfaceType.Vpn,
            "wlan0" to InterfaceType.Wifi, "wifi1" to InterfaceType.Wifi, "p2p0" to InterfaceType.Wifi,
            "softap0" to InterfaceType.Wifi, "softap.0" to InterfaceType.Wifi,
            "ap_br_wlan0" to InterfaceType.Wifi, "ap_br_softap0" to InterfaceType.Wifi,
            "ap12" to InterfaceType.Wifi, "eth0" to InterfaceType.Ethernet,
            "rmnet_data0" to InterfaceType.Mobile, "ccmni0" to InterfaceType.Mobile, "pdp0" to InterfaceType.Mobile,
            "ap" to InterfaceType.Other, "ap2_extra" to InterfaceType.Other, "usb0" to InterfaceType.Other,
            "v4-rmnet_data0" to InterfaceType.Other, "v4-wlan0" to InterfaceType.Other, "unknown0" to InterfaceType.Other,
        )
        for ((name, expected) in fixtures) assertEquals(name, expected, inferInterfaceType(name))
    }

    // Cases: MJPEG-NET-06
    @Test
    fun absenceAuthorityIsPerInterfaceAndIndependentOfMetadataReadiness() {
        fun scan(ready: Boolean, enumerationComplete: Boolean, failed: Set<String> = emptySet()) =
            AndroidNetworkAddressSource.ScanResult(
                platformSnapshot = AndroidNetworkAddressSource.Snapshot(4, emptyList(), emptyMap(), ready),
                interfaces = emptyList(), successfulInterfaces = setOf("checked0"), failedInterfaces = failed,
                enumerationComplete = enumerationComplete,
            )

        val incomplete = scan(ready = false, enumerationComplete = false, failed = setOf("unreadable0"))
        assertFalse(incomplete.complete)
        assertTrue(incomplete.canConfirmAbsence("checked0"))
        assertFalse(incomplete.canConfirmAbsence("unreadable0"))
        assertFalse(incomplete.canConfirmAbsence("unknown0"))
        val enumerated = scan(ready = false, enumerationComplete = true, failed = setOf("unreadable0"))
        assertFalse(enumerated.complete)
        assertTrue(enumerated.canConfirmAbsence("unknown0"))
        assertFalse(enumerated.canConfirmAbsence("unreadable0"))
        assertFalse(scan(ready = false, enumerationComplete = true).complete)
        assertFalse(scan(ready = true, enumerationComplete = false).complete)
        assertTrue(scan(ready = true, enumerationComplete = true).complete)
    }
}
