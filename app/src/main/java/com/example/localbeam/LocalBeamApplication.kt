package com.example.localbeam

import android.app.Application
import com.example.localbeam.core.AppContainer

/**
 * Owns the process-wide dependency container (see [AppContainer]).
 *
 * Referenced from the manifest as `.LocalBeamApplication`.
 */
class LocalBeamApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
