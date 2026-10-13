package com.viswa.localbeam.server

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives [VideoHttpServer] through a real socket, because that is the contract the TV
 * depends on: a renderer probes with `HEAD`, then streams with `GET` and seeks with
 * `Range`. Bytes are asserted exactly, so an off-by-one in the range maths fails here
 * instead of showing up as a frozen TV.
 */
class VideoHttpServerTest {

    private companion object {
        const val FILE_SIZE = 200 * 1024
        const val DURATION_MS = 120_000L
    }

    private lateinit var server: VideoHttpServer
    private lateinit var file: File
    private lateinit var payload: ByteArray
    private var port = 0

    /** Servers individual tests start on their own port, e.g. repositioned streams. */
    private val extraServers = mutableListOf<VideoHttpServer>()

    @Before
    fun startServer() {
        // Deterministic, non-repeating bytes so a wrong offset cannot accidentally match.
        payload = ByteArray(FILE_SIZE) { index -> ((index * 37 + 11) and 0xFF).toByte() }
        file = File.createTempFile("localbeam-test", ".mp4").apply {
            writeBytes(payload)
            deleteOnExit()
        }

        server = startServerFor(content())
        port = server.listeningPort
    }

    @After
    fun stopServer() {
        server.stop()
        extraServers.forEach { it.stop() }
        extraServers.clear()
    }

    /** The same file, described the way the service describes it. */
    private fun content(
        startSeconds: Long = 0L,
        durationMs: Long = DURATION_MS,
    ) = StreamContent(
        uri = file.toURI().toString(),
        title = "Sample movie",
        mimeType = "video/mp4",
        sizeBytes = payload.size.toLong(),
        durationMs = durationMs,
        extension = "mp4",
        startSeconds = startSeconds,
    )

    private fun startServerFor(content: StreamContent): VideoHttpServer {
        val started = VideoHttpServer(
            content = content,
            byteSource = FileByteSource(file),
            bindAddress = "127.0.0.1",
            port = 0,
        )
        started.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        return started
    }

    /** Port of a second server that exposes the file from [startSeconds] on. */
    private fun startWindowed(startSeconds: Long, durationMs: Long = DURATION_MS): Int =
        startServerFor(content(startSeconds = startSeconds, durationMs = durationMs))
            .also { extraServers += it }
            .listeningPort

    // ------------------------------------------------------------------ probing ---

