# The trailer player (extra videos)

How a movie's extra videos — trailers, special features — get played, and why this player is built
the way it is. Landed on `feature/play-extras` (`f81f20f`, reviewed and corrected in `eb9f1bc`).

`docs/design-system.md` §11.8.1 is the **normative spec**: what the surface must look like and how
it must behave. This document is the explanation behind it — the mechanism, the contracts between
the pieces, and the sharp edges. Where the two disagree, the design system wins.

It is written in two halves. **Part 1** is plain language and assumes nothing; **Part 2** is the
technical reference, with the code quoted from source.

---

# Part 1 — How it works, in plain language

## What an "extra" actually is

The Igloo backend does not store trailer video files. For each movie it stores a short list of
*extra videos*, and each one is little more than a **YouTube video id** plus a title and a type
("trailer", "special feature", "other"). There is no file to stream, no HLS ladder, nothing for the
server to transcode. The one thing the backend does serve is the **thumbnail**, proxied through
`/api/youtube/thumbnails/{key}` so the TV never fetches images from Google directly.

That single fact decides the whole design: **to play an extra, we have to play it on YouTube's own
player.** So the TV app opens a hidden browser view, loads YouTube's official embedded player into
it, and then hides every piece of YouTube's own interface and draws Igloo's controls on top. The
web client does exactly the same thing, so behaviour matches across the two apps.

This is a deliberate exception to the app's usual rules. Everything else in Igloo plays through
ExoPlayer and talks only to your own server. `AGENTS.md` records the exception explicitly: the
trailer player's browser view may talk to `youtube.com`, `ytimg.com` and `googlevideo.com`, for this
one purpose, and **no Igloo login, cookie or token is ever handed to it**.

## What you see

On a movie's detail page, the *Extras* rail shows a card per extra video. Press Center on one and
the app opens a full-screen player over the top of the detail page. Nothing navigates — the detail
page stays exactly as it was, just covered up, so closing the player puts you back where you were
instantly, with the highlight back on **the same card you launched from** (not the first card in the
rail — that distinction is tested).

The player starts playing on its own. Its controls — Back, and a Rewind / Play-Pause / Forward row
over a thin progress bar — **fade away after four seconds** so nothing sits on top of the picture.
They come straight back the moment you touch the remote. They only fade while the video is actually
playing; if it is paused or still loading, they stay put, because a frozen picture with no interface
on it looks like the app has crashed.

Because the controls can be invisible, the remote works two ways:

- **While they are hidden**, the d-pad drives the video: Left and Right jump ten seconds back and
  forward, Center plays or pauses, Up or Down simply brings the controls back. A key press never
  "clicks" a button you cannot see.
- **While they are visible**, the d-pad moves between the buttons as normal.

The dedicated media keys on a TV remote (play/pause, rewind, fast-forward) always work, whichever of
the two states you are in. There is no volume control: on a TV, volume belongs to the remote and the
TV itself.

Press **Back** once to hide the controls, again to leave. When the video reaches its end the player
closes itself, which is what the web client does too.

## What it deliberately does not do

Trailers are not tracked. There is no "resume where you left off", no progress saved to the server,
no chapters, no quality selector, no subtitle picker. Those all belong to the real movie player
(§11.8), which is a different thing entirely. A trailer is two minutes long; remembering your place
in it is not worth the machinery.

## When something goes wrong

Plenty of YouTube videos simply cannot be played outside youtube.com — the uploader disabled
embedding, or the video is blocked in your region, or it has been deleted. YouTube reports these as
numeric codes, and the player translates each one into a plain sentence, for example *"YouTube
doesn't allow this video to play outside youtube.com."* The screen then shows that sentence with a
single **Retry** button, which throws the whole embedded player away and builds a fresh one.

Two separate timers guard against the player never starting at all. The inner one gives YouTube's
own script eight seconds to arrive; if it doesn't, you are told the *YouTube player* failed to load,
which is the more useful message. The outer one waits twelve seconds for the video to actually
become ready and reports a generic failure. Whichever fires first is what you see, and the first
error always sticks — a later, vaguer failure never overwrites a specific one you already read.

