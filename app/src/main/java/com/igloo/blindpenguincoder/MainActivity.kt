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
        setContent {
            IglooRoot(container)
        }
    }
}
