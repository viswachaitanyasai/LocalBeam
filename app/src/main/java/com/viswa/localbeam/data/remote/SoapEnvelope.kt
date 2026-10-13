package com.viswa.localbeam.data.remote

import com.viswa.localbeam.core.CastConstants

/**
 * Builds UPnP control (SOAP) request bodies.
 *
 * The shape is fixed by the UPnP Device Architecture:
 * ```
 * <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
 *             s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
 *   <s:Body>
 *     <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
 *       <InstanceID>0</InstanceID>
 *       <Speed>1</Speed>
 *     </u:Play>
 *   </s:Body>
 * </s:Envelope>
 * ```
 * Argument order matters to some renderers, so a [LinkedHashMap] keeps insertion order.
 */
object SoapEnvelope {

    fun build(
        serviceType: String,
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"").append(CastConstants.SOAP_ENVELOPE_NS).append('"')
        append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in arguments) {
            append('<').append(name).append('>')
            append(SoapXml.escape(value))
            append("</").append(name).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body>")
        append("</s:Envelope>")
    }

    /** Value of the `SOAPACTION` header, including the required quotes. */
    fun soapActionHeader(serviceType: String, action: String): String =
        "\"$serviceType#$action\""

    // ------------------------------------------------------- AVTransport actions ---

    fun setAvTransportUri(uri: String, metadata: String): String = build(
        serviceType = CastConstants.AV_TRANSPORT_SERVICE_TYPE,
        action = "SetAVTransportURI",
        arguments = linkedMapOf(
            "InstanceID" to CastConstants.DEFAULT_INSTANCE_ID.toString(),
            "CurrentURI" to uri,
            "CurrentURIMetaData" to metadata,
        ),
    )

    fun play(speed: String = "1"): String = build(
        serviceType = CastConstants.AV_TRANSPORT_SERVICE_TYPE,
        action = "Play",
        arguments = linkedMapOf(
            "InstanceID" to CastConstants.DEFAULT_INSTANCE_ID.toString(),
            "Speed" to speed,
        ),
    )

    fun pause(): String = simpleAction("Pause")

    fun stop(): String = simpleAction("Stop")

    /** `Target` is either `HH:MM:SS` (REL_TIME) or a frame (`00:12:34.567`). */
    fun seek(target: String, unit: String = "REL_TIME"): String = build(
        serviceType = CastConstants.AV_TRANSPORT_SERVICE_TYPE,
        action = "Seek",
        arguments = linkedMapOf(
            "InstanceID" to CastConstants.DEFAULT_INSTANCE_ID.toString(),
            "Unit" to unit,
            "Target" to target,
        ),
    )

    fun getTransportInfo(): String = simpleAction("GetTransportInfo")

    fun getPositionInfo(): String = simpleAction("GetPositionInfo")

    private fun simpleAction(action: String): String = build(
        serviceType = CastConstants.AV_TRANSPORT_SERVICE_TYPE,
        action = action,
        arguments = linkedMapOf("InstanceID" to CastConstants.DEFAULT_INSTANCE_ID.toString()),
    )
}