Finally, if the TV goes to standby the video is paused, and coming back does *not* restart it — a TV
in standby has to be silent, and resuming is your decision. If Android recreates the screen
underneath (a language change, say), the player reopens but the trailer starts again from 0:00; a
browser view cannot be saved and restored, and for a trailer that is an accepted trade.

---

# Part 2 — Technical reference

## 2.1 The shape of the feature

Four production files were added, all on this branch:

| File | Role |
|---|---|
| `playback/youtube/TrailerPlayerState.kt` | The reducer. Pure Kotlin, no Android types, JVM-testable. |
| `playback/youtube/TrailerPlayerEngine.kt` | The engine interface and its event alphabet — the test seam. |
| `playback/youtube/YouTubeIFrameEngine.kt` | The production engine: a `WebView` + the YouTube IFrame API. |
| `feature/player/TrailerPlayerScreen.kt` | The whole Compose UI: chrome, focus graph, key map, watchdog. |

Plus one extraction — `core/ui/FocusPinning.kt`, `Modifier.pinnedToScreen()` was private inside
`MovieDetailsScreen.kt` and is now shared — and small additions to `core/ui/MediaFormatting.kt`
(`formatTimecode`, `formatSpokenTime`), `core/ui/IglooIcons.kt` (`Pause`, `Rewind`, `FastForward`,
`ArrowBack`) and `core/ui/IglooPosterCard.kt` (an `actionLabel` override).

## 2.2 Data flow: from the wire to the embed

```
GET /api/movies/{id}/details
        │  MovieDetailsData.extraVideos: List<MovieExtraVideo>   (key, type, site, official)
        ▼
MovieRepository.movieDetails(id)                       data/repository/MovieRepository.kt:49
        ▼
MovieDetailsViewModel.extraVideos(details, apiBaseUrl) feature/movies/MovieDetailsViewModel.kt:522
        │  filter site == "youtube" · sort trailer→special feature→other · title tie-break
        │  thumbnailUrl = youtubeThumbnailUrl(...)  ← the ONLY backend hop
        ▼
ExtraVideoUi(id, title, typeLabel, thumbnailUrl, key)  MovieDetailsViewModel.kt:48
        ▼
ExtraVideosSection card, onClick = { onPlayExtra(video) }   MovieDetailsSections.kt:320
        ▼
IglooApp: trailerRequest = TrailerRequest(video.key, video.title, video.typeLabel)   IglooApp.kt:297
        ▼
TrailerPlayerScreen(videoKey = key, …)                 feature/player/TrailerPlayerScreen.kt:96
        ▼
YouTubeIFrameEngine → WebView → https://www.youtube.com/iframe_api
```

The wire model (`data/model/Movies.kt:174`) is the source of everything:

```kotlin
data class MovieExtraVideo(
    val id: Long,
    val title: String,
    @SerialName("external_id") val externalId: SqlNullString? = null,
    val key: String,
    val type: String,
    val site: String,
    val official: Boolean,
    …
)
```

The view model keeps only YouTube entries, because the backend's thumbnail proxy has no other site
(web parity), and re-sorts because the API's `ORDER BY type, title` puts trailers last
(`MovieDetailsViewModel.kt:522`):

```kotlin
private fun extraVideos(details: MovieDetailsData, apiBaseUrl: String): List<ExtraVideoUi> =
    details.extraVideos
        .filter { normalizedVideoValue(it.site) == "youtube" }
        .sortedWith(
            compareBy<MovieExtraVideo> { extraVideoSortRank(it.type) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        .map {
            ExtraVideoUi(
                id = it.id,
                title = it.title,
                typeLabel = extraVideoTypeLabel(it.type),
                thumbnailUrl = youtubeThumbnailUrl(apiBaseUrl, it.key),
                key = it.key,
            )
        }
```

