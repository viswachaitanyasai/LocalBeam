package com.viswa.localbeam.data.remote

import android.util.Log
import com.viswa.localbeam.core.CastConstants
import com.viswa.localbeam.core.TimeUtils
import com.viswa.localbeam.data.model.DlnaDevice
import com.viswa.localbeam.data.model.RendererPosition
import com.viswa.localbeam.data.model.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Anything that stops us from controlling the renderer. */
class UpnpException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The UPnP control point: sends AVTransport SOAP actions to the TV and reads its
 * transport state back.
 *
 * Every action is a plain HTTP POST with a strict `SOAPACTION` header, so OkHttp plus
 * [SoapEnvelope] is all that is needed – no SOAP stack.
 */
class UpnpControlPoint(private val httpClient: OkHttpClient) {

    private val xmlMediaType = "text/xml; charset=\"utf-8\"".toMediaType()

    // ------------------------------------------------------------ AVTransport ---

    /** Hands the renderer our streaming URL plus the DIDL-Lite metadata describing it. */
    suspend fun setAvTransportUri(device: DlnaDevice, videoUrl: String, metadata: String) {
        postAction(device, "SetAVTransportURI", SoapEnvelope.setAvTransportUri(videoUrl, metadata))
    }

    suspend fun play(device: DlnaDevice, speed: String = "1") {
        postAction(device, "Play", SoapEnvelope.play(speed))
    }

    suspend fun pause(device: DlnaDevice) {
        postAction(device, "Pause", SoapEnvelope.pause())
    }

    suspend fun stop(device: DlnaDevice) {
        postAction(device, "Stop", SoapEnvelope.stop())
    }

    /**
     * `Seek(Unit=REL_TIME, Target=HH:MM:SS)`.
     *
     * Works because the DIDL metadata advertises `DLNA.ORG_OP=01` and the local server
     * answers HTTP byte ranges: the renderer converts the time into a byte offset itself.
     */
    suspend fun seekTo(device: DlnaDevice, targetSeconds: Long) {
        postAction(device, "Seek", SoapEnvelope.seek(TimeUtils.toRelTime(targetSeconds)))
    }

    suspend fun getTransportState(device: DlnaDevice): TransportState {
        val response = postAction(device, "GetTransportInfo", SoapEnvelope.getTransportInfo())
        return TransportState.fromUpnp(SoapXml.valueOf(response, "CurrentTransportState"))
    }

    suspend fun getPosition(device: DlnaDevice): RendererPosition {
        val response = postAction(device, "GetPositionInfo", SoapEnvelope.getPositionInfo())
        return RendererPosition(
            positionSeconds = TimeUtils.parseRelTime(SoapXml.valueOf(response, "RelTime")) ?: 0L,
            durationSeconds = TimeUtils.parseRelTime(SoapXml.valueOf(response, "TrackDuration")) ?: 0L,
        )
    }

    // ------------------------------------------------------------------ plumbing ---

    /**
     * POSTs one action to the device's AVTransport control URL.
     *
     * @return the raw SOAP body, for the caller to pull values out of.
     * @throws UpnpException on transport failure or a SOAP fault.
     */
    suspend fun postAction(device: DlnaDevice, action: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(device.controlUrl)
                .post(body.toRequestBody(xmlMediaType))
                .header(
                    "SOAPAction",
                    SoapEnvelope.soapActionHeader(CastConstants.AV_TRANSPORT_SERVICE_TYPE, action),
                )
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("User-Agent", SsdpDiscoveryClient.USER_AGENT)
                .header("Connection", "close")
                .build()

            val responseText = try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val detail = SoapXml.faultMessage(text) ?: "HTTP ${response.code}"
                        throw UpnpException("$action failed: $detail")
                    }
                    text
                }
            } catch (upnp: UpnpException) {
                throw upnp
            } catch (t: IOException) {
                Log.w(TAG, "$action could not reach ${device.controlUrl}", t)
                throw UpnpException(
                    "$action failed: the TV did not answer (${t.javaClass.simpleName})",
                    t,
                )
            }

            if (SoapXml.isFault(responseText)) {
                throw UpnpException("$action rejected by the TV: ${SoapXml.faultMessage(responseText)}")
            }
            responseText
        }

    private companion object {
        const val TAG = "UpnpControl"
    }
}
