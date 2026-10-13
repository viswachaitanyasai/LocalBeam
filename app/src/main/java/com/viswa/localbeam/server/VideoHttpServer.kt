package com.viswa.localbeam.server

import android.util.Log
import com.viswa.localbeam.core.Dlna
import com.viswa.localbeam.core.TimeUtils
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Serves exactly one video file to the LAN, with the HTTP features DLNA needs:
 *
 *  - `HEAD` support (renderers probe the size before deciding to play),
 *  - single `Range` requests answered with `206 Partial Content` + `Content-Range`,
 *    which is what makes seeking work on the TV,
 *  - DLNA response headers (`contentFeatures.dlna.org`, `transferMode.dlna.org`),
 *  - optional `TimeSeekRange.dlna.org` translation for renderers that seek by time,
 *  - a *windowed* view of the file: when [StreamContent.startSeconds] is set, byte 0 of
 *    the stream is that position in the file, so a TV that cannot seek still starts
 *    playing from the requested timestamp.
 *
 * The server binds to the phone's hotspot/Wi-Fi address (never `127.0.0.1`), because
 * the TV can only reach an address on the network it shares with the phone.
 */
class VideoHttpServer(
    private val content: StreamContent,
    private val byteSource: VideoByteSource,
    private val bindAddress: String,
    private val port: Int,
) : NanoHTTPD(bindAddress, port) {

    /** `http://192.168.43.1:8080/video.mp4` – the URL handed to the renderer. */
    val url: String get() = "http://$bindAddress:$port${content.path}"

    private val indexPage: String
        get() = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>LocalBeam</title></head>
            <body style="font-family:sans-serif;padding:2rem">
            <h1>LocalBeam</h1>
            <p>Streaming <strong>${content.title}</strong> to your TV.</p>
            <p>Duration: ${TimeUtils.formatDuration(content.durationMs)}</p>
            <p>Direct link: <a href="${content.path}">${content.path}</a></p>
            </body></html>
        """.trimIndent()

    override fun serve(session: IHTTPSession): Response {
        val method = session.method?.name?.uppercase(Locale.US) ?: "GET"
        val headOnly = method == "HEAD"

        if (method != "GET" && method != "HEAD") {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_TEXT,
                "Only GET and HEAD are supported.",
            )
        }

        val uri = session.uri.orEmpty()
        if (uri.isEmpty() || uri == "/") {
            return if (headOnly) {
                headersOnlyResponse(Response.Status.OK, MIME_HTML, indexPage)
            } else {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, indexPage)
            }
        }

        return serveVideo(session, headOnly)
    }

    // ------------------------------------------------------------------- video ---

    private fun serveVideo(session: IHTTPSession, headOnly: Boolean): Response {
        // Everything below is expressed in *window* coordinates: byte 0 is the first
        // byte this stream exposes, whether that is the start of the file or the
        // position a repositioned stream resumes from.
        val window = StreamWindow.of(content, byteSource.length)
        if (window.totalBytes <= 0L || window.length <= 0L) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_TEXT,
                "The selected video could not be opened.",
            )
        }

        // A renderer that seeks by time tells us nothing about bytes, so translate the
        // target using the duration we know – relative to this window, since that is
        // what the TV has been told the media's timeline is.
        // NanoHTTPD lower-cases request header names while parsing, hence the lookup key.
        val timeSeek = DlnaTimeSeek.parse(
            session.headers[Dlna.TIME_SEEK_RANGE_HEADER.lowercase(Locale.US)],
        )
        val effectiveTimeSeek = timeSeek?.takeIf { window.remainingDurationMs > 0L }

        val range: RangeRequest = when {
            effectiveTimeSeek != null -> {
                val startByte = DlnaTimeSeek.toByteOffset(
                    startSeconds = effectiveTimeSeek.startSeconds,
                    durationMs = window.remainingDurationMs,
                    totalBytes = window.length,
                )
                if (startByte >= window.length) {
                    RangeRequest.Unsatisfiable
                } else {
                    RangeRequest.Partial(startByte, window.length - 1)
                }
            }
            // Time seek without a known duration: sending everything beats guessing.
            timeSeek != null -> RangeRequest.Full
            else -> RangeSpec.parse(session.headers["range"], window.length)
        }

        val extraHeaders = LinkedHashMap<String, String>()
        extraHeaders["Accept-Ranges"] = "bytes"
        extraHeaders["contentFeatures.dlna.org"] = Dlna.CONTENT_FEATURES
        extraHeaders["transferMode.dlna.org"] = Dlna.TRANSFER_MODE_STREAMING
        extraHeaders["Cache-Control"] = "no-store"

        if (effectiveTimeSeek != null) {
            extraHeaders[Dlna.TIME_SEEK_RANGE_HEADER] = DlnaTimeSeek.responseHeader(
                // Reported against the whole file, which is what the header means.
                startSeconds = window.absoluteSeconds(effectiveTimeSeek.startSeconds),
                totalDurationSeconds = content.durationSeconds,
            )
        }

        // A HEAD probe must advertise the stream's length without sending a body.
        if (headOnly) {
            return headersOnlyResponse(
                Response.Status.OK,
                content.contentType,
                window.length,
                extraHeaders,
            )
        }

        val status: Response.Status
        val startOffset: Long
        val length: Long
        when (range) {
            is RangeRequest.Partial -> {
                status = Response.Status.PARTIAL_CONTENT
                startOffset = window.realOffset(range.start)
                length = range.length
                extraHeaders["Content-Range"] =
                    RangeSpec.contentRange(range.start, range.endInclusive, window.length)
            }

            RangeRequest.Full -> {
                status = Response.Status.OK
                startOffset = window.offsetBytes
                length = window.length
            }

            RangeRequest.Unsatisfiable -> {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    MIME_TEXT,
                    "Requested range is outside the file.",
                ).apply {
                    addHeader("content-range", RangeSpec.unsatisfiableContentRange(window.length))
                    addHeader("accept-ranges", "bytes")
                }
            }
        }

        val body: InputStream = try {
            byteSource.open(startOffset, length)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read offset $startOffset of ${content.fileName}", t)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_TEXT,
                "Streaming failed: ${t.javaClass.simpleName}",
            )
        }

        // NanoHTTPD closes the body stream once it has been sent.
        val response = newFixedLengthResponse(status, content.contentType, body, length)
        for ((name, value) in extraHeaders) {
            response.addHeader(name, value)
        }
        return response
    }

    /**
     * NanoHTTPD only suppresses the body for `HEAD` when the response data is empty,
     * so header-only responses pair an empty stream with an explicit `Content-Length`.
     */
    private fun headersOnlyResponse(
        status: Response.Status,
        mimeType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response = respondWithHeaders(
        status,
        mimeType,
        body.toByteArray(Charsets.UTF_8).size.toLong(),
        extraHeaders,
    )

    private fun headersOnlyResponse(
        status: Response.Status,
        mimeType: String,
        length: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response = respondWithHeaders(status, mimeType, length, extraHeaders)

    private fun respondWithHeaders(
        status: Response.Status,
        mimeType: String,
        length: Long,
        extraHeaders: Map<String, String>,
    ): Response {
        val response = newFixedLengthResponse(
            status,
            mimeType,
            ByteArrayInputStream(ByteArray(0)),
            0L,
        )
        response.addHeader("Content-Length", length.toString())
        for ((name, value) in extraHeaders) {
            response.addHeader(name, value)
        }
        return response
    }

    companion object {
        private const val TAG = "VideoHttpServer"
        const val MIME_TEXT = "text/plain; charset=utf-8"
        const val MIME_HTML = "text/html; charset=utf-8"
    }
}