`key` is what this branch added to `ExtraVideoUi`; everything before it already existed for the
detail screen's rail.

**The boundary, stated plainly:** `youtubeThumbnailUrl` (`images/ImageUrlResolver.kt:27`) returns
`"$apiBaseUrl/youtube/thumbnails/$id"` — the *poster image* is proxied and authenticated. The
*video* is not proxied, not downloaded, and never touches Igloo. Only the id travels.

The card became actionable in `MovieDetailsSections.kt:320`, using the new `actionLabel` so
TalkBack announces the action that actually happens:

```kotlin
onClick = { onPlayExtra(video) },
actionLabel = "Play ${video.title}",
```

## 2.3 The three layers, and why there is no nav graph

`IglooApp` composes overlays in-tree: **shell → details → player**. `onPlayExtra` sets state, and
that is the whole "navigation" (`IglooApp.kt:174`):

```kotlin
var trailerRequest by rememberSaveable(stateSaver = TrailerRequest.Saver) {
    mutableStateOf<TrailerRequest?>(null)
}
val trailerOpen = trailerRequest != null
```

`TrailerRequest` (`IglooApp.kt:117`) holds only what the screen renders, and is saveable so the
overlay survives activity recreation:

```kotlin
private data class TrailerRequest(val key: String, val title: String, val typeLabel: String) {
    companion object {
        val Saver: Saver<TrailerRequest?, List<String>> = Saver(
            save = { request ->
                if (request == null) emptyList() else listOf(request.key, request.title, request.typeLabel)
            },
            restore = { saved ->
                if (saved.isEmpty()) null else TrailerRequest(saved[0], saved[1], saved[2])
            },
        )
    }
}
```

**Focus restore is the host's job**, exactly as it is for the details overlay. The extras rail parks
`extrasReturnRequester` on its last-focused card, and closing runs in the callback rather than an
effect, for the detach-race reason the details close documents (`IglooApp.kt:181`):

```kotlin
val closeTrailer = {
    trailerRequest = null
    if (!extrasReturnRequester.requestFocusSafely()) {
        contentStartRequester.requestFocusSafely()
    }
}
```

Every host `BackHandler` is gated on `!trailerOpen` (`IglooApp.kt:202`, `:219`, `:224`) so the
player's own handler wins while it is mounted. And the details layer beneath is hidden from
TalkBack but *not* stripped (`IglooApp.kt:287`):

```kotlin
Modifier.semantics { hideFromAccessibility() }
```

`hideFromAccessibility`, not `clearAndSetSemantics`, for the same reason as the shell under details:
the nodes stay in the semantics tree, so a test can still assert on what is not traversable.

The engine factory is an `IglooApp` parameter with a production default, which is what lets the
instrumented tests drive the whole shell with a fake (`IglooApp.kt:147`):

```kotlin
trailerEngineFactory: (Context, String) -> TrailerPlayerEngine = { context, key ->
    youTubeIFrameEngine(context, key, serverOrigin)
},
```

## 2.4 The engine seam

`playback/youtube/TrailerPlayerEngine.kt` cuts the abstraction **exactly at the JS bridge** — one
level lower and the reducer would be untestable, one level higher and the tests would need a
WebView.

```kotlin
sealed interface TrailerPlayerEvent {
    data class Ready(val durationSec: Double) : TrailerPlayerEvent
    data class StateChange(val code: Int) : TrailerPlayerEvent
    data class Error(val code: Int) : TrailerPlayerEvent
    data class Time(val currentSec: Double, val durationSec: Double) : TrailerPlayerEvent
}

fun TrailerPlayerState.onEvent(event: TrailerPlayerEvent): TrailerPlayerState = when (event) {
    is TrailerPlayerEvent.Ready -> onReady(event.durationSec)
    is TrailerPlayerEvent.StateChange -> onYtStateChange(event.code)
    is TrailerPlayerEvent.Error -> onError(event.code)
    is TrailerPlayerEvent.Time -> onTime(event.currentSec, event.durationSec)
}

interface TrailerPlayerEngine {
    val events: SharedFlow<TrailerPlayerEvent>

    /** The video surface to mount, or null when the engine draws nothing (fakes). */
    fun surface(): View?

    fun play()
    fun pause()

    fun seekTo(seconds: Double)

    /** Host lifecycle went to the background: stop playback — a TV in standby must be silent. */
    fun onHostPaused()

    /** Host lifecycle returned; playback stays paused for the user to resume. */
    fun onHostResumed()

    /** Tear down the surface; the engine is unusable afterwards. */
    fun release()
}
```

