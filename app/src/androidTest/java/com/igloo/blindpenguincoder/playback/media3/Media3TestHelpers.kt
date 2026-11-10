package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.media.session.MediaController as PlatformMediaController
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/**
 * Pumps the main looper until [condition] holds — the players' work is all posted there — or
 * fails naming [what], with [detail] appended.
 */
internal fun waitFor(
    what: String,
    timeoutMs: Long = 5_000,
    detail: () -> String = { "" },
    condition: () -> Boolean,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        if (condition()) return
        SystemClock.sleep(20)
    }
    throw AssertionError("timed out waiting for $what${detail()}")
}

internal fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

/** [read]'s value, read on the main thread the player lives on. */
internal fun <T> readOnMain(read: () -> T): T {
    var value: Result<T>? = null
    onMain { value = runCatching(read) }
    return checkNotNull(value).getOrThrow()
}

/** Reads [read] on the main thread until it gives [expected], or fails with [failure]. */
internal fun <T> awaitOnMain(expected: T, failure: String, read: () -> T) {
    val deadline = SystemClock.elapsedRealtime() + 5_000
    while (SystemClock.elapsedRealtime() < deadline) {
        if (readOnMain(read) == expected) return
        Thread.sleep(50)
    }
    fail(failure)
}

/** A media button pressed and released through [session]'s platform token, as a remote sends it. */
internal fun pressMediaKey(context: Context, session: MediaSession, keyCode: Int) {
    val controller = PlatformMediaController(context, session.platformToken)
    controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
}
