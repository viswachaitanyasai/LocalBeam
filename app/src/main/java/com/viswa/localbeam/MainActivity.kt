package com.viswa.localbeam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.viswa.localbeam.ui.LocalBeamApp
import com.viswa.localbeam.ui.theme.LocalBeamTheme

/**
 * Single-activity host. All UI lives in [LocalBeamApp]; the streaming work happens
 * in [com.viswa.localbeam.server.StreamingService] so it survives this activity.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalBeamTheme {
                LocalBeamApp()
            }
        }
    }
}