Two contracts worth naming: all members are **main-thread only**, and `events` **replays**, because
the real engine begins loading in its constructor and may emit before the screen's collector
attaches.

## 2.5 The production engine: WebView + IFrame API

`YouTubeIFrameEngine.kt:25`. The factory takes the server origin, which is the host's to know:

```kotlin
fun youTubeIFrameEngine(context: Context, videoKey: String, embedOrigin: String): TrailerPlayerEngine =
    YouTubeIFrameEngine(context, videoKey, embedOrigin)
```

### The base URL is the Igloo server's origin

```kotlin
init {
    // The backend constrains keys to this charset; a value that slipped past it must not be
    // interpolated into the page's script.
    if (SAFE_KEY.matches(videoKey)) {
        webView.loadDataWithBaseURL(embedOrigin, playerHtml(videoKey, embedOrigin), "text/html", "utf-8", null)
    } else {
        post(TrailerPlayerEvent.Error(INVALID_VIDEO_ID))
    }
}
```

with `private val SAFE_KEY = Regex("^[A-Za-z0-9_-]{1,64}$")` and `INVALID_VIDEO_ID = 2`. The regex
is not cosmetic: `videoKey` is interpolated into a `<script>` block, so anything outside the
YouTube-id charset is refused before it can reach the page.

The base URL matters just as much. The page is loaded under the **Igloo server's own origin** — the
same real, attributable origin the web client's trailer page has. Device-verified 2026-08-16: a
borrowed `https://www.youtube.com` base URL is rejected by the embed with **error 152**; the server
origin plays.

### The WebView is locked down and unfocusable

```kotlin
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
```

`mediaPlaybackRequiresUserGesture = false` is what lets `autoplay: 1` work on a device with no
touch. The `shouldOverrideUrlLoading` guard blocks any main-frame navigation — a tap-through to a
YouTube watch page would otherwise replace the app's player with a web page it cannot control —
while leaving the iframe and its subresources alone.

### The page

`playerHtml(videoKey, embedOrigin)` builds a minimal black document, creates `YT.Player`, and
exposes exactly three commands:

```js
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
```

The page is a Kotlin raw string, so `$JS_INTERFACE` interpolates to the bridge's name,
`IglooNative`, and `$videoKey` / `$embedOrigin` to the constructor arguments. The `<script
src="https://www.youtube.com/iframe_api">` tag at the end of the body is the only external
resource the page requests directly.

`controls: 0` and `disablekb: 1` are the two that matter for TV: YouTube draws nothing and handles
no keys, so the Compose chrome is the only interface and there is never a second focus system
competing with it. Time ticks run at 500 ms and **only while playing** (`if (e.data === 1)
startTicks(); else stopTicks();`).

Commands go down as one-liners:

```kotlin
override fun play() = command("igloo.play()")
override fun pause() = command("igloo.pause()")
override fun seekTo(seconds: Double) = command("igloo.seek($seconds)")
```

### Callbacks come back up marshalled

`@JavascriptInterface` methods are invoked on a WebView thread, so every event is hopped to the main
thread before it is emitted:

```kotlin
private val _events = MutableSharedFlow<TrailerPlayerEvent>(replay = 64)

private fun post(event: TrailerPlayerEvent) {
    mainHandler.post { _events.tryEmit(event) }
}
```

