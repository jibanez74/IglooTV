package com.igloo.blindpenguincoder.playback.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The production [TrailerPlayerEngine]: a WebView hosting the official YouTube IFrame API.
 *
 * [embedOrigin] is the Igloo server's origin (`scheme://host[:port]`). The bootstrap page is
 * loaded with it as the base URL, so the embed sees the same real, attributable origin the web
 * client's trailer page has — device-verified: a borrowed `https://www.youtube.com` origin is
 * rejected with embed error 152, while the server origin plays.
 */
fun youTubeIFrameEngine(context: Context, videoKey: String, embedOrigin: String): TrailerPlayerEngine =
    YouTubeIFrameEngine(context, videoKey, embedOrigin)

/**
 * All playback happens inside the embed; this class only builds the WebView, shuttles commands
 * down via `evaluateJavascript`, and marshals bridge callbacks — which arrive on a WebView
 * thread — onto the main thread before emitting them. Everything the page loads is https, so
 * the app's cleartext Igloo traffic never mixes with this stack.
 */
private class YouTubeIFrameEngine(
    context: Context,
    private val videoKey: String,
    private val embedOrigin: String,
) : TrailerPlayerEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Replay covers the attach race: the page starts loading in the constructor, and an event
    // that beats the screen's collector must not strand the player in Loading forever.
    private val _events = MutableSharedFlow<TrailerPlayerEvent>(replay = 64)
    override val events: SharedFlow<TrailerPlayerEvent> = _events

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(Color.BLACK)
        // All D-pad input belongs to the Compose chrome; the surface must never take focus.
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webViewClient = object : WebViewClient() {
            // A click-through to youtube.com/watch would hijack the app; iframe and subresource
            // loads (the embed itself, ytimg, googlevideo) never hit this callback's true arm.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = request.isForMainFrame
        }
        addJavascriptInterface(Bridge(), JS_INTERFACE)
    }

    init {
        // The backend constrains keys to this charset; a value that slipped past it must not be
        // interpolated into the page's script.
        if (SAFE_KEY.matches(videoKey)) {
            webView.loadDataWithBaseURL(embedOrigin, playerHtml(videoKey, embedOrigin), "text/html", "utf-8", null)
        } else {
            post(TrailerPlayerEvent.Error(INVALID_VIDEO_ID))
        }
    }

    override fun surface(): View = webView

    override fun play() = command("igloo.play()")

    override fun pause() = command("igloo.pause()")

    override fun seekTo(seconds: Double) = command("igloo.seek($seconds)")

    override fun onHostPaused() {
        pause()
        webView.onPause()
    }

    override fun onHostResumed() {
        webView.onResume()
    }

    override fun release() {
        webView.removeJavascriptInterface(JS_INTERFACE)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        // destroy() requires the view to be out of the hierarchy first; the AndroidView holding
        // it may not have detached it yet, and destroying an attached WebView crashes its renderer.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun command(script: String) {
        webView.evaluateJavascript(script, null)
    }

    private fun post(event: TrailerPlayerEvent) {
        mainHandler.post { _events.tryEmit(event) }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(durationSec: Double) = post(TrailerPlayerEvent.Ready(durationSec))

        @JavascriptInterface
        fun onStateChange(code: Int) = post(TrailerPlayerEvent.StateChange(code))

        @JavascriptInterface
        fun onError(code: Int) = post(TrailerPlayerEvent.Error(code))

        @JavascriptInterface
        fun onTime(currentSec: Double, durationSec: Double) =
            post(TrailerPlayerEvent.Time(currentSec, durationSec))
    }

    companion object {
        private const val JS_INTERFACE = "IglooNative"

        // Must stay under the screen's ready watchdog: an Error is sticky, so a guard that fires
        // second can never be seen, and this one knows the narrower cause — the API script itself
        // never arrived — so it gets to report first.
        private const val API_LOAD_TIMEOUT_MS = 8_000
        private const val INVALID_VIDEO_ID = 2
        private val SAFE_KEY = Regex("^[A-Za-z0-9_-]{1,64}$")

        private fun playerHtml(videoKey: String, embedOrigin: String): String = """
            <!doctype html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
              #player { position: absolute; inset: 0; width: 100%; height: 100%; }
            </style>
            </head>
            <body>
            <div id="player"></div>
            <script>
              var apiTimeout = setTimeout(function () {
                $JS_INTERFACE.onError(${TrailerPlayerState.API_LOAD_TIMEOUT});
              }, $API_LOAD_TIMEOUT_MS);
              var player = null;
              var timeTimer = null;
              function startTicks() {
                if (timeTimer) return;
                timeTimer = setInterval(function () {
                  if (player && player.getCurrentTime) {
                    $JS_INTERFACE.onTime(player.getCurrentTime(), player.getDuration());
                  }
                }, 500);
              }
              function stopTicks() {
                if (timeTimer) { clearInterval(timeTimer); timeTimer = null; }
              }
              window.onYouTubeIframeAPIReady = function () {
                clearTimeout(apiTimeout);
                player = new YT.Player('player', {
                  videoId: '$videoKey',
                  width: '100%',
                  height: '100%',
                  playerVars: {
                    autoplay: 1, controls: 0, playsinline: 1, rel: 0, modestbranding: 1,
                    enablejsapi: 1, disablekb: 1, fs: 0, origin: '$embedOrigin'
                  },
                  events: {
                    onReady: function (e) { $JS_INTERFACE.onReady(e.target.getDuration()); },
                    onStateChange: function (e) {
                      if (e.data === 1) startTicks(); else stopTicks();
                      $JS_INTERFACE.onStateChange(e.data);
                    },
                    onError: function (e) { $JS_INTERFACE.onError(e.data); }
                  }
                });
              };
              window.igloo = {
                play: function () { if (player) player.playVideo(); },
                pause: function () { if (player) player.pauseVideo(); },
                seek: function (s) { if (player) player.seekTo(s, true); }
              };
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
            </body>
            </html>
        """.trimIndent()
    }
}
