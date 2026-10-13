package com.viswa.localbeam.data.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.viswa.localbeam.core.CastConstants
import com.viswa.localbeam.core.NetworkUtils
import com.viswa.localbeam.data.model.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** What a discovery run reports while it is running. */
sealed interface DiscoveryEvent {
    data class DeviceFound(val device: DlnaDevice) : DiscoveryEvent

    /** Recoverable/terminal problem worth showing to the user. */
    data class Failed(val message: String) : DiscoveryEvent

    /** Emitted once, after the last possible result. */
    data class Completed(val deviceCount: Int) : DiscoveryEvent
}

/**
 * SSDP (UPnP discovery) client.
 *
 * Flow of a scan:
 *  1. take a [WifiManager.MulticastLock] so the hotspot does not filter multicast,
 *  2. bind a [MulticastSocket] to an ephemeral port on the hotspot/Wi-Fi interface,
 *  3. send `M-SEARCH` for MediaRenderers (repeated, plus a subnet-broadcast copy as
 *     a fallback for access points that drop link-local multicast),
 *  4. parse replies, fetch each `LOCATION` description and emit a [DlnaDevice] as
 *     soon as it resolves.
 *
 * Results stream out as they arrive, so the UI can fill in while scanning.
 */
class SsdpDiscoveryClient(
    context: Context,
    private val httpClient: OkHttpClient,
    private val descriptionParser: DeviceDescriptionParser = DeviceDescriptionParser(),
) {

    private val appContext: Context = context.applicationContext

    fun discover(
        targets: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Flow<DiscoveryEvent> = channelFlow {
        val searchTargets = targets.ifEmpty { listOf(CastConstants.ST_MEDIA_RENDERER) }
        val candidate = NetworkUtils.localIpv4(appContext)
        if (candidate == null) {
            send(
                DiscoveryEvent.Failed(
                    "No local network address found. Enable the hotspot (or join the TV's Wi-Fi) and try again.",
                ),
            )
            send(DiscoveryEvent.Completed(0))
            return@channelFlow
        }

        val networkInterface = NetworkUtils.interfaceFor(candidate)
        if (networkInterface == null) {
            Log.w(TAG, "No NetworkInterface for ${candidate.hostAddress}; continuing without binding")
        }

        // In-flight description reads for this scan only, cancelled with the flow.
        val pending = mutableListOf<Job>()
        val multicastLock = acquireMulticastLock()
        var socket: MulticastSocket? = null
        try {
            socket = openSocket(networkInterface)
            val group = InetAddress.getByName(CastConstants.SSDP_ADDRESS)

            val started = System.currentTimeMillis()
            val deadline = started + timeoutMs
            sendSearchBurst(socket, group, networkInterface, candidate, searchTargets)

            val seen = HashSet<String>()
            val found = AtomicInteger(0)
            var secondRoundSent = false

            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE)
                val received = try {
                    socket.receive(packet)
                    true
                } catch (timeout: SocketTimeoutException) {
                    false
                } catch (closed: IOException) {
                    // Socket closed by cancellation.
                    break
                }

                if (!received) {
                    // Some renderers answer the second probe only; ask once more.
                    if (!secondRoundSent && System.currentTimeMillis() - started > RETRY_AFTER_MS) {
                        secondRoundSent = true
                        sendSearchBurst(socket, group, networkInterface, candidate, searchTargets)
                    }
                    continue
                }

                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val response = SsdpParser.parse(text) ?: continue
                val location = response.location ?: continue
                if (!response.isMediaRenderer) continue

                val key = response.deviceKey ?: location
                if (!seen.add(key)) continue

                pending += launch {
                    val device = fetchDevice(location)
                    if (device != null) {
                        found.incrementAndGet()
                        send(DiscoveryEvent.DeviceFound(device))
                    }
                }
            }

            pending.toList().joinAll()
            send(DiscoveryEvent.Completed(found.get()))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "SSDP discovery failed", t)
            send(DiscoveryEvent.Failed(t.message ?: "Discovery failed"))
            send(DiscoveryEvent.Completed(0))
        } finally {
            runCatching { socket?.close() }
            runCatching { multicastLock?.release() }
            pending.clear()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ internals ---

    private suspend fun ProducerScope<DiscoveryEvent>.sendSearchBurst(
        socket: MulticastSocket,
        group: InetAddress,
        networkInterface: NetworkInterface?,
        local: NetworkUtils.Candidate,
        targets: List<String>,
    ) {
        val payloads = targets.map { SsdpParser.buildMSearch(it, CastConstants.SSDP_MX_SECONDS) }
        val destinations = buildList {
            add(InetSocketAddress(group, CastConstants.SSDP_PORT))
            // Link-local multicast is sometimes dropped by the soft AP; a directed
            // broadcast to the subnet usually still reaches renderers.
            broadcastAddress(networkInterface, local)?.let { add(InetSocketAddress(it, CastConstants.SSDP_PORT)) }
        }

        // Repeat: UDP is lossy and cheap to duplicate.
        repeat(SEARCH_REPEATS) { round ->
            for (payload in payloads) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                for (destination in destinations) {
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, destination)) }
                }
            }
            if (round < SEARCH_REPEATS - 1) {
                delay(SEARCH_REPEAT_GAP_MS)
            }
        }
    }

    private fun openSocket(networkInterface: NetworkInterface?): MulticastSocket {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        // Ephemeral port: unicast replies come back to the source port of our M-SEARCH.
        socket.bind(InetSocketAddress(0))
        socket.soTimeout = READ_TIMEOUT_MS
        socket.timeToLive = 4

        if (networkInterface != null) {
            runCatching { socket.networkInterface = networkInterface }
                .onFailure { Log.w(TAG, "Could not pin SSDP socket to ${networkInterface.name}", it) }
            runCatching {
                socket.joinGroup(
                    InetSocketAddress(InetAddress.getByName(CastConstants.SSDP_ADDRESS), CastConstants.SSDP_PORT),
                    networkInterface,
                )
            }.onFailure {
                // Hotspots occasionally refuse multicast joins; unicast replies still work.
                Log.w(TAG, "Could not join SSDP multicast group on ${networkInterface.name}", it)
            }
        }
        return socket
    }

    private fun broadcastAddress(
        networkInterface: NetworkInterface?,
        local: NetworkUtils.Candidate,
    ): InetAddress? {
        val fromInterface = networkInterface?.let { nif ->
            runCatching {
                nif.interfaceAddresses
                    .firstOrNull { it.address == local.address }
                    ?.broadcast
            }.getOrNull()
        }
        if (fromInterface != null) return fromInterface

        // Derive the /24 broadcast as a last resort (hotspot subnets are /24 in practice).
        val bytes = local.address.address
        if (bytes.size != 4) return null
        return runCatching {
            Inet4Address.getByAddress(byteArrayOf(bytes[0], bytes[1], bytes[2], 0xFF.toByte()))
        }.getOrNull()
    }

    private suspend fun fetchDevice(location: String): DlnaDevice? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(location)
            .header("User-Agent", USER_AGENT)
            .header("Connection", "close")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Description request failed: HTTP ${response.code} for $location")
                    return@use null
                }
                val description = descriptionParser.parse(location, response.body.byteStream())
                val controlUrl = description.avTransportControlUrl
                if (controlUrl == null) {
                    Log.i(TAG, "Device at $location exposes no AVTransport service; skipping")
                    return@use null
                }
                DlnaDevice(
                    friendlyName = description.friendlyName,
                    controlUrl = controlUrl,
                    location = location,
                    manufacturer = description.manufacturer,
                    modelName = description.modelName,
                    udn = description.udn,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read device description at $location", t)
            null
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.createMulticastLock(CastConstants.MULTICAST_LOCK_TAG)?.apply {
            // Not reference counted: one scan takes the lock, one scan releases it.
            setReferenceCounted(false)
            acquire()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "MulticastLock unavailable; discovery may miss packets", t)
        null
    }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val MAX_PACKET_SIZE = 4096
        private const val READ_TIMEOUT_MS = 400
        private const val SEARCH_REPEATS = 3
        private const val SEARCH_REPEAT_GAP_MS = 250L
        private const val RETRY_AFTER_MS = 1_500L
        const val DEFAULT_TIMEOUT_MS = 6_000L
        const val USER_AGENT = "Android/1.0 UPnP/1.0 LocalBeam/1.0"
    }
}
