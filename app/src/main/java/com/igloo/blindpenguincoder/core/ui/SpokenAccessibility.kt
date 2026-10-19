package com.igloo.blindpenguincoder.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.concurrent.Executor
import kotlinx.coroutines.delay

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
    return rememberLiveFlag(
        key = accessibilityManager,
        probe = { accessibilityManager.hasSpokenFeedbackService() },
        subscribe = { changed ->
            if (accessibilityManager == null) return@rememberLiveFlag {}
            val accessibilityStateListener =
                AccessibilityManager.AccessibilityStateChangeListener { changed() }
            accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
            val servicesStateObserver = object : ContentObserver(
                Handler(Looper.getMainLooper()),
            ) {
                override fun onChange(selfChange: Boolean) {
                    changed()
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                servicesStateObserver,
            )
            val unsubscribeServices =
                subscribeToServicesState(accessibilityManager, context.mainExecutor, changed)
            val unsubscribe: () -> Unit = {
                accessibilityManager.removeAccessibilityStateChangeListener(
                    accessibilityStateListener,
                )
                context.contentResolver.unregisterContentObserver(servicesStateObserver)
                unsubscribeServices()
            }
            unsubscribe
        },
    )
}

/**
 * A system flag that changes outside the app, read through [probe] and re-read whenever
 * [subscribe]'s callback fires — and twice more on top of that, because the callbacks are not
 * the whole story. A change can be reported before it is true: on a Google TV emulator,
 * TalkBack switched on while the app ran fired the settings callbacks while the accessibility
 * manager still listed no spoken service, and nothing fired again once TalkBack had bound, so
 * the screens kept their sighted layout under a running screen reader. Each change is therefore
 * read again after [settleMs]. And a change made while the app was away (TalkBack switched on
 * in Settings) may report nothing at all, so every resume reads the truth again.
 */
@Composable
internal fun rememberLiveFlag(
    key: Any?,
    probe: () -> Boolean,
    subscribe: (changed: () -> Unit) -> () -> Unit,
    settleMs: Long = SERVICE_BIND_SETTLE_MS,
): Boolean {
    var value by remember(key) { mutableStateOf(probe()) }
    var changes by remember(key) { mutableIntStateOf(0) }
    DisposableEffect(key) {
        val unsubscribe = subscribe { changes += 1 }
        onDispose { unsubscribe() }
    }
    LaunchedEffect(key, changes) {
        if (changes == 0) return@LaunchedEffect
        value = probe()
        delay(settleMs)
        value = probe()
    }
    LifecycleResumeEffect(key) {
        value = probe()
        onPauseOrDispose { }
    }
    return value
}

/** API 33's direct report of a service binding or unbinding; earlier releases have none. */
private fun subscribeToServicesState(
    accessibilityManager: AccessibilityManager,
    mainExecutor: Executor,
    changed: () -> Unit,
): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
    val listener = AccessibilityManager.AccessibilityServicesStateChangeListener { changed() }
    accessibilityManager.addAccessibilityServicesStateChangeListener(mainExecutor, listener)
    return { accessibilityManager.removeAccessibilityServicesStateChangeListener(listener) }
}

private fun AccessibilityManager?.hasSpokenFeedbackService(): Boolean =
    this?.isEnabled == true &&
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()

/** Long enough for TalkBack to finish binding after its settings change is reported. */
private const val SERVICE_BIND_SETTLE_MS = 1_500L