    @Test
    fun `head reports the full size with no body`() {
        val response = request(method = "HEAD")

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE.toLong(), response.contentLength)
        assertTrue("HEAD must not carry a body", response.body.isEmpty())
    }

    @Test
    fun `full get streams every byte`() {
        val response = request()

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE, response.body.size)
        assertArrayEquals(payload, response.body)
    }

    @Test
    fun `responses advertise range support and dlna features`() {
        val response = request()

        assertEquals("bytes", response.header("Accept-Ranges"))
        assertEquals("video/mp4", response.header("Content-Type"))
        val features = response.header("contentFeatures.dlna.org").orEmpty()
        assertTrue(features.contains("DLNA.ORG_OP=01"))
        assertEquals("Streaming", response.header("transferMode.dlna.org"))
    }

    // ------------------------------------------------------------------- seeking ---

    @Test
    fun `range request returns exactly the requested bytes`() {
        val response = request(range = "bytes=1000-1999")

        assertEquals(206, response.code)
        assertEquals("bytes 1000-1999/$FILE_SIZE", response.header("Content-Range"))
        assertEquals(1000L, response.contentLength)
        assertArrayEquals(payload.copyOfRange(1000, 2000), response.body)
    }

    @Test
    fun `open ended range runs to the last byte`() {
        val response = request(range = "bytes=204000-")

        assertEquals(206, response.code)
        assertEquals("bytes 204000-204799/$FILE_SIZE", response.header("Content-Range"))
        assertArrayEquals(payload.copyOfRange(204000, FILE_SIZE), response.body)
    }

    @Test
    fun `suffix range counts back from the end`() {
        val response = request(range = "bytes=-512")

        assertEquals(206, response.code)
        assertEquals("bytes 204288-204799/$FILE_SIZE", response.header("Content-Range"))
        assertArrayEquals(payload.copyOfRange(FILE_SIZE - 512, FILE_SIZE), response.body)
    }

    @Test
    fun `range past the end is rejected with 416`() {
        val response = request(range = "bytes=500000-600000")

        assertEquals(416, response.code)
        assertEquals("bytes */$FILE_SIZE", response.header("Content-Range"))
    }

    @Test
    fun `multiple ranges fall back to the whole file rather than a broken partial`() {
        val response = request(range = "bytes=0-99,200-299")

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE, response.body.size)
    }

    @Test
    fun `a head probe with a range still reports the full size`() {
        val response = request(method = "HEAD", range = "bytes=1000-1999")

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE.toLong(), response.contentLength)
        assertTrue(response.body.isEmpty())
    }

    // -------------------------------------------------------------- dlna seeking ---

    @Test
    fun `time seek is translated into a byte offset`() {
        // One minute into a two minute file -> half of 200 KB.
        val response = request(timeSeek = "npt=00:01:00.000-")

        assertEquals(206, response.code)
        assertEquals("bytes 102400-204799/$FILE_SIZE", response.header("Content-Range"))
        assertArrayEquals(payload.copyOfRange(102400, FILE_SIZE), response.body)
        assertTrue(response.header("TimeSeekRange.dlna.org").orEmpty().startsWith("npt=00:01:00.000-"))
    }

    @Test
    fun `time seek of now serves the whole file`() {
        val response = request(timeSeek = "npt=now-")

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE, response.body.size)
    }

    // ----------------------------------------------------- repositioned streams ---
    //
    // What the app does when a TV refuses to seek inside a file: it serves the file
    // from the requested position, so every byte the renderer asks for is measured
    // from the window, not from the start of the movie.

    @Test
    fun `a repositioned stream starts at the requested position`() {
        // Half way into a two minute file.
        val windowPort = startWindowed(startSeconds = 60)

        val response = request(serverPort = windowPort)

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE / 2, response.body.size)
        assertArrayEquals(payload.copyOfRange(FILE_SIZE / 2, FILE_SIZE), response.body)
    }

    @Test
    fun `a repositioned stream reports what is left as its length`() {
        val windowPort = startWindowed(startSeconds = 60)

        val response = request(method = "HEAD", serverPort = windowPort)

        assertEquals(200, response.code)
        assertEquals((FILE_SIZE / 2).toLong(), response.contentLength)
    }

    @Test
    fun `ranges inside a repositioned stream stay inside the window`() {
        val windowPort = startWindowed(startSeconds = 60)
        val offset = FILE_SIZE / 2

        val response = request(range = "bytes=0-99", serverPort = windowPort)

        assertEquals(206, response.code)
        assertEquals("bytes 0-99/${FILE_SIZE / 2}", response.header("Content-Range"))
        assertArrayEquals(payload.copyOfRange(offset, offset + 100), response.body)
    }

    @Test
    fun `an open ended range stops at the end of the file`() {
        val windowPort = startWindowed(startSeconds = 60)
        val virtualStart = FILE_SIZE / 2 - 10

        val response = request(range = "bytes=$virtualStart-", serverPort = windowPort)

        assertEquals(206, response.code)
        assertEquals(
            "bytes $virtualStart-${FILE_SIZE / 2 - 1}/${FILE_SIZE / 2}",
            response.header("Content-Range"),
        )
        assertArrayEquals(payload.copyOfRange(FILE_SIZE - 10, FILE_SIZE), response.body)
    }

    @Test
    fun `time seek inside a repositioned stream is relative to the window`() {
        // 30s into a window that starts at 1:00 of a 2:00 film, so 1:30 of the film.
        val windowPort = startWindowed(startSeconds = 60)

        val response = request(timeSeek = "npt=00:00:30.000-", serverPort = windowPort)

        assertEquals(206, response.code)
        assertEquals(
            "bytes ${FILE_SIZE / 4}-${FILE_SIZE / 2 - 1}/${FILE_SIZE / 2}",
            response.header("Content-Range"),
        )
        assertArrayEquals(payload.copyOfRange(FILE_SIZE * 3 / 4, FILE_SIZE), response.body)
        // The npt answer describes the whole film, not the window.
        assertTrue(
            response.header("TimeSeekRange.dlna.org").orEmpty().startsWith("npt=00:01:30.000-"),
        )
    }

    @Test
    fun `a window without a known duration serves the whole file`() {
        val windowPort = startWindowed(startSeconds = 60, durationMs = 0)

        val response = request(serverPort = windowPort)

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE, response.body.size)
        assertArrayEquals(payload, response.body)
    }

    @Test
    fun `a seek to the very end still leaves a byte to serve`() {
        val windowPort = startWindowed(startSeconds = DURATION_MS / 1000)

        val head = request(method = "HEAD", serverPort = windowPort)
        assertEquals(200, head.code)
        assertEquals(1L, head.contentLength)

        val body = request(serverPort = windowPort)
        assertEquals(200, body.code)
        assertArrayEquals(payload.copyOfRange(FILE_SIZE - 1, FILE_SIZE), body.body)
    }

    // -------------------------------------------------------------------- basics ---

    @Test
    fun `other methods are rejected`() {
        val connection = openConnection("POST")
        connection.doOutput = true
        connection.outputStream.use { it.write("x".toByteArray()) }
        assertEquals(405, connection.responseCode)
        connection.disconnect()
    }

    @Test
    fun `index page is browsable for debugging`() {
        val response = request(path = "/")

        assertEquals(200, response.code)
        assertTrue(response.header("Content-Type").orEmpty().startsWith("text/html"))
        assertTrue(String(response.body).contains("LocalBeam"))
    }

    @Test
    fun `unknown paths still serve the video so a mangled url still plays`() {
        val response = request(path = "/video")

        assertEquals(200, response.code)
        assertEquals(FILE_SIZE, response.body.size)
    }

    // ------------------------------------------------------------------- helpers ---

    private data class TestResponse(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val contentLength: Long,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }

    private fun openConnection(
        method: String = "GET",
        path: String = "/video.mp4",
        range: String? = null,
        timeSeek: String? = null,
        serverPort: Int = port,
    ): HttpURLConnection {
        val connection = URL("http://127.0.0.1:$serverPort$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        range?.let { connection.setRequestProperty("Range", it) }
        timeSeek?.let { connection.setRequestProperty("TimeSeekRange.dlna.org", it) }
        return connection
    }

    private fun request(
        method: String = "GET",
        path: String = "/video.mp4",
        range: String? = null,
        timeSeek: String? = null,
        serverPort: Int = port,
    ): TestResponse {
        val connection = openConnection(method, path, range, timeSeek, serverPort)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)

            // Java's header map carries a null key holding the status line; drop it.
            val headers = LinkedHashMap<String, List<String>>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null) headers[name] = values ?: emptyList()
            }

            return TestResponse(
                code = code,
                headers = headers,
                body = body,
                contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Minimal file-backed [VideoByteSource] standing in for MediaStore. */
    private class FileByteSource(private val file: File) : VideoByteSource {

        override val length: Long get() = file.length()

        override fun open(start: Long, count: Long): InputStream {
            val stream = FileInputStream(file)
            stream.channel.position(start)
            return LimitedInputStream(stream, count)
        }
    }
}
