package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The lifecycle contract every full-screen player shares (design-system.md section 11.8), and
 * the one rule that makes it correct: **a lifecycle silence is not transport intent.**
 *
 * Configuration teardown preserves the user's saved choice for the replacement engine, while a
 * real background or standby trip fully releases the engine and comes back **paused**, waiting
 * for an explicit Play. The distinction matters because both arrive as the same lifecycle
 * events, and both make the engine report `playWhenReady = false` — which the screen must
 * record as the user's intent in one case and ignore in the other.
 *
 * [playWhenReadyIntent] and [reloadKey] are read during composition and so are snapshot state;
 * the two silence flags are only ever touched from the observer and the event collector, both
 * off the composition, and are deliberately plain fields.
 */
@Stable
internal class PlayerHostLifecycleState(intent: MutableState<Boolean>) {

    /** The user's transport choice, saved so it survives activity recreation. */
    var playWhenReadyIntent: Boolean by intent

    /** Bumped to build a replacement engine: an error Retry, or a return from background. */
    var reloadKey: Int by mutableIntStateOf(0)
        private set

    private var lifecycleSilenced = false
    private var hostPausePending = false
    private var releasedForBackground = false

    /**
     * The engine's own report of its transport intent. Dropped while the host is silenced —
     * that `false` is the standby's, not the user's, and recording it would turn a screensaver
     * into a paused movie the next visit has to un-pause.
     */
    fun onEnginePlayWhenReady(playWhenReady: Boolean) {
        if (!lifecycleSilenced) playWhenReadyIntent = playWhenReady
    }

    /** Build a replacement engine with [playWhenReady] as its starting intent. */
    fun rebuildEngine(playWhenReady: Boolean) {
        playWhenReadyIntent = playWhenReady
        reloadKey += 1
    }

    internal fun onHostPause() {
        lifecycleSilenced = true
        hostPausePending = true
    }

    internal fun onBackgroundRelease() {
        playWhenReadyIntent = false
        releasedForBackground = true
    }

    /** True when the return needs a replacement engine, which [rebuildEngine] then supplies. */
    internal fun onHostResume(): Boolean {
        val rebuilding = releasedForBackground
        releasedForBackground = false
        lifecycleSilenced = false
        if (rebuilding || hostPausePending) playWhenReadyIntent = false
        hostPausePending = false
        return rebuilding
    }
}

/** The intent starts at [initialPlayWhenReady]; every later value is the user's or the host's. */
@Composable
internal fun rememberPlayerHostLifecycle(initialPlayWhenReady: Boolean): PlayerHostLifecycleState {
    val intent = rememberSaveable { mutableStateOf(initialPlayWhenReady) }
    return remember { PlayerHostLifecycleState(intent) }
}

/**
 * Installs [host]'s observer for the current [engine]. [onHostPaused] and [onHostResumed] are
 * the engine's own notifications; [onBackgroundRelease] is the full teardown a non-configuration
 * `ON_STOP` performs (the movie player flushes progress and persists its track selection
 * there first).
 */
@Composable
internal fun PlayerHostLifecycleEffect(
    host: PlayerHostLifecycleState,
    engine: Any,
    onHostPaused: () -> Unit,
    onHostResumed: () -> Unit,
    onBackgroundRelease: () -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(host, engine, lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    host.onHostPause()
                    onHostPaused()
                }
                Lifecycle.Event.ON_STOP -> {
                    // A configuration change tears the composition down and rebuilds it; the
                    // engine goes with it through the screen's own disposal, and the saved
                    // intent must survive. Only a real trip away releases here.
                    if (context.findHostActivity()?.isChangingConfigurations != true) {
                        // The teardown runs first: the movie player persists its track
                        // selection here, and it reads the engine, not this state.
                        onBackgroundRelease()
                        host.onBackgroundRelease()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (host.onHostResume()) {
                        host.rebuildEngine(playWhenReady = false)
                    } else {
                        onHostResumed()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
