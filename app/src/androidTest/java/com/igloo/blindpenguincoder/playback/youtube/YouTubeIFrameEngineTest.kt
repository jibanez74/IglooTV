package com.igloo.blindpenguincoder.playback.youtube

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The WebView contract of the trailer surface. The state machine is unit-tested in
 * `TrailerPlayerStateMachineTest`; what this covers is the surface configuration that only a
 * real WebView exposes. The base URL points at TEST-NET-style dead space, so nothing here
 * depends on the network or on the IFrame API actually loading.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeIFrameEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val engines = mutableListOf<TrailerPlayerEngine>()

    @After
    fun releaseAll() {
        instrumentation.runOnMainSync {
            engines.forEach { it.release() }
            engines.clear()
        }
    }

    private fun surface(): FrameLayout {
        lateinit var built: TrailerPlayerEngine
        instrumentation.runOnMainSync {
            built = youTubeIFrameEngine(context, "abc123", "https://example.invalid")
            engines += built
        }
        return built.surface() as FrameLayout
    }

    private fun surfaceWebView(): WebView = surface().getChildAt(0) as WebView

    @Test
    fun theSurfaceWrapsTheWebViewSoComposeHostingCanCompositeVideo() {
        // Device-verified on the Shield: with the WebView as the direct child of Compose's
        // AndroidView holder, Chromium decodes the embed's audio but never draws a frame. The
        // plain view-group wrapper (and a WebChromeClient) is what keeps trailer video visible.
        val surface = surface()
        instrumentation.runOnMainSync {
            assertTrue(surface.getChildAt(0) is WebView)
            assertNotNull((surface.getChildAt(0) as WebView).webChromeClient)
        }
    }

    @Test
    fun theSurfaceAllowsScriptedMediaPlaybackAndNeverTakesFocus() {
        val webView = surfaceWebView()
        instrumentation.runOnMainSync {
            // The embed autoplays from script; a user-gesture requirement would strand it paused.
            assertFalse(webView.settings.mediaPlaybackRequiresUserGesture)
            // All D-pad input belongs to the Compose chrome.
            assertFalse(webView.isFocusable)
            assertFalse(webView.isFocusableInTouchMode)
        }
    }
}
