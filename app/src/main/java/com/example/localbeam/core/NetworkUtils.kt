package com.example.localbeam.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Finds the IPv4 address the TV should use to reach this phone.
 *
 * This is the single most fragile part of the whole app: if we advertise
 * `127.0.0.1`, `localhost` or the cellular address, the TV will never load the
 * video. When the phone is an access point the interesting address is the
 * hotspot gateway (typically `192.168.43.1`, `192.168.232.1`, …), which lives on
 * the soft-AP interface (`ap0`, `swlan0`, `softap0`, …), not on `wlan0`.
 *
 * Two independent sources are merged:
 *  1. [NetworkInterface.getNetworkInterfaces] – gives every interface with its name.
 *  2. [ConnectivityManager] link properties – gives interfaces Android considers live,
 *     which catches the local-only AP network that listing alone sometimes misses.
 *
 * The ranking itself ([pickBest]) is pure Kotlin so it can be unit tested.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /** Where a candidate address came from; used only for diagnostics. */
    enum class Source { INTERFACE_LIST, CONNECTIVITY }

    data class Candidate(
        val interfaceName: String,
        val address: Inet4Address,
        val source: Source,
    ) {
        /** `192.168.43.1` */
        val hostAddress: String get() = address.hostAddress ?: ""
    }

    // ------------------------------------------------------------ interface names ---

    /**
     * Soft-AP interface names across OEMs. An address on one of these is the
     * hotspot gateway, which is exactly what a TV on the hotspot must dial.
     * Checked on the lower-cased interface name.
     */
    private val AP_INTERFACE_PREFIXES = listOf(
        "swlan",     // Android 11+ soft AP
        "softap",
        "ap",        // ap0 / ap1
        "wlan1",     // classic hotspot interface
        "wlan1.",    // multiple AP instances
    )

    /** Tethered (USB/Ethernet) interfaces – usable, slightly less common. */
    private val TETHER_INTERFACE_PREFIXES = listOf("rndis", "usb", "eth")

    /** Normal Wi-Fi client interface: valid when phone and TV share a router. */
    private val WIFI_INTERFACE_PREFIXES = listOf("wlan")

    /** Interfaces that can never host a reachable stream. */
    private val IGNORED_INTERFACE_PREFIXES = listOf(
        "lo", "dummy", "p2p", "tun", "tap", "ppp", "rmnet", "ccmni", "pdp", "v4-", "sit",
    )

    // ------------------------------------------------------------------ public API ---

    /** Best guess at the LAN address of this phone, or `null` if none is usable. */
    fun localIpv4(context: Context?): Candidate? = pickBest(collectCandidates(context))

    /** True when the interface looks like a soft-AP / hotspot interface. */
    fun isHotspotInterface(interfaceName: String): Boolean {
        val name = interfaceName.lowercase()
        return AP_INTERFACE_PREFIXES.any { name.startsWith(it) }
    }

    // ------------------------------------------------------------------- collection ---

    private fun collectCandidates(context: Context?): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()

        // 1. Ask the OS for every interface.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (nif in Collections.list(interfaces)) {
                    for (address in Collections.list(nif.inetAddresses)) {
                        val ipv4 = address as? Inet4Address ?: continue
                        if (!isUsable(ipv4)) continue
                        val name = nif.name ?: continue
                        found.putIfAbsent(
                            "${name}|${ipv4.hostAddress}",
                            Candidate(name, ipv4, Source.INTERFACE_LIST),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enumerate network interfaces", t)
        }

        // 2. Ask ConnectivityManager what it considers live. This is what surfaces the
        //    "local only" hotspot network on devices where listing alone is incomplete.
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) {
            try {
                // `allNetworks` is deprecated in favour of NetworkCallback, but the
                // callback API is asynchronous and cannot answer "what addresses do I
                // have right now?". This snapshot is what surfaces the local-only
                // hotspot network, and it is only a supplement to the enumeration
                // above (failures are caught), so the deprecation is accepted here.
                @Suppress("DEPRECATION")
                val networks = buildList {
                    addAll(manager.allNetworks.orEmpty())
                    manager.activeNetwork?.let { if (this.none { n -> n == it }) add(it) }
                }
                for (network in networks) {
                    collectFromNetwork(manager, network, found)
                }
            } catch (t: Throwable) {
                // allNetworks can throw on some OEM builds without location permission.
                Log.w(TAG, "ConnectivityManager enumeration failed", t)
            }
        }

        return found.values.toList()
    }

    private fun collectFromNetwork(
        manager: ConnectivityManager,
        network: Network,
        into: MutableMap<String, Candidate>,
    ) {
        val link = try {
            manager.getLinkProperties(network)
        } catch (t: Throwable) {
            null
        } ?: return

        val name = link.interfaceName ?: return
        for (linkAddress in link.linkAddresses) {
            val ipv4 = linkAddress.address as? Inet4Address ?: continue
            if (!isUsable(ipv4)) continue
            into.putIfAbsent(
                "$name|${ipv4.hostAddress}",
                Candidate(name, ipv4, Source.CONNECTIVITY),
            )
        }
    }

    /** Only unicast, non-loopback IPv4 addresses in a range a LAN peer could reach. */
    private fun isUsable(address: Inet4Address): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
            return false
        }
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF

        return when {
            first == 192 && second == 168 -> true          // 192.168.0.0/16
            first == 172 && second in 16..31 -> true       // 172.16.0.0/12
            first == 10 -> true                            // 10.0.0.0/8
            // 169.254.0.0/16 is link-local: useless to a TV, but kept so the UI can
            // explain "no usable address" instead of showing nothing at all.
            first == 169 && second == 254 -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------------- ranking ---

    /**
     * Scores a candidate; higher wins. Exposed for tests.
     *
     * Order of preference: soft-AP interfaces, then tethered, then plain Wi-Fi,
     * and within an interface the classic `.1` gateway address wins.
     */
    fun score(candidate: Candidate): Int {
        val name = candidate.interfaceName.lowercase()
        if (IGNORED_INTERFACE_PREFIXES.any { name.startsWith(it) }) return Int.MIN_VALUE

        var score = when {
            AP_INTERFACE_PREFIXES.any { name.startsWith(it) } -> 100
            TETHER_INTERFACE_PREFIXES.any { name.startsWith(it) } -> 80
            WIFI_INTERFACE_PREFIXES.any { name.startsWith(it) } -> 60
            else -> 40
        }

        val host = candidate.hostAddress
        // The hotspot host is virtually always the first address of the subnet.
        if (host.endsWith(".1")) score += 10
        // Link-local means "DHCP failed"; never prefer it.
        if (host.startsWith("169.254.")) score -= 50
        // Live data straight from ConnectivityManager is more trustworthy.
        if (candidate.source == Source.CONNECTIVITY) score += 3

        return score
    }

    /** Highest scoring candidate, or `null` when the list is empty/all unusable. */
    fun pickBest(candidates: List<Candidate>): Candidate? =
        candidates.filter { score(it) > Int.MIN_VALUE }.maxByOrNull { score(it) }

    /** Convenience for the UI: is this apparently a hotspot-style address? */
    fun looksLikeHotspot(candidate: Candidate?): Boolean =
        candidate != null && isHotspotInterface(candidate.interfaceName)

    /**
     * The [NetworkInterface] owning [candidate]'s address, looked up by name first
     * and then by scanning addresses. `null` when the OS refuses to tell us, in
     * which case callers continue without pinning an interface.
     */
    fun interfaceFor(candidate: Candidate): NetworkInterface? {
        runCatching {
            NetworkInterface.getByName(candidate.interfaceName)?.let { if (it.isUp) return it }
        }
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            Collections.list(interfaces).firstOrNull { nif ->
                Collections.list(nif.inetAddresses).any { it == candidate.address }
            }
        }.getOrNull()
    }

    /** Platform-independent IPv4 factory, used by tests and diagnostics. */
    fun ipv4Of(host: String): Inet4Address? =
        try {
            InetAddress.getByName(host) as? Inet4Address
        } catch (t: Throwable) {
            null
        }
}
