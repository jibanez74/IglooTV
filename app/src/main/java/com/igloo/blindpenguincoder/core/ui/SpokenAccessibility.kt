package com.igloo.blindpenguincoder.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether a spoken screen reader (TalkBack) is currently running. On Android TV, TalkBack
 * navigation follows input focus rather than traversing the semantics tree, so screens use this
 * to add reading stops — focus targets whose only job is to speak text a d-pad never lands on —
 * without slowing sighted d-pad users down. Tracks service changes live, so toggling TalkBack
 * mid-session recomposes the gated UI.
 */
@Composable
fun rememberSpokenAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var enabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager.hasSpokenFeedbackService())
    }

    DisposableEffect(accessibilityManager) {
        if (accessibilityManager == null) return@DisposableEffect onDispose { }

        val update = {
            enabled = accessibilityManager.hasSpokenFeedbackService()
        }
        val accessibilityStateListener =
            AccessibilityManager.AccessibilityStateChangeListener { update() }
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
        val servicesStateObserver = object : ContentObserver(
            Handler(Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) {
                update()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            servicesStateObserver,
        )

        onDispose {
            accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateListener)
            context.contentResolver.unregisterContentObserver(servicesStateObserver)
        }
    }

    return enabled
}

private fun AccessibilityManager?.hasSpokenFeedbackService(): Boolean =
    this?.isEnabled == true &&
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
