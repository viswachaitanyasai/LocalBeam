package com.example.localbeam.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.localbeam.MainActivity
import com.example.localbeam.R
import com.example.localbeam.core.CastConstants
import com.example.localbeam.core.NetworkUtils
import com.example.localbeam.core.TimeUtils
import com.example.localbeam.data.model.LocalVideo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Keeps the embedded video server alive while the TV is playing.
 *
 * Android tears down background work aggressively, so the server lives in a
 * foreground service: it keeps serving even when the user switches apps or turns
 * the screen off mid-movie. A partial wake lock keeps the CPU awake so the socket
 * does not stall when the device would otherwise idle.
 */
class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: VideoHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested from notification")
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val content = intent.toStreamContent()
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                if (content == null) {
                    Log.w(TAG, "Start requested without a video")
                    publishFailure("No video was handed to the streaming service.")
                    return START_NOT_STICKY
                }
                // Must happen within a few seconds of startForegroundService().
                promoteToForeground(content)
                startServer(content, deviceName)
            }

            else -> {
                Log.w(TAG, "Unknown intent action ${intent?.action}")
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ caps `dataSync` foreground services; end the session cleanly
     * instead of being killed mid-stream.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType); stopping the stream")
        shutdown()
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        LocalServerBridge.publish(LocalServerState.Idle)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ server ---

    private fun startServer(content: StreamContent, deviceName: String?) {
        scope.launch {
            // A session can be replaced (a different video, or a repositioned stream on
            // the same video), and the previous server still holds the port. Tear it
            // down first, so the new stream can keep the same URL.
            stopServer()

            val candidate = NetworkUtils.localIpv4(this@StreamingService)
            if (candidate == null) {
                publishFailure(
                    "No hotspot or Wi-Fi address found. Turn on the hotspot (or join the TV's network) and try again.",
                )
                return@launch
            }

            val bound = try {
                bindServer(content, candidate.hostAddress)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not bind the streaming server", t)
                publishFailure("Could not start the local server: ${t.message ?: "unknown error"}")
                return@launch
            }

            server = bound
            acquireWakeLock()
            LocalServerBridge.publish(
                LocalServerState.Ready(
                    url = bound.url,
                    host = candidate.hostAddress,
                    port = bound.listeningPort,
                    content = content,
                ),
            )
            Log.i(TAG, "Streaming ${content.fileName} from ${bound.url}")
            notifyReady(deviceName, content)
        }
    }

    /**
     * Binds the server to the hotspot address, walking up from the preferred port
     * when something else already holds it.
     */
    private fun bindServer(content: StreamContent, hostAddress: String): VideoHttpServer {
        var lastError: IOException? = null
        val first = CastConstants.DEFAULT_SERVER_PORT
        val last = first + CastConstants.SERVER_PORT_ATTEMPTS - 1

        for (port in first..last) {
            val attempt = VideoHttpServer(
                content = content,
                byteSource = ContentResolverByteSource(
                    resolver = contentResolver,
                    uri = Uri.parse(content.uri),
                    fallbackLength = content.sizeBytes,
                ),
                bindAddress = hostAddress,
                port = port,
            )
            try {
                attempt.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                return attempt
            } catch (e: IOException) {
                lastError = e
                runCatching { attempt.stop() }
                Log.w(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        throw IOException("no free port in $first-$last", lastError)
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        releaseWakeLock()
    }

    private fun publishFailure(message: String) {
        LocalServerBridge.publish(LocalServerState.Failed(message))
        shutdown()
    }

    private fun shutdown() {
        stopServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ wake lock ---

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire the wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // --------------------------------------------------------- notification ---

    private fun promoteToForeground(content: StreamContent) {
        createChannel()
        val notification = buildNotification(
            title = getString(R.string.notification_preparing),
            text = content.title,
            ongoing = true,
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceTypes)
    }

    private fun notifyReady(deviceName: String?, content: StreamContent) {
        val title = if (deviceName.isNullOrBlank()) {
            getString(R.string.notification_ready)
        } else {
            getString(R.string.notification_streaming_to, deviceName)
        }
        val text = buildString {
            append(content.title)
            if (content.durationMs > 0L) {
                append(" · ").append(TimeUtils.formatDuration(content.durationMs))
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text, ongoing = true))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_streaming_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_streaming_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private val foregroundServiceTypes: Int
        get() = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    // -------------------------------------------------------------- intent API ---

    private fun Intent.toStreamContent(): StreamContent? {
        val uri = getStringExtra(EXTRA_URI) ?: return null
        return StreamContent(
            uri = uri,
            title = getStringExtra(EXTRA_TITLE) ?: "Video",
            mimeType = getStringExtra(EXTRA_MIME).orEmpty(),
            sizeBytes = getLongExtra(EXTRA_SIZE, 0L),
            durationMs = getLongExtra(EXTRA_DURATION, 0L),
            extension = getStringExtra(EXTRA_EXTENSION) ?: "mp4",
            startSeconds = getLongExtra(EXTRA_START_SECONDS, 0L),
            token = getStringExtra(EXTRA_TOKEN).orEmpty(),
        )
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "localbeam.casting"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_LOCK_TAG = "localbeam:stream"

        const val ACTION_START = "com.example.localbeam.action.START_STREAMING"
        const val ACTION_STOP = "com.example.localbeam.action.STOP_STREAMING"

        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MIME = "extra_mime"
        private const val EXTRA_SIZE = "extra_size"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_EXTENSION = "extra_extension"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"
        private const val EXTRA_START_SECONDS = "extra_start_seconds"
        private const val EXTRA_TOKEN = "extra_token"

        /**
         * A fresh URL for every stream. Renderers cache item state per URL, and Samsung
         * TVs refuse a URL they have already played once it has been stopped.
         */
        private fun newStreamToken(): String =
            UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)

        private const val TOKEN_LENGTH = 8

        /**
         * Starts (or restarts) the server for [video].
         *
         * @param startSeconds serve the file from this position on, which is how a seek
         *   is performed on renderers that refuse UPnP `Seek`.
         * @param durationMs best known duration; MediaStore's value is used unless the
         *   renderer knows better, because seeking needs a duration to work from.
         */
        fun start(
            context: Context,
            video: LocalVideo,
            deviceName: String?,
            startSeconds: Long = 0L,
            durationMs: Long = video.durationMs,
        ) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, video.uri)
                putExtra(EXTRA_TITLE, video.title)
                putExtra(EXTRA_MIME, video.mimeType)
                putExtra(EXTRA_SIZE, video.sizeBytes)
                putExtra(EXTRA_DURATION, durationMs)
                putExtra(EXTRA_EXTENSION, video.extension)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_START_SECONDS, startSeconds.coerceAtLeast(0L))
                putExtra(EXTRA_TOKEN, newStreamToken())
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the server and removes the notification. */
        fun stop(context: Context) {
            val intent = Intent(context, StreamingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Could not send stop to the streaming service", it) }
        }
    }
}
