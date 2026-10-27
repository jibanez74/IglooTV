# Testing IglooTV

Use the [README](../README.md#build-and-run) to configure the JDK, SDK, and TV target first.
Run commands below from the repository root. Use `gradlew.bat` on Windows.

## Commands

```bash
# JVM tests: no device required
./gradlew :app:testDebugUnitTest

# Instrumented tests: boot and connect a TV target first
./gradlew :app:connectedDebugAndroidTest

# Static analysis and debug assembly
./gradlew :app:lintDebug
./gradlew :app:assembleDebug

# Unit tests across build variants
./gradlew test
```

Reports are written to:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/index.html`
- `app/build/reports/lint-results-debug.html`

Run a focused suite before widening to related tests:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.igloo.blindpenguincoder.feature.library.LibraryViewModelTest'

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.igloo.blindpenguincoder.feature.movies.MoviesGridBehaviorTest
```

The repository has no checked-in CI workflow. Report the checks you actually ran, the device
used, what you verified, and what remains unverified. Documentation-only changes need link,
command, and configuration checks; app builds and device tests are unnecessary for them.

## Choosing a test

| Change | Validation |
| --- | --- |
| Pure logic, mapping, state machines | JVM tests in `app/src/test/java/` |
| Repository or API route | JVM tests using `data/repository/TestHttp.kt` |
| Wire model or API contract | Serialization tests and `data/model/OpenApiContractTest.kt` |
| Design token | Update `core/design/`, the specification, and the matching token test |
| Focus, D-pad order, semantics, or layout | Compose instrumented tests in `app/src/androidTest/java/` |
| Boot or authentication gate | Gate tests using `LocalApiServer` and the real activity |
| Focus visuals | Rendered pixel assertions, as in `core/ui/FocusTreatmentTest.kt` |
| Android Keystore, ExoPlayer, MediaSession, or WebView | Instrumented tests on a TV target |
| TV UI, accessibility, or playback behavior | Relevant automated tests plus a manual device pass |

Do not add screenshot tests by default. Existing geometry, semantics, and targeted pixel
assertions cover different properties; select the one that proves the behavior. A semantics
assertion that focus moved does not prove a visible ring was drawn. Open coverage gaps are
tracked in the [cleanup backlog](cleanup-backlog.md#4-test-coverage-still-open).

## Test conventions

Paths in this section are relative to `app/src/test/java/com/igloo/blindpenguincoder/` for JVM
tests and `app/src/androidTest/java/com/igloo/blindpenguincoder/` for instrumented tests.

### JVM tests

The stack uses JUnit 4, `org.junit.Assert`, kotlinx-coroutines-test, and Ktor `MockEngine`.
There is no mocking framework or Android-resource unit-test configuration. Use hand-written
fakes and existing seams rather than introducing a mocking library.

`data/repository/TestHttp.kt` creates the real HTTP client, auth plugin, APIs, repositories,
token provider, and auth event bus over `MockEngine`. It supplies `FakeProfileStore` and a
controllable clock. Start here for request-shape, serialization, error-mapping, cache, and
profile-ordering tests so the production network pipeline is exercised.

Storage fakes include `FakeProfileStore`, `FakeSecretCipher`, and
`InMemoryPreferencesDataStore` in `core/storage/`. Coroutine tests use `runTest`, test
dispatchers, and `runCurrent`; restore `Dispatchers.Main` after replacing it in a test.

`OpenApiContractTest` reads [openapi.json](openapi.json) through the `igloo.openapi` system
property configured in the app build. It checks routes and model/schema correspondence, but
cannot establish that a live server matches the checked-in contract. Inspect a sanitized live
response when diagnosing a decode failure.

### Compose and gate tests

Use the v2 Compose JUnit APIs: `androidx.compose.ui.test.junit4.v2.createComposeRule`, or
`createEmptyComposeRule` when a gate suite launches `MainActivity` with `ActivityScenario`.
The runner is `androidx.test.runner.AndroidJUnitRunner`.

When a test needs `AnimationScaleRule`, keep it outside the Compose rule:

```kotlin
@get:Rule(order = 0) val animations = AnimationScaleRule()
@get:Rule(order = 1) val compose = createComposeRule()
```

This disables system animation before Compose starts and restores the previous setting after
the test. The welcome backdrop loops indefinitely; without reduced motion, waiting for the
Compose clock to idle can hang.

Use `GateWaits.kt` helpers (`awaitScreen`, `awaitContentDescription`, `awaitTestTag`) for the
auth gate rather than assuming `waitForIdle` means DataStore restore has completed. These
helpers require non-empty window bounds, avoiding stale nodes left by a previous activity.

- `TestIglooApp.kt` wraps the real shell with inert dependency fixtures. The caller supplies
  `IglooTheme`, allowing UI scale and theme variation.
- `HomeTestFixtures.kt` provides media/state fixtures without network image URLs and fake player
  engine factories.
- `LocalApiServer.kt` serves a loopback API on an allocated port for server-driven gate flows,
  including PIN verification and Quick Connect. It records requested paths, returns 404 for
  unimplemented routes, and handles connections independently so hanging requests do not block
  other routes.
- A held Center (the episode card's long-press) is `keyDown`, then `waitUntil` the menu's tag
  appears, then `keyUp` through `onRoot()`: foundation's long-press timer runs on real time, not
  the Compose clock, and the card has left the semantics tree under the menu by the time the
  release is sent.
- Prefer `testTag` selectors, following existing tags such as `navigation_rail`, `movies_grid`,
  and `poster_card_${id}`. Use Coil's `FakeImageLoaderEngine` when an image result matters;
  see `feature/movies/MovieDetailsOverMediaTest.kt`.

Use semantics assertions for spoken labels, announcements, and decorative content; bounds
assertions for order, clipping, and overscan; pixel assertions for visible focus. The design
system defines [focus behavior](design-system.md#6-focus--interaction) and
[accessibility expectations](design-system.md#12-accessibility).

### TalkBack and instrumentation

`UiAutomation` suppresses other accessibility services while connected. `AnimationScaleRule`
uses it, so a full connected run with TalkBack enabled does not prove the spoken-feedback
branches ran. `spokenFeedbackEnabled()` checks enabled spoken services, rather than merely
`AccessibilityManager.isEnabled` (which can be true because of automation itself).

Run relevant suites that do not connect `UiAutomation` alone on a TalkBack-enabled TV, and
confirm the service remains active. For example:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.igloo.blindpenguincoder.QuickConnectGateTest
```

Also drive the flow manually with TalkBack. Avoid `uiautomator dump` during that manual session
because it can disturb the accessibility service. On TV, important spoken content must be
reachable through input focus or deliberate reading stops.

Voice search is the same shape: instrumentation cannot speak into the recogniser, so the Search
suites drive the pane through `FakeVoiceSearch` (`SearchTestFixtures.kt`) — listening, partial
guesses, a recognised phrase, a cancel, a no-match — and the real round trip (press, speak into
the remote, results) is a manual check on the Shield. The emulator and the Fire TV each prove one
half: the button composes where a recogniser answers and is absent where none does.

## Device validation

| Target | Purpose |
| --- | --- |
| Nvidia Shield | Playback, audio passthrough, remote media keys, and TalkBack on real hardware |
| Compatible Fire TV | Fire TV behavior without Google Play Services or Play Store assumptions |
| Android TV / Google TV 1080p AVD | Everyday D-pad, layout, and focus validation |
| 4K TV target | High-resolution artwork and playback paths |

Use API 28 or later. Phones, tablets, and generic emulator images are outside the supported
targets. Check layout at the design system's reference viewport and UI scales; a 4K target
does not imply extra layout space. See the [screen model](design-system.md#2-screen--scale-model).
Keep the display awake during connected tests.

### Manual ADB runbook

Boot the TV AVD using Android Studio Device Manager, or connect a physical TV with debugging
enabled. Confirm the intended target before installing or resetting anything:

```bash
adb devices
adb wait-for-device
adb shell getprop sys.boot_completed
```

Wait for `sys.boot_completed` to report `1`. If multiple devices are attached, use
`adb -s <serial>` throughout; select a single target before running connected tests.
Build, install, and launch using the [README commands](../README.md#command-line).

Useful commands while driving a flow:

```bash
adb exec-out screencap -p > shot.png
adb shell input keyevent 23
adb shell dumpsys activity activities
adb shell dumpsys media_session
```

D-pad keyevents are `19` Up, `20` Down, `21` Left, `22` Right, `23` Select, and `4` Back.
`66` is Enter and can submit a field; Back may dismiss the IME before navigating.
`adb shell input text` can lose characters when the IME changes focus, so prefer Quick Connect
for pairing and validate credential entry separately.

Start with welcome → server setup → sign-in → Home, then check incorrect credentials, profile
switching, PIN entry, and force-stop/relaunch. Verify sign-in field traversal, returning from
details and playback, and focus restoration. Compare behavior with the relevant
[screen specification](design-system.md#11-screens--ux), including failure and retry states.
Use populated libraries for paging and queue-refill checks.

To check server health from the development computer, replace this example address and port
with your server's actual origin:

```bash
server_origin='http://localhost:8080'
curl --fail --show-error "$server_origin/api/health"
```

This checks host connectivity only. The TV must reach the same server using its own reachable
address; an Android Emulator uses `10.0.2.2` for the development computer. DNS, VPN routing,
firewall rules, and TLS trust can differ between host and TV.

For repeatable Quick Connect approval through the API, use a test account and a temporary
cookie jar. Replace the credentials and displayed code in this example:

```bash
cookie_jar=$(mktemp)
curl --fail --show-error -c "$cookie_jar" -X POST "$server_origin/api/auth/login" \
  -H 'Content-Type: application/json' \
  --data '{"email":"tester@example.com","password":"<test-account-password>"}'
curl --fail --show-error -b "$cookie_jar" -X POST "$server_origin/api/quick-connect/approve" \
  -H 'Content-Type: application/json' --data '{"code":"<displayed-code>"}'
rm -f "$cookie_jar"
```

Keep credentials and session cookies out of shared logs and repository files. Approval uses a
browser-style session cookie; the TV's bearer token cannot approve another device.

### Resetting and inspecting state

Force-stop and relaunch to exercise stored-session restoration:

```bash
adb shell am force-stop com.igloo.blindpenguincoder
adb shell am start -n com.igloo.blindpenguincoder/.MainActivity
```

To repeat first launch on a test target, the following command **erases all local app data**,
including saved profiles and the server address. Relaunch afterwards:

```bash
adb shell pm clear com.igloo.blindpenguincoder
```

On a debug build, inspect DataStore file presence with:

```bash
adb shell run-as com.igloo.blindpenguincoder ls -l files/datastore/
```

File size alone does not prove that the correct profile or encrypted preference was removed.
Validate the resulting profile behavior and server state as well.

### Per-profile sign-out

Store two test profiles, A and B, and sign in as A. Add the second profile through **Switch
profile → Add profile**, then return to A. Use only the remote:

1. Open **Sign out**. Confirm Cancel initially has focus, the dialog contains focus, and Back
   closes it and restores focus to the invoking row.
2. Open it again and confirm sign-out. Only B should remain in the profile picker.
3. In each account's authenticated Igloo web session, verify A's TV device was revoked and B's
   remains. The equivalent API check is `GET /api/devices` with that account's session cookie.
4. Select B and confirm it works without pairing again (enter its PIN if required).
5. Sign out B. Expect the sign-in screen, then force-stop and relaunch: it should remain signed
   out with the server address preserved.

Also exercise the offline sign-out notice by making the server unreachable independently of
the ADB connection. A single-profile check cannot establish that another household member's
session survived.

### Playback and manual troubleshooting

- **No Compose hierarchies found:** Wake the TV display and keep it awake, then rerun the focused
  suite. Check activity launch if the display was already on.
- **Instrumented tests hang:** Check animation-rule order and use the gate wait helpers for
  asynchronous session restore.
- **Focus reported but no ring visible:** Check modifier order. `Modifier.clickable` already
  includes a focus target; do not append a redundant `.focusable()`, and place `.onFocusChanged`
  before `.clickable`. Use a rendered assertion to verify the ring.
- **D-pad Up/Down fails in a text field:** Preserve `IglooTextField`'s `onPreviewKeyEvent`
  handling; `BasicTextField` can otherwise consume those keys.
- **Black player screenshots:** Hardware video may be absent from captures. Watch advancing
  timecodes and transport state, inspect MediaSession/logcat, and confirm picture and audio
  directly on the TV. A screenshot alone cannot establish playback failure or success.
- **Trailer fails:** Inspect app-process logcat for Chromium console/embed errors. Test the
  actual WebView on the device.
- **Decode failure despite passing contract tests:** Compare a sanitized live server response
  with `openapi.json`; a passing test only confirms the checked-in schema.
- **401 from `/api/devices` with a TV token:** Use a test account's browser session cookie;
  those endpoints deliberately reject device bearer tokens.

Validate Direct and HLS playback, seeking, resume, track selection, mode changes, progress writes,
and exit behavior with suitable media. Use real hardware for TrueHD, DTS-HD, AC-3, E-AC-3,
Atmos, HDMI passthrough, remote media keys, and TalkBack; emulator results do not establish
hardware codec or audio compatibility. Record the device and media configuration alongside
results. See [backend FFmpeg behavior](ffmpeg.md) and the
[playback UX contract](design-system.md#118-playback).
