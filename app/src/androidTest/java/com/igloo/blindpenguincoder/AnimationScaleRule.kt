package com.igloo.blindpenguincoder

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Turns system animations off for one test class and puts the previous value back afterwards.
 *
 * The welcome screen's ambient backdrop loops forever, and an infinite animation never lets the
 * Compose test clock go idle — so a test that renders it has to disable animations first.
 * `rememberReducedMotion` reads exactly this setting and observes it live.
 *
 * Restoring matters as much as setting: `animator_duration_scale` is global device state, and
 * leaving it at 0 makes unrelated suites fail. PinEntryAccessibilityTest is sensitive to it.
 *
 * A device that had the setting unset gets it *deleted* again rather than written back as "1" —
 * writing a value the device never had is still leaving the device changed.
 */
class AnimationScaleRule : ExternalResource() {

    private var previous: String? = null

    override fun before() {
        previous = read()
        write("0")
    }

    override fun after() {
        previous?.let(::write) ?: execute("settings delete global $SETTING")
    }

    /** Null when the setting is unset on this device, which is not the same as "1". */
    private fun read(): String? {
        val value = execute("settings get global $SETTING").trim()
        return value.takeUnless { it.isEmpty() || it == "null" }
    }

    private fun write(value: String) {
        execute("settings put global $SETTING $value")
    }

    private fun execute(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .use { it.readBytes().decodeToString() }
            }

    private companion object {
        const val SETTING = "animator_duration_scale"
    }
}
