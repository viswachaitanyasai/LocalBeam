package com.viswa.localbeam.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Extracts what we need from a UPnP device description (`LOCATION` from SSDP):
 * the friendly name to show in the list and the absolute AVTransport control URL
 * to send SOAP calls to.
 *
 * Uses the platform's [XmlPullParser], so no XML library is pulled in. Real TVs
 * nest the renderer as an embedded device, which is why the whole `<device>` tree
 * is collected before choosing.
 */
class DeviceDescriptionParser {

    data class Service(
        val serviceType: String,
        val controlUrl: String?,
    )

    private class DeviceNode {
        var friendlyName: String? = null
        var manufacturer: String? = null
        var modelName: String? = null
        var udn: String? = null
        var deviceType: String? = null
        val services = mutableListOf<Service>()
    }

    private class ServiceNode {
        var serviceType: String? = null
        var controlUrl: String? = null
    }

    data class ParsedDescription(
        /** Friendly name of the renderer device, falling back to the root device name. */
        val friendlyName: String,
        val manufacturer: String?,
        val modelName: String?,
        val udn: String?,
        /** Absolute URL of the AVTransport control endpoint, `null` if the device lacks one. */
        val avTransportControlUrl: String?,
        /** Number of `<device>` elements seen, useful for error messages. */
        val deviceCount: Int,
    )

    /** True when this description belongs to a MediaRenderer device. */
    fun parse(location: String, stream: InputStream): ParsedDescription {
        val devices = collectDevices(stream)
        if (devices.isEmpty()) {
            return ParsedDescription(
                friendlyName = location.substringAfter("://").substringBefore('/'),
                manufacturer = null,
                modelName = null,
                udn = null,
                avTransportControlUrl = null,
                deviceCount = 0,
            )
        }

        // Prefer the device that actually exposes AVTransport: on a TV that is the
        // embedded MediaRenderer, not the root device.
        val renderer = devices.firstOrNull { node ->
            node.services.any { it.serviceType.contains(":service:AVTransport:") && it.controlUrl != null }
        } ?: devices.firstOrNull { it.deviceType?.contains("MediaRenderer", true) == true }

        // The root <device> is the last one to close, so it is last in the list.
        val root = devices.last()
        val chosen = renderer ?: root

        val avTransport = chosen.services
            .firstOrNull { it.serviceType.contains(":service:AVTransport:") }
            ?.controlUrl
            ?: devices.asSequence()
                .flatMap { it.services.asSequence() }
                .firstOrNull { it.serviceType.contains(":service:AVTransport:") }
                ?.controlUrl

        return ParsedDescription(
            friendlyName = chosen.friendlyName?.takeIf { it.isNotBlank() }
                ?: root.friendlyName?.takeIf { it.isNotBlank() }
                ?: location.substringAfter("://").substringBefore('/'),
            manufacturer = chosen.manufacturer ?: root.manufacturer,
            modelName = chosen.modelName ?: root.modelName,
            udn = chosen.udn ?: root.udn,
            avTransportControlUrl = avTransport?.let { UrlResolver.resolve(location, it) },
            deviceCount = devices.size,
        )
    }

    private fun collectDevices(stream: InputStream): List<DeviceNode> {
        val devices = mutableListOf<DeviceNode>()
        val stack = ArrayDeque<DeviceNode>()
        var currentService: ServiceNode? = null
        var currentTag: String? = null

        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name?.substringAfterLast(':')?.lowercase() ?: ""
                    currentTag = tag
                    when (tag) {
                        "device" -> stack.addLast(DeviceNode())
                        "service" -> currentService = ServiceNode()
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = parser.text?.trim().orEmpty()
                    val tag = currentTag
                    if (text.isNotEmpty() && tag != null) {
                        // Tag names are lower-cased and de-prefixed when parsed above.
                        val service = currentService
                        if (service != null) {
                            when (tag) {
                                "servicetype" -> service.serviceType = text
                                "controlurl" -> service.controlUrl = text
                            }
                        } else {
                            stack.lastOrNull()?.let { device ->
                                when (tag) {
                                    "friendlyname" -> device.friendlyName = device.friendlyName ?: text
                                    "manufacturer" -> device.manufacturer = device.manufacturer ?: text
                                    "modelname" -> device.modelName = device.modelName ?: text
                                    "udn" -> device.udn = device.udn ?: text
                                    "devicetype" -> device.deviceType = device.deviceType ?: text
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name?.substringAfterLast(':')?.lowercase()) {
                        "service" -> {
                            val service = currentService
                            if (service?.serviceType != null) {
                                stack.lastOrNull()?.services?.add(
                                    Service(
                                        serviceType = service.serviceType.orEmpty(),
                                        controlUrl = service.controlUrl,
                                    ),
                                )
                            }
                            currentService = null
                        }

                        "device" -> stack.removeLastOrNull()?.let { devices.add(it) }
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return devices
    }
}