`replay = 64` covers the attach race described above.

### Teardown

```kotlin
override fun release() {
    webView.removeJavascriptInterface(JS_INTERFACE)
    webView.stopLoading()
    webView.loadUrl("about:blank")
    // destroy() requires the view to be out of the hierarchy first; the AndroidView holding
    // it may not have detached it yet, and destroying an attached WebView crashes its renderer.
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.destroy()
}
```

## 2.6 The reducer

`TrailerPlayerState.kt` is deliberately free of Android types so the entire machine can be tested on
the JVM.

```kotlin
enum class TrailerPhase { Loading, Playing, Paused, Buffering, Ended, Error }

data class TrailerPlayerState(
    val phase: TrailerPhase = TrailerPhase.Loading,
    val ready: Boolean = false,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val errorMessage: String? = null,
)
```

| Phase | What the screen renders |
|---|---|
| `Loading` | Chrome pinned visible, centred "Loading trailer…" |
| `Playing` | Chrome may auto-hide; Play/Pause shows Pause |
| `Paused` | Chrome pinned visible |
| `Buffering` | Chrome pinned visible; time and duration preserved |
| `Ended` | Screen calls `onClose()` — web parity |
| `Error` | `PlayerError` replaces the chrome entirely |

`YT.PlayerState` codes map straight across (`TrailerPlayerState.kt:31`):

```kotlin
-1, 5 -> copy(phase = TrailerPhase.Loading)   // unstarted, cued
 0    -> copy(phase = TrailerPhase.Ended)
 1    -> copy(phase = TrailerPhase.Playing)
 2    -> copy(phase = TrailerPhase.Paused)
 3    -> copy(phase = TrailerPhase.Buffering)
 else -> this                                  // a new code is YouTube's business
```

Error codes become sentences (`TrailerPlayerState.kt:87`):

| Code | Message |
|---|---|
| `-2` (`API_LOAD_TIMEOUT`, synthesized in-page) | "The YouTube player took too long to load." |
| `2` | "This video's YouTube id is invalid." |
| `5` | "YouTube's player hit a playback error." |
| `100` | "This video was not found on YouTube." |
| `101, 150, 152, 153` | "YouTube doesn't allow this video to play outside youtube.com." |
| anything else | "The trailer could not be played." |

`152`/`153` are the newer members of the embed-restriction family that YouTube's embed has returned
since roughly 2024; `152` is the one observed on-device when it rejects the embedding context.

### Two invariants

**1. An error is sticky.** Every transition opens with the same guard, so the first failure the user
read is never downgraded by a later, vaguer one:

```kotlin
fun onError(code: Int): TrailerPlayerState = when (phase) {
    TrailerPhase.Error -> this
    else -> copy(phase = TrailerPhase.Error, errorMessage = errorMessage(code))
}
```

**2. A known duration never shrinks back to zero.** The IFrame API reports `0` while buffering, and
a seek bar that collapses mid-play reads as a crash:

```kotlin
private fun keptDuration(incoming: Double): Double =
    if (incoming > 0.0) incoming else durationSec
```

### Seeking

The clamp lives in the reducer so the engine and the bar are always given the *same* number:

```kotlin
fun seekTarget(deltaSec: Double): Double = clampToPlayable(currentTimeSec + deltaSec)

fun onSeekApplied(targetSec: Double): TrailerPlayerState = when (phase) {
    TrailerPhase.Error -> this
    else -> copy(currentTimeSec = clampToPlayable(targetSec))
}

private fun clampToPlayable(seconds: Double): Double = when {
    durationSec > 0.0 -> seconds.coerceIn(0.0, durationSec)
    else -> seconds.coerceAtLeast(0.0)
}
```

The reason is a real bug: a Fast-Forward within ten seconds of the end used to push the embed past
the end, YouTube reported `Ended`, and the player auto-closed. `onSeekApplied` also makes the seek
*optimistic* — the bar moves under a held key without waiting for the next 500 ms tick.

