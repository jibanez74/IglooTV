package com.igloo.blindpenguincoder.core.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.igloo.blindpenguincoder.BuildConfig

/** How this device introduces itself to the server when pairing or signing in. */
data class DeviceIdentity(
    val name: String,
    val platform: String,
    val appVersion: String,
)

fun deviceIdentity(context: Context): DeviceIdentity = DeviceIdentity(
    name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL,
    platform = "android_tv",
    appVersion = BuildConfig.VERSION_NAME,
)
