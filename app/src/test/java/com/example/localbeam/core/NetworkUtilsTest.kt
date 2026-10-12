package com.example.localbeam.core

import com.example.localbeam.core.NetworkUtils.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address ranking decides whether the TV can reach the phone at all, so the
 * interface-name heuristics are pinned down here.
 */
class NetworkUtilsTest {

    private fun candidate(
        name: String,
        ip: String,
        source: Source = Source.INTERFACE_LIST,
    ) = NetworkUtils.Candidate(name, NetworkUtils.ipv4Of(ip)!!, source)

    @Test
    fun `hotspot interface wins over a joined wifi network`() {
        val hotspot = candidate("ap0", "192.168.43.1")
        val wifi = candidate("wlan0", "192.168.1.24")
        assertEquals(hotspot, NetworkUtils.pickBest(listOf(wifi, hotspot)))
    }

    @Test
    fun `modern soft ap names are recognised too`() {
        val softAp = candidate("swlan0", "192.168.232.1")
        val wifi = candidate("wlan0", "10.0.0.42")
        assertEquals(softAp, NetworkUtils.pickBest(listOf(wifi, softAp)))
        assertTrue(NetworkUtils.isHotspotInterface("swlan0"))
        assertTrue(NetworkUtils.isHotspotInterface("softap0"))
        assertTrue(NetworkUtils.isHotspotInterface("wlan1"))
        assertFalse(NetworkUtils.isHotspotInterface("wlan0"))
    }

    @Test
    fun `the subnet gateway address is preferred on the same interface`() {
        val gateway = candidate("ap0", "192.168.43.1")
        val lease = candidate("ap0", "192.168.43.2")
        assertEquals(gateway, NetworkUtils.pickBest(listOf(lease, gateway)))
    }

    @Test
    fun `cellular and p2p interfaces are never used`() {
        // Handing the TV a mobile-data address would guarantee a timeout.
        assertNull(NetworkUtils.pickBest(listOf(candidate("rmnet0", "10.55.1.7"))))
        assertNull(NetworkUtils.pickBest(listOf(candidate("ccmni0", "10.55.1.7"))))
        assertNull(NetworkUtils.pickBest(listOf(candidate("p2p-wlan0-0", "192.168.49.1"))))
    }

    @Test
    fun `link local addresses lose to real ones`() {
        val linkLocal = candidate("wlan0", "169.254.10.20")
        val real = candidate("wlan0", "192.168.0.33")
        assertEquals(real, NetworkUtils.pickBest(listOf(linkLocal, real)))
    }

    @Test
    fun `connectivity manager data breaks ties`() {
        val fromList = candidate("wlan0", "192.168.0.33", Source.INTERFACE_LIST)
        val fromConnectivity = candidate("wlan0", "192.168.0.34", Source.CONNECTIVITY)
        assertEquals(fromConnectivity, NetworkUtils.pickBest(listOf(fromList, fromConnectivity)))
    }

    @Test
    fun `usb tethering is a fallback after an access point`() {
        val ap = candidate("ap0", "192.168.43.1")
        val usb = candidate("rndis0", "192.168.42.129")
        assertEquals(ap, NetworkUtils.pickBest(listOf(usb, ap)))
        assertEquals(usb, NetworkUtils.pickBest(listOf(usb)))
    }

    @Test
    fun `no candidates means no address`() {
        assertNull(NetworkUtils.pickBest(emptyList()))
        assertFalse(NetworkUtils.looksLikeHotspot(null))
    }
}