## 2.7 Chrome, focus and the key map

`TrailerPlayerScreen.kt:96`. The screen owns the engine's lifetime and reduces its events:

```kotlin
var reloadKey by remember { mutableIntStateOf(0) }
val engine = remember(reloadKey) { engineFactory(context, videoKey) }
var state by remember(engine) { mutableStateOf(TrailerPlayerState()) }

DisposableEffect(engine) { onDispose { engine.release() } }

LaunchedEffect(engine) {
    engine.events.collect { event -> state = state.onEvent(event) }
}
```

The surface is mounted full-bleed and explicitly cannot take focus:

```kotlin
key(engine) {
    engine.surface()?.let { surfaceView ->
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false },
        )
    }
}
```

### Auto-hide

Two effects, and the split between them is the rule "chrome may only rest hidden over a moving
picture" (`TrailerPlayerScreen.kt:132`):

```kotlin
LaunchedEffect(state.phase) {
    when (state.phase) {
        TrailerPhase.Ended -> onClose()
        TrailerPhase.Playing -> Unit
        else -> chromeVisible = true
    }
}

LaunchedEffect(chromeVisible, state.phase, interactionTick) {
    if (chromeVisible && state.phase == TrailerPhase.Playing) {
        delay(CHROME_HIDE_MS)   // 4_000L
        chromeVisible = false
    }
}
```

`interactionTick` is bumped by any handled key and by any control's `onFocusChanged`, so either
restarts the clock. The chrome is **always composed** and only alpha-animated:

```kotlin
val chromeAlpha by animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = iglooTween(IglooMotion.STANDARD_MS),
    label = "trailerChrome",
)
```

Dismissal must not detach the focused control or reshuffle TalkBack traversal — that is the whole
reason it is alpha rather than conditional composition. Under reduced motion `iglooTween` snaps.

### The key map

`handlePlayerKey` (`TrailerPlayerScreen.kt:551`) is a pure function on the root
`Modifier.onPreviewKeyEvent`, which makes it directly testable. It ignores everything but `KeyDown`,
and returns `false` immediately while `phase == Error` so the Retry button owns all input.

| Input | Chrome hidden | Chrome visible |
|---|---|---|
| Media play/pause, rewind, fast-forward | Acts, shows chrome, consumed | Acts, shows chrome, consumed |
| Center / Enter | Toggle play/pause, show chrome, consumed | Falls through to the focused control |
| Left / Right | Seek ∓10 s, show chrome, consumed | Falls through (moves focus) |
| Up / Down | Show chrome + focus Play/Pause, consumed | Falls through (moves focus) |
| Anything else | Falls through | Falls through |

Every handled key while hidden is **swallowed** — an invisible focused control must never activate.
When the chrome is visible the handler still calls `showChrome()` before returning `false`, so
falling through still counts as interaction and restarts the clock:

```kotlin
// Chrome visible: the key falls through to the focused control, but still counts as
// interaction so the auto-hide clock restarts.
showChrome()
return false
```

Back is separate, and handles only chrome dismissal (`TrailerPlayerScreen.kt:171`):

```kotlin
BackHandler {
    if (chromeVisible && state.phase == TrailerPhase.Playing) chromeVisible = false else onClose()
}
```

Note the `Playing` condition: while paused there is nothing to reveal by hiding the chrome, so Back
closes immediately rather than eating a press.

### The focus graph

Four controls, each pinning the edges it must not escape through:

| Control | up | down | left | right |
|---|---|---|---|---|
| Back | Cancel | `playPauseRequester` | Cancel | Cancel |
| Rewind | `backRequester` | Cancel | Cancel | (Play/Pause) |
| Play/Pause | `backRequester` | Cancel | (Rewind) | (Forward) |
| Forward | `backRequester` | Cancel | (Play/Pause) | Cancel |

Entry focus is Play/Pause, and the error state moves it to Retry — the only control that state has:

