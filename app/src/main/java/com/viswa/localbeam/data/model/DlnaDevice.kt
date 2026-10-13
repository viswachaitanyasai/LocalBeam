package com.viswa.localbeam.data.model

/**
 * A DLNA MediaRenderer discovered over SSDP, enriched with the details we need to
 * control it: the AVTransport `controlURL` from its device description.
 */
data class DlnaDevice(
    /** `<friendlyName>` of the renderer device, or the TV name as a fallback. */
    val friendlyName: String,
    /** Absolute URL of the AVTransport control endpoint used for SOAP calls. */
    val controlUrl: String,
    /** Where the description came from, e.g. `http://192.168.43.2:49152/description.xml`. */
    val location: String,
    val manufacturer: String? = null,
    val modelName: String? = null,
    /** UPnP unique device name (`uuid:…`), used to de-duplicate SSDP replies. */
    val udn: String? = null,
) {
    /** Stable key for list keys and "same device as before" checks. */
    val id: String get() = udn ?: location

    /** Just the host portion of [location], e.g. `192.168.43.2`. */
    val host: String get() = location.substringAfter("://").substringBefore('/').substringBefore(':')

    /** Port of the device description endpoint, or -1 when not present. */
    val port: Int
        get() {
            val authority = location.substringAfter("://", "").substringBefore('/')
            val portPart = authority.substringAfter(':', "")
            return portPart.toIntOrNull() ?: -1
        }

    /** "Samsung · UE55" when the description carried those fields. */
    val subtitle: String?
        get() = listOfNotNull(manufacturer, modelName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")
            .ifEmpty { null }
}
