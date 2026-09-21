package com.igloo.blindpenguincoder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, and with no keep-on-screen condition: the system splash hands off at the
        // first Compose frame, where feature/boot/SplashScreen draws the same mark on the same
        // canvas and takes over the hold.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as IglooApplication).container
        // A launch, not a recreation: SessionManager is a process singleton, so a relaunch over a
        // surviving process would otherwise compose the signed-in app — and fire its user-scoped
        // fetches — on the first frame, before restoreOnLaunch() re-published the gate. Has to be
        // synchronous and before setContent; a LaunchedEffect runs a whole composition too late.
        // A configuration change arrives with a saved bundle and keeps the session it had.
        if (savedInstanceState == null) container.sessionManager.beginLaunch()
        setContent {
            IglooRoot(container)
        }
    }
}