```kotlin
LaunchedEffect(state.phase == TrailerPhase.Error) {
    if (state.phase == TrailerPhase.Error) {
        retryRequester.requestFocus()
    } else {
        playPauseRequester.requestFocus()
    }
}
```

Retry wears `pinnedToScreen()` (`core/ui/FocusPinning.kt:12`), which cancels all four directions —
an overlay's host UI is composed underneath it, so an unpinned edge is an escape hatch onto
something the user cannot see.

## 2.8 Lifecycle, watchdogs, retry

**Standby.** `TrailerPlayerScreen.kt:178`:

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(engine, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> engine.onHostPaused()
            Lifecycle.Event.ON_RESUME -> engine.onHostResumed()
            else -> Unit
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

The engine's side is asymmetric on purpose — `onHostPaused()` does `pause(); webView.onPause()`,
while `onHostResumed()` does only `webView.onResume()`. The rendering pipeline comes back; the video
does not.

**The two watchdogs**, and their ordering rule:

```kotlin
// in-page, YouTubeIFrameEngine
private const val API_LOAD_TIMEOUT_MS = 8_000
var apiTimeout = setTimeout(function () {
  $JS_INTERFACE.onError(${TrailerPlayerState.API_LOAD_TIMEOUT});   // -2
}, $API_LOAD_TIMEOUT_MS);

// Kotlin, TrailerPlayerScreen
private const val READY_WATCHDOG_MS = 12_000L
LaunchedEffect(engine) {
    delay(READY_WATCHDOG_MS)
    state = state.onWatchdogExpired()
}
```

The inner guard **must** fire first. Because an error is sticky, a guard that reports second is a
guard nobody ever sees — and the in-page one knows the narrower cause (YouTube's API script itself
never arrived), so it gets to report. `onWatchdogExpired()` no-ops once `ready`, so the outer expiry
needs no cancellation bookkeeping.

**Retry** is `onRetry = { reloadKey++ }`, and everything else falls out of the `remember` keys:
`remember(reloadKey)` builds a brand-new engine, the `DisposableEffect` releases the old one, and
`remember(engine)` resets `state` to its default. There is no reset path to get wrong.

## 2.9 Accessibility

- Root: `paneTitle = "Trailer player"`, `isTraversalGroup = true`.
- A 1 dp `LiveRegionMode.Polite` node announces `"Playing: $title"` / `"Paused: $title"` /
  `"Loading trailer"`. Polite, because it narrates and must never interrupt; it exists because media
  keys otherwise flip play state silently for a TalkBack focus parked anywhere.
- Transport buttons are single cleared nodes whose label is also their action:
  `clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick(label = label) { … } }`.
- The seek bar is **one cleared, non-focusable summary node** and deliberately **not** a live region
  — a timer narrating every 500 ms tick is §12 noise:

  ```kotlin
  contentDescription =
      "${formatSpokenTime(state.currentTimeSec)} of ${formatSpokenTime(state.durationSec)}"
  ```

- The WebView is `isFocusable = false` and
  `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`, so YouTube's own DOM never enters the
  TalkBack tree.
- Formatting is in `core/ui/MediaFormatting.kt`: `formatTimecode` (`72.4 → "1:12"`,
  `3675.0 → "1:01:15"`) for the visible clock, `formatSpokenTime` (`72.4 → "1 minute 12 seconds"`)
  for the spoken one. Both share a private `hms()`. This screen is §9.1's one sanctioned exception
  to "formatting happens in the view model" — it has no view model, so its chrome formats in place.

Test tags: `trailer_player`, `trailer_back`, `trailer_loading`, `trailer_rewind`,
`trailer_play_pause`, `trailer_forward`, `trailer_seek_track`, plus `details_layer` in `IglooApp`.

## 2.10 Tests

| Suite | Where | Covers |
|---|---|---|
| `TrailerPlayerStateMachineTest` | `app/src/test/.../playback/youtube/` | 13 JVM tests over the pure reducer: the YT code map, unknown codes, the error-message table, error stickiness against every later event, watchdog expiry (and its no-op once ready), `seekTarget`/`onSeekApplied` clamping at both ends, and duration never shrinking. |
| `MediaFormattingTest` | `app/src/test/.../core/ui/` | `formatTimecode` (hours only when present, negatives → `"0:00"`) and `formatSpokenTime`, including the singular regression `formatSpokenTime(60.0) == "1 minute"`. |
| `MovieDetailsViewModelTest` | `app/src/test/.../feature/movies/` | Extras keep their YouTube `key` and thumbnails go through the proxy. |
| `TrailerPlayerScreenTest` | `app/src/androidTest/.../feature/player/` | 13 tests against `FakeTrailerPlayerEngine` with a test-driven `LifecycleRegistry`: entry focus, Center toggling through the engine, media keys seeking regardless of chrome, Left seeking while hidden vs. moving focus while visible, edge pinning, Back-hides-then-Back-closes, Back closing directly while paused, `Ended` closing, the pinned error Retry, standby silence, both watchdog outcomes, and Retry discarding the failed engine. |
| `TrailerOverlayFocusTest` | `app/src/androidTest/.../feature/player/` | 3 tests driving the whole `IglooApp` shell with an injected fake: opening from a card, the details layer carrying `HideFromAccessibility` while the player is up, and Back restoring focus to the launching card — deliberately launched from the **second** card, since a regression to the rail's entry anchor would still pass a first-card assertion. |
| `MovieDetailsAccessibilityTest` | `app/src/androidTest/.../feature/movies/` | Extra cards announce `"Play {title}"` and performing the click reports the right id. |

`FakeTrailerPlayerEngine` (`app/src/androidTest/.../playback/youtube/`) records `"play"`, `"pause"`,
`"seek:$s"`, `"hostPaused"`, `"hostResumed"`, exposes `playbackCommands` with the lifecycle entries
filtered out (registering a lifecycle observer replays the current state), tracks `released`, and
lets a test `emit(event)` directly.

## 2.11 Known limits and accepted trades

- **Activity recreation restarts the trailer at 0:00.** A WebView cannot be parceled; `TrailerRequest`
  restores *which* trailer, not where it was.
- **No progress reporting, resume, chapters or quality chip.** Trailers are not tracked; §11.8's
  machinery is intentionally absent.
- **No volume control.** TV remotes drive device volume.
- **The `release()` detach-before-destroy fix is not verified on hardware.** The fake draws no
  surface, so only a real trailer on the Shield can exercise it.
- **The embed is at YouTube's mercy.** Codes 101/150/152/153 mean the video simply cannot play
  outside youtube.com, and there is no fallback — the error state is the answer.

## 2.12 What the review pass fixed (`eb9f1bc`)

- **Unclamped forward seek** — the clamp moved out of the screen and into `seekTarget`, so a
  Fast-Forward near the end no longer pushes past the end, reports `Ended`, and auto-closes.
- **Watchdog ordering** — `API_LOAD_TIMEOUT_MS` dropped 15 s → **8 s** so the in-page guard fires
  inside the screen's 12 s one, with the reasoning recorded at both constants.
- **Detach before destroy** — `release()` now removes the WebView from its parent before
  `destroy()`; destroying an attached WebView crashes its renderer.
- **Three de-duplications** — `SeekBar` calls the shared `progressFraction`;
  `MovieDetailsViewModel.spokenRuntime` was deleted in favour of `formatSpokenTime`, which also
  fixed a "1 minutes" singular bug; `formatTimecode` and `formatSpokenTime` now share `hms()`.
- **Design-system corrections** — all chrome text goes through `TextStyle.overMedia(true)`, and the
  three over-media literals became named constants at spec values (control ground `0.45f`, secondary
  text `0.85f`, tertiary timecode `0.75f`); the seek track deliberately keeps `0.40f`, the
  progress-strip ground.
