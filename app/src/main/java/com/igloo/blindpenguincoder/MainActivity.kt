package com.igloo.blindpenguincoder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IglooApplication).container
        setContent {
            IglooRoot(container)
        }
    }
}
