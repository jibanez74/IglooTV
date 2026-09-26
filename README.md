# Igloo TV

The official **Android TV client** for [Igloo](../Igloo), a self-hosted media center. Written in
Kotlin with Jetpack Compose for TV, it talks to the Igloo Go backend over HTTP and plays your
library on a television with a remote control.

This repository is the TV client **and nothing else**. It is not a mobile app, not a tablet app,
not a Plex or Jellyfin client, and not a responsive web view in a wrapper. The manifest declares
`android.software.leanback` as *required*, which is what keeps the APK off phones and tablets.

**Status: pre-production.** Version `0.1.0`, `versionCode 1`. There is no backward compatibility
to preserve, no migrations to write, and no compatibility layers to maintain. Of the seven
navigation destinations, **Home**, **Movies**, **TV Shows** and **Music** render real content;
Search, Photos and Settings are placeholder panes driven by `IglooDestination.supportingText`.

---

## Contents

- [What it does today](#what-it-does-today)
- [Constraints you need before writing a line](#constraints-you-need-before-writing-a-line)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build and run](#build-and-run)
- [Testing strategy](#testing-strategy)
- [Repository map](#repository-map)
- [Contributing](#contributing)
- [Troubleshooting](#troubleshooting)

---

## What it does today

All paths below are relative to `app/src/main/java/com/igloo/blindpenguincoder/`.

| Surface | What ships | Owned by |
| --- | --- | --- |
| Boot / splash | System splash handing off to a Compose splash, gated so no app content renders early | `feature/boot/SplashScreen.kt`, `MainActivity.kt` |
| Welcome + server setup | First-run welcome, server address entry, URL normalization, health probe | `feature/auth/WelcomeScreen.kt`, `ServerSetupScreen.kt`, `core/config/ServerUrl.kt` |
| Sign-in | Quick Connect (six-character code + on-screen QR) and email/password, as two tabs | `feature/auth/SignInScreen.kt`, `QuickConnectScreen.kt`, `LoginScreen.kt`, `core/ui/IglooQrCode.kt` |
| Profiles | Multi-profile household picker, per-profile encrypted token vault, per-profile sign-out | `feature/auth/ProfilePickerScreen.kt`, `core/storage/ProfileVault.kt`, `feature/home/SignOutViewModel.kt` |
| PIN | PIN entry gate for a profile that has one (verify only; setting a PIN is backend/web for now) | `feature/auth/PinEntryScreen.kt` |
| Navigation shell | Nav spine with seven destinations, brand block, profile footer, overlay host, Back handling | `feature/home/IglooApp.kt`, `NavigationRail.kt` |
| Home | Cinematic hero plus rails: continue watching, latest movies, latest albums, in theaters | `feature/home/HomeViewModel.kt`, `HomeHero.kt` |
| Movies and TV Shows | One shared library pane per kind: a poster grid with an `All · Genres (· Liked)` tab strip, focus-driven tab switching with a 300 ms debounce, infinite paging, sort toggle, genre picker with memory. Movies has the Liked tab and opens details; TV Shows has two tabs and inert cards until a show details screen exists | `feature/library/LibraryScreen.kt`, `LibraryViewModel.kt`, `LibraryKind.kt`, `feature/movies/MovieLibrarySource.kt`, `feature/shows/ShowLibrarySource.kt` |
| Movie details | Backdrop hero, metadata, watched/like toggles, playback-mode + audio + subtitle pickers, trailer launch | `feature/movies/MovieDetailsScreen.kt`, `PlaybackSettingsDialog.kt` |
| In theaters | A separate TMDB-backed details page sharing the one overlay slot | `feature/movies/TheaterMovieDetailsViewModel.kt` |
| Album details + music player | Album page with track list and facts panel; Play Album opens a full-screen player with one ExoPlayer playlist, auto-advance and a MediaSession | `feature/music/AlbumDetailsScreen.kt`, `feature/player/MusicPlayerScreen.kt` |
| Movie playback | Media3 over a `SurfaceView`, Direct play and backend-produced HLS, audio/subtitle track selection, chapters, resume prompt, progress reporting | `feature/player/MoviePlayerScreen.kt`, `playback/` |
| Trailers | An isolated YouTube IFrame WebView that never receives Igloo credentials or cookies | `playback/youtube/YouTubeIFrameEngine.kt`, `feature/player/TrailerPlayerScreen.kt` |

---

## Constraints you need before writing a line

These are summaries. `AGENTS.md` is the full contract and `docs/design-system.md` is the
authoritative visual and interaction spec — read them, don't infer them from here.

**It is a 10-foot, landscape, remote-only product.** No hover, no cursor, no touch, no swipe, no
long-press for primary functionality. Everything a pointer UI would trigger on hover is triggered
by **focus**. No bottom navigation, no FABs, no portrait layouts.

**960×540dp is the reference viewport, at every screen size.** Android TV reports the same
density-independent viewport for a 26" 1080p panel, a 65" 1080p panel, and a 4K panel. Physical
inches are not detectable and there is no breakpoint to write. 4K buys image resolution, not
layout room. Because viewing distance is undetectable, scale is a user setting (`UiScale`), and
system font scale is clamped to `[0.85, 1.30]`. See `docs/design-system.md` §2.

**TalkBack is a product requirement, not a pass at the end.** On TV, TalkBack follows *input
focus* — it does not linearly traverse non-focusable text the way handset TalkBack does. A plain
text node is unreachable and unspoken. Text a screen-reader user must hear either rides a
focusable node's semantics or becomes a deliberate reading stop. See `docs/design-system.md` §12.

**One focus treatment, everywhere**, and focus must be preserved when returning from details,
dialogs, authentication or playback. Dialogs contain focus while open and restore it to the
invoking control.

**Platform floor:** `minSdk 28`. Do not assume Google Play Services or the Play Store — Fire TV
must work.

**Hard lines that are easy to cross by accident:**

- Never hardcode a server host, port, LAN address or domain. The server is user-configured.
- Never disable certificate validation or install a trust-all verifier for self-hosting.
- Never transcode, decode, downmix or transform media in the client — that is the backend's
  FFmpeg pipeline (`docs/ffmpeg.md`).
- Never force stereo; preserve audio passthrough where the device and media support it.
- Sign-out affects **only the selected profile**. A shared TV keeps everyone else signed in.
- Never log passwords, tokens, pairing codes, QR secrets or sensitive headers.
- No third-party services called directly (no TMDB, Spotify, analytics); images and metadata
  proxy through the Igloo backend.
- Do not add Hilt, Koin, Room or another architectural framework without asking.

---

## Architecture

A deliberately small MVVM stack with unidirectional data flow:

```text
Compose UI  ->  ViewModel / immutable screen state  ->  Repository  ->  Ktor API
                                                                   \-> DataStore
```

Screen state is an immutable `data class`; loading / success / empty / error are modeled
explicitly (`sealed interface`), not as nullable data. Collection is lifecycle-aware
(`collectAsStateWithLifecycle`, `LifecycleStartEffect`).

### The deliberate absences

The most surprising thing about this codebase is what is *not* in it. None of these are
oversights.

**No DI framework.** `IglooAppContainer.kt` is a hand-rolled lazy service locator, constructed
once by `IglooApplication.kt` and passed explicitly into `IglooRoot(container)` from
`MainActivity.kt`.

**No `NavHost`.** The auth boundary is a `when (authState)` in `IglooRoot.kt`. In-shell
navigation is a `rememberSaveable` destination name over the `core/navigation/IglooDestination.kt`
enum, inside `feature/home/IglooApp.kt`. Details pages and players are overlays occupying **one
details slot**, mutually exclusive by construction, with a `DetailsOrigin` saver that restores
focus to the exact card you came from. `navigation-compose` is in the version catalog but is not
used for routing.

**No Room.** Two DataStore Preferences files (`core/storage/IglooDataStores.kt`):
`igloo_settings` holds the server URL and UI scale; `igloo_session` holds the profile vault as a
single blob encrypted through `core/storage/AndroidKeystoreCipher.kt`. Sign-out removes the
encrypted key and user-scoped caches; the server URL survives by design.

**No mocking library.** See [Testing strategy](#testing-strategy).

### Layers

| Package | Responsibility |
| --- | --- |
| `core/config/` | `ServerUrl` parsing/normalization, `DeviceIdentity` (the only `BuildConfig` consumer) |
| `core/design/` | Design tokens: colors, dimens, typography, motion, `UiScale`, theme accessors |
| `core/ui/` | ~35 shared TV primitives: focus ring, media rail, poster card, pills, tabs, menus, dialogs, QR, text field |
| `core/network/` | Ktor client, bearer auth plugin, `SafeApiCall`/`ApiResult`, error mapping, health probe, server URL provider |
| `core/storage/` | DataStore wiring, profile vault, secret cipher, UI preferences |
| `core/image/`, `images/` | Coil loader + bearer interceptor + cache; provider URL resolution |
| `core/navigation/`, `core/error/` | Destination enum; app-level error types |
| `data/api/`, `data/model/`, `data/repository/` | Endpoint construction, wire models with a shared `Envelope`, repositories with caching |
| `feature/auth/`, `feature/boot/` | Auth gate state machine (`SessionManager`, `AppAuthState`) and the boot/auth screens |
| `feature/home/`, `feature/movies/`, `feature/music/` | The shell and the two real panes, plus details overlays |
| `feature/player/`, `feature/shared/` | Player screens and chrome; cross-feature UI |
| `playback/` | `playback/media3/` engines and sessions, `playback/hls/` session lifecycle, `playback/model/` pure state machines, `playback/progress/` reporting, `playback/youtube/` trailer engine |

### Networking and auth

`core/network/IglooHttpClient.kt` builds the Ktor 3 client on the OkHttp engine with
kotlinx.serialization (`ignoreUnknownKeys`, `explicitNulls = false`). A custom `deviceTokenAuth`
plugin injects `Authorization: Bearer igd_…` and maps **any** 401 onto `AuthEventBus`, which is
what drives the app back to the auth gate. Per-request attributes opt individual calls out
(`NoDeviceAuthAttribute`, `BearerOverrideAttribute`, `ExpectedUnauthorizedAttribute`). Timeouts
are 10s connect / 30s request and socket; a second no-redirect client backs `ServerHealthProbe`.

The client is **bearer-only** — there is no session cookie. Tokens come from Quick Connect or
device login and are stored encrypted per profile. Note that `GET`/`DELETE /api/devices` are
cookie-auth only on the backend, deliberately, so a stolen TV token cannot enumerate or revoke
devices.

Failures are normalized by `SafeApiCall.kt` and `NetworkErrorMapping.kt` into app-level errors
with retry where recoverable. Nothing swallows a request failure.

### Images

Coil 3, installed as the singleton by `IglooApplication` (`SingletonImageLoader.Factory`).
`core/image/IglooImageLoader.kt` adds a `BearerImageInterceptor` that attaches the token **only**
to same-origin Igloo URLs. Provider URL construction lives in `images/ImageUrlResolver.kt`
(`/api/tmdb/images/{size}/{file}`, `/api/youtube/thumbnails/{key}`, avatars) — never in a
composable. Memory and disk caches are cleared on sign-out so one person's artwork does not
linger on a shared TV.

### Playback

Media3 1.11 behind `MoviePlayerEngine` / `MusicPlayerEngine` interfaces, with
`ExoMoviePlayerEngine` / `ExoMusicPlayerEngine` implementations and `MediaSession` wrappers that
publish real metadata to the system now-playing surface. `BearerStreamDataSource.kt` injects the
token into media requests (with a separate HLS variant using a 120s segment read timeout).
`playback/hls/` owns HLS session start/stop/switch; `PlaybackMode` covers Direct plus the remux
and resolution-capped HLS profiles. Progress reporting lives in
`playback/progress/ProgressReporter.kt`: `MIN_PLAYED_SEC = 15.0`, `SAVE_INTERVAL_SEC = 15.0`, so
the first save normally lands around 30 seconds of actual playback. Pause, background (`ON_STOP`)
and exit/end writes go out at once under the web's rule (`shouldPersistProgress`: past 30 s or at
95%), deduplicated within `FLUSH_DEDUPE_SEC`, and the exit write runs `NonCancellable` so backing
out of the app cannot lose it. The picture is Media3's
Compose `ContentFrame` over a `SurfaceView` with `ContentScale.Fit` — `SurfaceView` is required
for TV-quality timing, power use, full-resolution output and HDR, so do not swap it for a texture
surface.

### Design tokens

`docs/design-system.md` Appendix B maps every token group to its file in `core/design/`, and
`app/src/test/.../core/design/` pins the numbers. **Changing a number in the spec means changing
the code and the test** — see [Testing strategy](#testing-strategy) §Unit tests.

---

## Prerequisites

| Requirement | Version / note |
| --- | --- |
| JDK | 17 (`sourceCompatibility`/`targetCompatibility` are both 17) |
| Android SDK | `compileSdk 37`, `targetSdk 36`, `minSdk 28` |
| Gradle | Use the wrapper. Wrapper is **9.6.1**; never rely on a globally installed Gradle |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.21 (Compose compiler and serialization plugins only — no separate kotlin-android alias) |
| A TV target | A Google TV / Android TV AVD, or a real Android TV device. **A phone or generic emulator will not work** |
| `adb` | On `PATH`. Almost all verification goes through it |
| An Igloo server | The sibling Go repo (`../Igloo`), treated as read-only, or any reachable Igloo instance |

Key libraries, all pinned in `gradle/libs.versions.toml`: Compose BOM `2026.06.01`,
`androidx.tv:tv-material` `1.1.0`, Ktor `3.5.1`, Media3 `1.11.0`, Coil `3.5.0`, DataStore `1.2.1`,
coroutines `1.11.0`, kotlinx-serialization-json `1.11.0`, zxing-core `3.5.4` (Quick Connect QR).
Respect the catalog; do not perform unrelated upgrades.

---

## Build and run

```bash
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.igloo.blindpenguincoder/.MainActivity
```

The APK will refuse to install on a phone, a tablet, or a non-TV emulator image. That is the
leanback requirement doing its job, not a broken build.

### First run: configuring the server

There is no build-time server URL. There are no `buildConfigField`s at all — the only
`BuildConfig` value the app reads is `VERSION_NAME`, in `core/config/DeviceIdentity.kt`. The
address is typed by the user on the setup screen, parsed and normalized by
`core/config/ServerUrl.kt` (accepts `http`/`https`, defaults a bare host to `http://`, tolerates a
trailing `/api`, rejects userinfo, queries, fragments and malformed ports), persisted in
`igloo_settings`, then health-checked at `{apiBaseUrl}/health` with redirects disabled.

From an emulator, the host machine is **`10.0.2.2`**, not `localhost`:

```text
http://10.0.2.2:8080
```

`usesCleartextTraffic="true"` is set because self-hosted servers are commonly plain HTTP on a
LAN. HTTPS is fully supported and is what you should use off-LAN.

### Resetting state

| Goal | Command | Effect |
| --- | --- | --- |
| Fresh install, as a new user | `adb uninstall com.igloo.blindpenguincoder` then install | DataStore is gone; app starts on server setup |
| Exercise session restore | `adb shell am force-stop com.igloo.blindpenguincoder` then relaunch | Stored device token is restored; app goes straight to the shell |
| Inspect the vault | `adb shell run-as com.igloo.blindpenguincoder ls -l files/datastore/` | `igloo_session.preferences_pb` at 0 bytes means the encrypted key was removed, not overwritten |

---

## Testing strategy

There is **no CI in this repository** — no GitHub Actions, no Jenkins, no pipeline. Every check
below is something a human or an agent runs by hand before calling work done. That makes the
discipline below the actual quality gate, not a formality.

### The four tiers

| Tier | Job | Runs on |
| --- | --- | --- |
| 1. JVM unit tests | State machines, repositories, mapping, serialization, design-token values | No device |
| 2. Instrumented Compose tests | Focus chains, D-pad order, semantics, layout geometry, focus pixels, real ExoPlayer and Keystore | TV emulator or device |
| 3. Manual ADB verification | Whole-product flows, and every property a test cannot observe | TV emulator or device |
| 4. Real-hardware validation | Playback, audio passthrough, remote media keys, TalkBack | Nvidia Shield (primary), Fire TV |

House rules, from `AGENTS.md` §Testing and completion:

- **Tests ship with the change.** Meaningful state, repository, auth, networking, playback,
  navigation, focus and accessibility behavior gets a test; cover the important failure paths too.
- **Run the smallest relevant test first**, then widen.
- **Do not add screenshot tests by default.** They are fragile here and the project has none.
- **Build success and static review are never sufficient for TV UI work.** If a target exists,
  drive it.
- When finishing, report the commands you ran, the device you used, what you verified, what you
  could not verify, and what real-device risk remains.

### The toolbox — and what is deliberately absent

Declared in `app/build.gradle.kts`:

```text
unit:         junit 4.13.2, kotlinx-coroutines-test, ktor-client-mock (MockEngine)
instrumented: compose ui-test + ui-test-junit4 (the v2 API), androidx.test runner/rules/ext-junit,
              coil3 coil-test, uiautomator (declared; used only via uiAutomation.executeShellCommand)
runner:       androidx.test.runner.AndroidJUnitRunner (stock; no custom runner or test Application)
```

**Not present, on purpose:** JUnit 5, Robolectric, Truth, AssertJ, MockK, Mockito, Turbine,
Espresso, MockWebServer, Paparazzi, Roborazzi, Hilt testing, kotest. Assertions are
`org.junit.Assert.*`; test doubles are hand-written fakes. **Do not reach for a mocking library to
write a test here** — the seams already exist (see below). There is also no `testOptions` block:
unit tests are plain JVM tests with no Android resources.

Instrumented tests use the **v2 Compose test API**:
`androidx.compose.ui.test.junit4.v2.createComposeRule` for composable tests and
`…v2.createEmptyComposeRule` for the gate suites that launch the Activity themselves.

### Commands

```bash
# Tier 1 — no device needed (890 @Test across 69 files)
./gradlew :app:testDebugUnitTest

# Tier 2 — needs a booted TV emulator or device (522 @Test across 51 suites)
./gradlew :app:connectedDebugAndroidTest

# Static + assembly
./gradlew :app:lintDebug
./gradlew :app:assembleDebug

# Both unit variants
./gradlew test
```

One class or one method:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.igloo.blindpenguincoder.feature.library.LibraryViewModelTest'

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.igloo.blindpenguincoder.feature.movies.MoviesGridBehaviorTest
```

Reports:

```text
app/build/reports/tests/testDebugUnitTest/index.html
app/build/reports/androidTests/connected/index.html
app/build/reports/lint-results-debug.html
```

Android Lint is stock AGP. There is no ktlint, detekt, spotless, `lint.xml` or lint baseline;
`gradle.properties` sets `kotlin.code.style=official`, so match the surrounding code.

### Tier 1: unit tests

`app/src/test/java/com/igloo/blindpenguincoder/`

| Area | Representative files | What is asserted |
| --- | --- | --- |
| Repositories | `data/repository/{Auth,Movie,Music,Profile,Server,Show}RepositoryTest.kt` | Request shape, decode, error mapping, caching, profile ordering — over the **real** HTTP client on a `MockEngine` |
| Wire models | `data/model/ApiModelsSerializationTest.kt` | Serialization round-trips against `docs/openapi.json` |
| Auth gate | `feature/auth/SessionManagerTest.kt` | The six-state gate: Loading, NeedsServer, ChooseProfile, NeedsPin, NeedsLogin, Authenticated |
| Pairing | `feature/auth/QuickConnectViewModelTest.kt`, `QuickConnectApprovalTest.kt` | Code initiation, polling, redeem, failure paths |
| Library pane | `feature/library/LibraryViewModelTest.kt` (the largest suite, over the movie routes), `LibraryKindTest.kt`, `feature/shows/ShowLibraryViewModelTest.kt` | Tab strip, the 300 ms switch debounce, pagination and append states, sort, genre memory, liked view; the per-kind wording and tags; the show routes and the two-tab strip |
| Details | `feature/movies/MovieDetailsViewModelTest.kt`, `PlaybackSettingsMappingTest.kt` | Details state, playback-mode/audio/subtitle option mapping |
| Home | `feature/home/HomeViewModelTest.kt`, `PlayerRequestSaversTest.kt` | Rails failing independently, hero state, `Saver` round-trips |
| Players | `playback/model/{Movie,Music}PlayerStateMachineTest.kt`, `feature/player/MoviePlayerViewModelTest.kt`, `ChaptersTest.kt` | Pure player state machines, chapter math, chrome state |
| HLS | `playback/hls/HlsSession*Test.kt` | Session lifecycle, policy, timeouts, mode switching |
| Progress | `playback/progress/ProgressReporterTest.kt` | The 15s / first-save-near-30s cadence, the pause/background/exit eligibility rule |
| Network core | `core/network/{SafeApiCall,DeviceTokenAuth,BearerTokenProvider}Test.kt` | Envelope handling, bearer injection, the 401 event bus |
| Storage | `core/storage/DataStoreProfileStoreTest.kt`, `UiPreferencesStoreTest.kt` | Encrypted vault and preference persistence |
| Design tokens | `core/design/{IglooDimens,IglooTypography,IglooMotion,UiScale}Test.kt` | The Standard values in `docs/design-system.md` §§4–5, pinned exactly |

> **The design-token rule** (`docs/design-system.md:2154`): if you change a number in the design
> system and the design tests still pass, **you forgot to change the code.**

#### The unit-test seam: `TestHttp.kt`

`app/src/test/java/com/igloo/blindpenguincoder/data/repository/TestHttp.kt` is where a new
repository or API test starts. It assembles the *real* `createIglooHttpClient` over a Ktor
`MockEngine`, plus the real `AuthApi`/`UserApi`/`MovieApi`/`MusicApi`, the real repositories, a
real `BearerTokenProvider` and `AuthEventBus`, a `FakeProfileStore`, and an injectable
`clockMillis`. Tests therefore exercise the actual serialization, auth plugin and error mapping —
the only substitution is the transport.

Other fakes, all hand-written: `core/storage/FakeProfileStore.kt`, `FakeSecretCipher.kt`,
`InMemoryPreferencesDataStore.kt`.

Coroutine conventions: `runTest`, `UnconfinedTestDispatcher`, `Dispatchers.setMain` /
`resetMain`, `runCurrent`.

### Tier 2: instrumented tests

`app/src/androidTest/java/com/igloo/blindpenguincoder/`

Four kinds of suite:

1. **Gate suites** — `AuthGateTest`, `WelcomeGateTest`, `QuickConnectGateTest`,
   `ProfilePickerGateTest`, `SplashBootGateTest`, `WarmRelaunchGateTest`. They clear DataStore and
   launch the real `MainActivity` through `ActivityScenario` + `createEmptyComposeRule`, then
   assert the boot and auth path end to end.
2. **Behavior suites** — `IglooBaseAppTest`, `feature/home/{NavigationRailBehavior,HomeRailBehavior,HomeHeroFocus,ShellBleed}Test`,
   `feature/movies/{MoviesGridBehavior,MovieDetailsFocus,PlaybackSettingsDialog}Test`,
   `feature/music/AlbumDetails*Test`, `feature/player/*ScreenTest` and the overlay-focus tests.
3. **Accessibility suites** — `MovieDetailsAccessibilityTest`, `MoviesGridAccessibilityTest`,
   `AlbumDetailsAccessibilityTest`, `PairingCodeAccessibilityTest`, `PinEntryAccessibilityTest`.
4. **Real-Android suites** — `playback/media3/{ExoMoviePlayerEngine,ExoMusicPlayerEngine,MovieMediaSession,MusicMediaSession,HlsLoadErrorPolicy,TrackOptions}Test`,
   `playback/youtube/YouTubeIFrameEngineTest`, `core/storage/AndroidKeystoreCipherTest`. These need
   a device because they drive a real ExoPlayer, a real MediaSession, a real WebView and the real
   Android Keystore.

#### Conventions that are easy to get wrong

**`AnimationScaleRule` must be outermost.**

```kotlin
@get:Rule(order = 0) val animations = AnimationScaleRule()
@get:Rule(order = 1) val compose = createComposeRule()
```

The rule zeroes `animator_duration_scale` (restoring the previous value afterwards) because the
welcome screen's ambient backdrop animates forever and the Compose clock never idles. Lower order
is outermost, so animations are off *before* the compose rule sets up. Get the order wrong and
tests hang.

**`GateWaits.kt` waits, rather than `waitForIdle`.** `awaitScreen(title)`,
`awaitContentDescription(desc)` and `awaitTestTag(tag)` each require a non-empty
`boundsInWindow`, so they cannot latch onto a previous activity's still-placed nodes. The timeout
is 5s. `spokenFeedbackEnabled()` reads `AccessibilityManager` exactly as the app does, so tests
branch the same way the app branches.

**An instrumented run cannot see TalkBack.** `UiAutomation` suppresses every other accessibility
service while it is connected, and `AnimationScaleRule` connects it in 29 suites, so
`spokenFeedbackEnabled()` is **false throughout any full run** no matter what the device's
TalkBack setting is. Measured on the Shield: TalkBack bound before the run, `Bound services:{}`
during it. Two consequences:

- Running the whole suite with TalkBack switched on proves nothing extra — it takes exactly the
  same branches as with it off.
- The screen-reader branches (`QuickConnectGateTest`'s spoken pairing-code assertions,
  `FocusTreatmentTest`'s skip) only fire when those classes are run **alone** on a TalkBack
  device, because then nothing connects `UiAutomation`:

  ```bash
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class=com.igloo.blindpenguincoder.QuickConnectGateTest
  ```

This is why `FocusTreatmentTest` guards on `spokenFeedbackEnabled()` and not on
`AccessibilityManager.isEnabled`: `UiAutomation` is itself an accessibility service, so `isEnabled`
is true in every full run, and guarding on it silently skipped all ten pixel tests — the only ones
that prove the focus treatment renders — while they passed whenever the class ran on its own.

**`TestIglooApp.kt`** wraps the real `IglooApp` with every dependency defaulted to an inert
fixture (`TEST_SERVER_ORIGIN`, `testAuthUser`, `spokenAccessibilityEnabled = false` by default).
The caller supplies `IglooTheme` deliberately, so a test can vary the theme or `UiScale`.
**`HomeTestFixtures.kt`** supplies movie, continue-watching, state and details fixtures with image
URLs nulled out, so nothing decodes or touches the network, plus fake player engine factories.

**`LocalApiServer.kt`** is a real loopback `ServerSocket(0)` speaking just enough Igloo API for the
gate suites, because the PIN and Quick Connect phases are *server-driven* and cannot be seeded
from the client. It switches behavior per test — `QuickConnectInitiate.Hang | Fail | Code(code)`,
`pinValid`, `quickConnectRedeemApproved` — records `requestedPaths` so a test can assert what was
**not** fetched, 404s unimplemented routes loudly, and serves one thread per connection so a
deliberately hanging route does not stall the rest.

**Selectors.** `testTag` is the primary selector; roughly 80 literal tags exist in `main`
(`navigation_rail`, `shell_content`, `content_pane`, `movies_grid`, `movies_tabs`, `movie_player`,
`music_player`, `trailer_player`, `album_details`, `details_layer`, `playback_settings_dialog`)
plus interpolated families (`poster_card_${id}`, `pin_key_$key`, `pairing_code_character_$index`).
When a real decode matters, Coil is faked with `coil3.test.FakeImageLoaderEngine` +
`SingletonImageLoader.setUnsafe` (see `MovieDetailsOverMediaTest`).

**Three assertion styles, and when to use which:**

| Style | Example | Use it for |
| --- | --- | --- |
| Semantics | `assertContentDescriptionEquals`, `SemanticsProperties.LiveRegion`, `HideFromAccessibility` | What TalkBack will say, announcements, decorative artwork being hidden |
| Geometry | `getUnclippedBoundsInRoot()` (`ShellBleedTest`, rail order, footer onscreen at `UiScale.Large`) | Layout facts the semantics tree cannot express — order, clipping, overscan |
| Pixels | `core/ui/FocusTreatmentTest` samples rendered color | That the focus ring actually *renders*. A node can report `focused = true` with nothing visible — a semantics assertion would pass while the product is broken |

That third row is the reason `Modifier.clickable` ordering matters: it already contains a focus
target, so never add `.focusable()` after it, and put `.onFocusChanged` **before** `.clickable`.

### Tier 3: the manual ADB runbook

Start the backend and confirm it is healthy:

```bash
# from your Igloo server checkout; the port comes from its own .env
curl -s <server-origin>/api/health     # {"error":false,"message":"server is healthy"}
```

Boot a TV emulator and wait for it properly:

```bash
emulator -avd <tv-avd> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
```

Drive it:

```bash
adb exec-out screencap -p > shot.png                 # look at the screen
adb shell input text 'http://10.0.2.2:8080'          # IME opens when a field gains focus
adb shell input keyevent 66                          # IME Next/Done — submits forms
adb shell uiautomator dump                           # then pull /sdcard/window_dump.xml,
                                                     # grep focused="true"
adb shell dumpsys activity activities | grep mResumedActivity
adb shell dumpsys media_session                      # what the system now-playing surface sees
```

D-pad keyevents: `19` up, `20` down, `21` left, `22` right, `23` center/select, `4` back (back
closes the IME first).

Flows worth driving, in order:

1. Fresh install → server setup → type the URL → Done → sign-in screen.
2. Wrong password → the inline "Incorrect email or password." error, not a crash or a blank state.
3. Correct sign-in → the shell shows the profile's name in the spine footer.
4. `am force-stop` + relaunch → straight to the shell (the stored device token is restored; there
   is no session cookie).
5. Two-profile sign-out — see below.
6. Sign-in screen D-pad order: DOWN moves email → password → Sign in → Change server. Text fields
   intercept vertical D-pad to move focus, and this is regression-prone.

**Pair without typing credentials.** `adb shell input text` fights the IME for focus and drops
characters. Quick Connect is the app's primary designed path anyway: read the six-character code
off a screencap and approve it from the host with a cookie jar.

```bash
S=<server-origin>
curl -s -c user.jar -X POST "$S/api/auth/login" \
  -H 'Content-Type: application/json' --data '{"email":"<email>","password":"<password>"}'
curl -s -b user.jar -X POST "$S/api/quick-connect/approve" \
  -H 'Content-Type: application/json' --data '{"code":"<code>"}'
```

Add a second profile through the spine: *Switch profile* → *Add profile*, then repeat with the
other account's cookie jar.

### What only a manual flow can prove

**Per-profile sign-out.** One stored profile can only show that the app forgot *someone*; it
cannot show that it forgot the *right* someone, which is the whole property. With profiles A and B
stored and A signed in, D-pad only:

1. LEFT into the spine, DOWN to *Sign out*, CENTER → the confirmation opens with **Cancel**
   focused.
2. DOWN / LEFT / UP off the card → focus must not move (the dialog contains focus). BACK → the
   modal closes and focus returns to the *Sign out* row, app still running.
3. CENTER to confirm → the profile picker, listing **only B**.
4. **Assert at the source** — the screen cannot show you this:
   ```bash
   curl -s -b a.jar "$S/api/devices"   # A's TV device: gone
   curl -s -b b.jar "$S/api/devices"   # B's TV device: still listed
   ```
5. Sign in as B from the picker → succeeds without re-pairing.
6. Sign out B too → the **sign-in screen**, not the picker.
7. `am force-stop` + relaunch → still signed out, server address preserved.

And check the vault directly when a sign-out looks right but may not be:

```bash
adb shell run-as com.igloo.blindpenguincoder ls -l files/datastore/
# igloo_session.preferences_pb at 0 bytes = the encrypted key was removed, not overwritten.
# igloo_settings keeps the server URL — the server surviving sign-out is by design.
```

**Playback.** `screencap` renders hardware video and WebView as a **black rectangle**. A black
player screen is not evidence that playback failed. Judge by the chrome instead: press UP to
reveal it and read the seek-bar timecodes advancing, with the transport showing the pause glyph
while playing. Trailer embed errors appear in logcat as `[INFO:CONSOLE:*]` lines from `chromium` —
filter by the app's pid, because a Shield's logcat is flooded with adbd noise.

**TalkBack on TV.** It follows input focus, so probe it with screenshots and focus-box detection
rather than a semantics dump, and do not run `uiautomator dump` mid-run against a live TalkBack
session.

**Audio passthrough and the FFmpeg decoder.** The bundled FFmpeg audio decoder ships an
`arm64-v8a` library only, and the app is multi-ABI, so a normal install on an x86_64 TV image runs
*without* it and audio behaves as if the extension were absent. To exercise that path on an
x86_64 image, force ARM translation:

```bash
adb install -r --abi arm64-v8a app/build/outputs/apk/debug/app-debug.apk
```

32-bit API 30/34 images cannot do this — their translation layer is armeabi only. TrueHD, DTS-HD,
AC3, E-AC3 and Atmos behavior is a **real-hardware** question; verify it on the Shield.

### Tier 4: the device matrix

| Target | Role | Watch for |
| --- | --- | --- |
| **Nvidia Shield** | Primary real-device target | Playback, audio passthrough, remote media keys, TalkBack. Prefer it over the emulator for all four |
| **Fire TV** | Must-work secondary | No Google Play Services, no Play Store assumptions |
| **Google TV 1080p AVD** | Everyday development target | 1920×1080 @ 320dpi = 960×540dp — the tightest layout there is; watch for clipped footers |
| **4K AVD** | Image-resolution paths | Same 960×540dp layout; higher-resolution artwork |
| Phone / tablet / generic emulator | **Not a target** | The leanback requirement blocks installation, deliberately |

Networking caveat: an emulator has no route to a Tailscale-style `*.ts.net` host, since there is
no tailnet inside the guest. Use a physical device on that network, or run a plain-HTTP relay on
the host and point the app at `http://10.0.2.2:<port>` — which has the useful side effect of
letting you make the server unreachable on demand (to exercise the offline sign-out notice)
without cutting the adb link a real device depends on.

### Writing a new test: where does it go?

| What you changed | Where the test goes |
| --- | --- |
| Pure logic, mapping, a state machine | JVM test in `app/src/test/…` |
| A repository or an endpoint | JVM test built on `TestHttp.kt` — do not mock the client |
| Focus chain, D-pad order, TalkBack semantics, layout bounds | Instrumented test with `TestIglooApp` + `AnimationScaleRule` |
| A boot or auth phase the app drives itself | Gate suite with `LocalApiServer` |
| A focus *visual* | Pixel assertion, in the `FocusTreatmentTest` style |
| A design-system number | Update `core/design/` **and** its test in `core/design/…Test.kt` |
| Anything a viewer sees on a TV | All of the above **plus** drive it over ADB, and say which device |
| Anything at all | Not a screenshot test |

Open coverage gaps are tracked in `docs/cleanup-backlog.md` §4 rather than left implicit.

---

## Repository map

| Path | What it is |
| --- | --- |
| `AGENTS.md` | The contributor contract: TV-only product rules, architecture, networking/auth/playback/image policy, dependency policy, definition of done. **Authoritative.** |
| `docs/design-system.md` | The 12-section design system: scale model, color, typography, spacing, the single focus treatment, motion, shell, components, UI states, per-screen UX, accessibility. Appendix B maps tokens to files. **Authoritative for UI.** |
| `docs/openapi.json` | The backend API contract (Igloo API 0.1.0, 129 paths). Read before API work; do not invent routes or payloads. Known stale for `AuthUser.avatar`. |
| `docs/known-issues.md` | Contract and behavior gaps deliberately not fixed yet, with enough detail to act on |
| `docs/cleanup-backlog.md` | Duplication, small correctness edges, coverage gaps and polish, each actionable |
| `docs/movies-screen-status.md` | Plain-language state of the Movies screen after the tab-strip pass |
| `docs/album-details-status.md` | Plain-language state of the Album Details screen |
| `docs/music-player-status.md` | Plain-language state of the music player |
| `docs/music-shuffle.md` | Spec for implementing music shuffle (rolling library queue vs finite client-shuffled queue) |
| `docs/subtitles-bug.md` | A fixed bug worth understanding: bitmap subtitle state surviving a Direct→HLS switch |
| `docs/ffmpeg.md` | How the **backend** uses FFmpeg/ffprobe — context for the client's playback modes |

`.claude/` is gitignored and local-only.

When a document and the implementation disagree, **report the conflict** rather than silently
picking one.

---

## Contributing

**Branching as practiced.** `master` is the mainline; feature work happens on a local `dev` or a
`feature/*` branch. There is no remote yet.

**Before you change anything:** read the relevant code and its tests. Follow the established
patterns unless the task is to change them. `docs/openapi.json` before API work,
`docs/design-system.md` before UI work.

**Style.** `kotlin.code.style=official`, and no formatter or linter is configured beyond stock
Android Lint — so match the surrounding file. Keep business, networking, storage and playback
logic out of composables. Comments explain non-obvious intent, constraints or workarounds; they do
not narrate the code, and an outdated comment is a defect.

**Do not** leave dead code, commented-out implementations or temporary diagnostics; do not perform
broad refactors or unrelated dependency upgrades alongside a feature; do not add speculative
abstractions or a use-case layer that removes no real duplication.

**Dependencies.** Prefer what is already here. Ask before adding or replacing dependency
injection, storage, networking, playback, code-generation, telemetry or background-work
frameworks, and explain the purpose and impact of anything new. No Firebase, analytics, crash
reporting or third-party media-server SDKs.

**Done means:** tests added or updated, the smallest relevant tests run first, lint and a debug
assembly clean, UI/focus/TalkBack/playback changes validated on an emulator or device, and a
report of what you ran, on what, what you verified, and what you could not.

---

## Troubleshooting

**`INSTALL_FAILED_MISSING_FEATURE` / the APK will not install.** The target is not a TV. The
manifest requires `android.software.leanback`. Use a Google TV / Android TV image or a real TV
device — connected tests need one too.

**"Something went wrong" right after sign-in.** `/api/auth/user` serializes `avatar` as a Go
`sql.NullString` (`{"String":…,"Valid":…}`) and `docs/openapi.json` is stale there. `curl` the real
backend before trusting the spec on a decode failure.

**The app cannot reach the server from an emulator.** Use `10.0.2.2`, not `localhost` or
`127.0.0.1`. For a Tailscale-style host, see the networking caveat in
[the device matrix](#tier-4-the-device-matrix).

**A focus ring silently stops rendering.** `Modifier.clickable` already contains a focus target:
never add `.focusable()` after it, and place `.onFocusChanged` **before** `.clickable`.
`uiautomator` will happily report `focused="true"` while nothing draws.

**D-pad up/down does nothing in a text field.** `BasicTextField` consumes those keys.
`core/ui/IglooTextField.kt` intercepts them in `onPreviewKeyEvent` to move focus — keep that when
you touch it.

**Every connected test fails with "No compose hierarchies found."** The device display is asleep.
Wake it and keep it on before running connected tests.

**Instrumented tests hang forever.** Check the rule order: `AnimationScaleRule` must be
`order = 0`, outside the compose rule, or the ambient backdrop animation prevents the clock from
ever idling.

**The player screen is black in a screenshot.** Expected — `screencap` cannot capture hardware
video or WebView. Judge playback by the chrome's timecodes.

**A `curl` to `/api/devices` returns 401 with the app's token.** Working as intended: device
listing and revocation are cookie-auth only, so a stolen TV token cannot enumerate or revoke
devices. Use a browser-style login.

---

## License

Copyright (C) 2026 Jose Ibañez

Igloo TV is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

Igloo TV is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

Third-party libraries bundled in the app and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
