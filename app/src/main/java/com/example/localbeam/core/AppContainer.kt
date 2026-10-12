package com.example.localbeam.core

import android.content.Context
import com.example.localbeam.LocalBeamApplication
import com.example.localbeam.cast.CastSession
import com.example.localbeam.data.local.MediaStoreVideoSource
import com.example.localbeam.data.remote.DeviceDescriptionParser
import com.example.localbeam.data.remote.SsdpDiscoveryClient
import com.example.localbeam.data.remote.UpnpControlPoint
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection.
 *
 * The graph is tiny (one HTTP client, three collaborators) and lives for the whole
 * process, so a container object held by the [Application] beats pulling in a DI
 * framework.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Shared HTTP client for both halves of the work: fetching UPnP device
     * descriptions during discovery and POSTing AVTransport SOAP actions.
     *
     * Timeouts are deliberately short – everything talked to is on the local
     * network, and a slow TV should not block the UI for 30 seconds.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val descriptionParser: DeviceDescriptionParser by lazy { DeviceDescriptionParser() }

    val discoveryClient: SsdpDiscoveryClient by lazy {
        SsdpDiscoveryClient(appContext, httpClient, descriptionParser)
    }

    val upnpControlPoint: UpnpControlPoint by lazy { UpnpControlPoint(httpClient) }

    val videoSource: MediaStoreVideoSource by lazy { MediaStoreVideoSource(appContext) }

    /** Survives screen changes so the selection and the session stay in sync. */
    val castSession: CastSession by lazy {
        CastSession(appContext, upnpControlPoint)
    }
}

/** `context.appContainer` shorthand used by the ViewModels. */
val Context.appContainer: AppContainer
    get() = (applicationContext as LocalBeamApplication).container
