# Developing IglooTV

For prerequisites, Android Studio setup, and installing a debug build, start with the
[README](../README.md#build-and-run). For validation commands and device procedures, see
[Testing IglooTV](testing.md).

## Architecture

The app uses MVVM with unidirectional data flow:

```text
Compose UI -> ViewModel / immutable screen state -> Repository -> Ktor API
                                                            -> DataStore
```

ViewModels expose immutable screen state, with loading, content, empty, and failure states
modeled explicitly. Compose collects state with `collectAsStateWithLifecycle`; foreground
refreshes use `LifecycleStartEffect`. Keep networking, storage, and playback logic outside
composables.

### Application wiring and navigation

`IglooApplication` creates a single `IglooAppContainer`, whose lazy services are passed from
`MainActivity` into `IglooRoot`. Dependency injection is manual.

`IglooRoot` switches on `AppAuthState` to render server setup, profile selection, PIN entry,
sign-in, or the authenticated app. `feature/home/IglooApp.kt` owns the shell's saved destination
from `IglooDestination`. Routing does not use a `NavHost`.

Details pages and players share one overlay slot. Opening another details page replaces the
current page; it does not create a nested back stack. `DetailsOrigin` and saved request state
support restoring focus to the originating card or control. The interaction contract lives in
the design system's [navigation shell](design-system.md#8-layout--navigation-shell) and
[screen specifications](design-system.md#11-screens--ux).

### Networking, authentication, and storage

`core/config/ServerUrl.kt` normalizes a user-entered HTTP(S) origin, optionally ending in
`/api`, into the server address and API base URL. It defaults a bare host to HTTP and rejects
userinfo, queries, fragments, arbitrary paths, and malformed ports. Server setup probes
`/api/health` before saving the address; there is no build-time server URL.

`core/network/IglooHttpClient.kt` builds a Ktor client with the OkHttp engine and
kotlinx.serialization. The device-auth plugin attaches the active profile's bearer token and
signals rejected credentials through `AuthEventBus`. Credential-issuing requests opt out;
revocation requests can override the token or mark an expected 401. Preserve these distinctions
when adding endpoints so stale or deliberately revoked credentials do not invalidate another
profile's session.

`SafeApiCall` and the network error mapping turn transport and backend failures into `ApiResult`
and app-level errors. Connection timeout is 10 seconds; request and socket timeouts are
30 seconds. The separate server-probe client disables automatic redirects;
`ServerHealthProbe` follows validated redirects itself, rejecting host changes, HTTPS downgrades,
loops, and excessive redirects.

The app uses device bearer tokens issued by Quick Connect or device login, rather than browser
session cookies. The API contract marks device listing, renaming, and revocation through
`/api/devices` as session-cookie operations; a device bearer token is rejected there.

Preferences DataStore holds two stores, wired in `core/storage/IglooDataStores.kt`:

- `igloo_settings`: the user-configured server address and UI scale.
- `igloo_session`: the profile vault, encrypted using `AndroidKeystoreCipher`.

Sign-out removes the selected profile and clears user-scoped caches while retaining the server
address. Other household profiles must remain available. See
[manual profile validation](testing.md#per-profile-sign-out).

### Images and playback

`IglooApplication` installs the Coil singleton loader. `core/image/IglooImageLoader.kt` adds a
bearer interceptor scoped to same-origin Igloo image URLs. Provider URL construction lives in
`images/ImageUrlResolver.kt`; composables should receive resolved URLs. Image caches are cleared
on sign-out.

Media3 implementations sit behind `VideoPlayerEngine` and `MusicPlayerEngine`, with MediaSession
wrappers for system metadata and remote media controls. Video uses a `SurfaceView` through
Media3's Compose `ContentFrame`; preserve that rendering path for TV playback.
`BearerStreamDataSource` supplies authenticated media requests, including a separate HLS source
with a longer segment read timeout. `playback/hls/` owns session lifecycle and mode switches;
`playback/progress/` owns watch-progress persistence; `playback/queue/` owns music queue refills.

The backend handles probing, remuxing, transcoding, and subtitle conversion; the client decodes
and renders streams with Media3. Read [the FFmpeg reference](ffmpeg.md) for backend behavior and
the design system's [playback section](design-system.md#118-playback) for the TV interaction
contract. Trailer playback uses a separate YouTube IFrame WebView, an exception to the usual
backend-only media-provider access; do not pass Igloo credentials to it.

## Source organization

Production Kotlin sources are under `app/src/main/java/com/igloo/blindpenguincoder/`.

| Path | Responsibility |
| --- | --- |
| `core/config/` | Server-address parsing and device identity |
| `core/design/` | Theme, dimensions, typography, motion, and UI scale |
| `core/ui/` | Shared TV controls, focus treatment, rails, menus, dialogs, QR, and text entry |
| `core/network/`, `core/error/` | HTTP clients, auth, health probing, and error handling |
| `core/storage/` | DataStore, encrypted profiles, and preferences |
| `core/image/`, `images/` | Coil wiring, authenticated images, and URL resolution |
| `core/navigation/` | Destination definitions |
| `data/api/`, `data/model/`, `data/repository/` | Routes, wire models, repositories, and caches |
| `feature/auth/`, `feature/boot/` | Session state machine and boot/auth UI |
| `feature/home/`, `feature/library/` | Navigation shell, Home, and shared movie/show library pane |
| `feature/movies/`, `feature/shows/`, `feature/music/` | Library sources, feature state, and details UI |
| `feature/player/`, `feature/shared/` | Player screens and shared feature UI |
| `feature/search/` | The Search pane, its state, and the voice-search seam over the platform recogniser |
| `playback/` | Engines, sessions, state machines, progress, music queues, and trailers |

JVM tests mirror these packages under `app/src/test/java/`; device tests are under
`app/src/androidTest/java/`. See [test conventions](testing.md#test-conventions).
Dependency versions are in [the version catalog](../gradle/libs.versions.toml), build settings
in [app/build.gradle.kts](../app/build.gradle.kts), and the wrapper version in
[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties).

## Development constraints

Build for a landscape TV controlled by a remote. Primary actions must work with the D-pad and
Back. Fire TV support means avoiding assumptions about Google Play Services or the Play Store.
The speech recogniser is one such optional capability: `VoiceSearch.available` gates the button,
the manifest's `<queries>` block lets API 30+ resolve it, the microphone is declared not
required, and `RECORD_AUDIO` — the app's only runtime permission — is asked for on the first
press, never at launch.
The platform floor is API 28.

Use the design system as the UI specification instead of duplicating its behavior rules here:

- [Screen and scale model](design-system.md#2-screen--scale-model): reference viewport,
  overscan, `UiScale`, and system font-scale handling.
- [Focus and interaction](design-system.md#6-focus--interaction): the shared focus treatment,
  traversal, dialog containment, and focus restoration.
- [UI states](design-system.md#10-ui-states) and [screens](design-system.md#11-screens--ux):
  loading, empty, failure, retry, rail, and per-screen behavior.
- [Accessibility](design-system.md#12-accessibility): TalkBack, semantics, and deliberate
  reading stops for text that input focus would otherwise skip.
- [Token-to-code index](design-system.md#appendix-b--tokencode-index): update the specification,
  implementation, and relevant design-token tests together when changing a token.

Keep the server address user-configured. Preserve certificate validation and same-origin
credential boundaries; never log passwords, tokens, pairing codes, QR secrets, or sensitive
headers. Metadata and artwork should use the backend's provider proxies. Preserve supported
audio passthrough rather than forcing stereo, and leave server-side media conversion to Igloo.

## Contributing

Read the relevant implementation and tests before changing behavior. For API work, consult
[docs/openapi.json](openapi.json); `OpenApiContractTest` checks called routes and wire models
against it. If a live response and the contract disagree, capture a sanitized reproduction and
report the mismatch before changing models based on assumptions. For UI work, read the relevant
design-system section first. Report conflicts between documentation and implementation.

Follow the surrounding Kotlin style (`kotlin.code.style=official`). Android Lint is configured
through AGP; no additional formatting or static-analysis framework is configured. Comments
should explain intent, constraints, or workarounds. Remove temporary diagnostics, dead code,
and obsolete comments as part of the change that made them unnecessary.

Keep changes focused. Reuse the existing manual dependency wiring, DataStore, HTTP, and player
seams. Discuss major framework additions or replacements before introducing them, and explain
their purpose and impact. Avoid unrelated dependency upgrades, broad refactors, speculative
abstractions, telemetry, and third-party media-server SDKs.

Add or update meaningful tests for behavior changes, including important failure paths. Run the
smallest relevant checks first, then the lint/build and device validation appropriate to the
change. TV UI, focus, accessibility, and playback changes need device validation when a target
is available. Documentation-only changes can be checked through links, commands, and consistency
with configuration without building the app. Report what ran, what was verified, and any gaps;
the [testing guide](testing.md) describes the available checks.

## Feature status and reference documents

The [README](../README.md#features-and-status) gives the current user-facing overview. These
documents record detailed behavior, implementation history, and remaining work; check the
current code when using an older status report:

- [Design system](design-system.md): visual and interaction specifications.
- [Known issues](known-issues.md) and [cleanup backlog](cleanup-backlog.md): behavior gaps,
  follow-up work, and open test coverage.
- [Movies](movies-screen-status.md) and [TV Shows](tv-shows-screen-status.md): library status.
- [Search](search-screen-status.md): the search pane over the five library categories.
- [Show details](show-details-status.md) and [episode playback](episode-playback-status.md): the
  show page, its seasons and episodes, playing them from it, the Up Next hand-off and marking
  them watched; the extras are still to land.
- [Music](music-screen-status.md), [album details](album-details-status.md), and
  [music player](music-player-status.md): music features and remaining work, including playlists,
  background playback, and play-stat reporting.
- [Music shuffle](music-shuffle.md): finite queues and rolling library shuffle.
- [Subtitle switch bug](subtitles-bug.md): a fixed Direct-to-HLS subtitle regression and coverage.
- [FFmpeg](ffmpeg.md): backend media-processing context.
