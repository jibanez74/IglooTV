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
    name = serverDeviceName(
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME),
        Build.MODEL,
    ),
    platform = "android_tv",
    appVersion = BuildConfig.VERSION_NAME,
)

/** The server trims `device_name`, then answers 400 when it runs past this many UTF-8 bytes. */
private const val MAX_DEVICE_NAME_BYTES = 100

/**
 * The user's own name for the TV, or [model] when there is none, cut to what the server accepts.
 * The name is user-editable, so a long one or one in a multibyte script would otherwise fail
 * pairing and sign-in alike.
 */
internal fun serverDeviceName(settingsName: String?, model: String): String =
    (settingsName?.trim()?.takeIf { it.isNotEmpty() } ?: model.trim())
        .truncateToUtf8Bytes(MAX_DEVICE_NAME_BYTES)
        .trimEnd()

/** Cuts on a code-point boundary, so a character is never split across the limit. */
private fun String.truncateToUtf8Bytes(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return substring(0, end)
}
