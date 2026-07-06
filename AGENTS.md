# AGENTS.md

## Project overview

Igloo is a modern media center Android TV app written in Kotlin.  This repository is the official Android TV client for the Igloo platform.  It communicates with the Igloo Go backend from the main Igloo server/web repository, referenced in this file as the **main Igloo repository**.

Igloo is not a Jellyfin, Plex, or third-party media-server client.  Plex and Jellyfin may be referenced conceptually when discussing media-center behavior, but this app must not depend on them or copy their implementation.

The app name is **Igloo**.  The package name is:

```text
com.igloo.blindpenguincoder
```

The app is pre-production.  Do not preserve backward compatibility unless explicitly requested.  Do not add migrations or compatibility layers unless the task specifically requires them.

## Related repositories and source-of-truth files

This repository contains the Android TV client only.  The Igloo backend and web client live in the main Igloo server/web repository.

### Local reference paths

When working locally, prefer a sibling checkout of the main Igloo repository.

Expected local layout:

```text
projects/
  Igloo/        # main Go backend + web client
  IglooTV/      # this Android TV client
```

From this repository, the main Igloo repo is expected at:

```text
../Igloo
```

If that path does not exist, do not guess.  Ask the user or use the in-repo API/design files.

### Canonical upstream reference

The main Igloo repository should be treated as the canonical implementation reference for backend behavior, API contracts, and existing web-client UX patterns.

Canonical repository:

```text
https://github.com/jibanez74/Igloo
```

Do not clone, pull, or modify the main Igloo repository.

### Source-of-truth files

Prefer source-of-truth files checked into this repository when available:

```text
docs/openapi.json
docs/design-system.md
```

Rules:

- Use `docs/openapi.json` as the API contract for this Android TV app.
- Use `docs/design-system.md` as the design-system reference for UI work.
- If these files are missing or outdated, check the main Igloo repository at `../Igloo`.
- Do not invent backend endpoints, response models, theme tokens, or screen behavior.
- When API behavior conflicts between this repository and the main Igloo repo, stop and ask which source should win.

## Non-negotiable rules

- Build for Android TV, Google TV, and Android-based Fire TV devices.
- Do not turn this into a mobile, tablet, desktop, or touch-first app.
- Use Kotlin.  Avoid Java unless there is a strong technical reason.
- Prioritize TalkBack, D-pad navigation, focus behavior, playback reliability, and maintainability.
- Keep changes focused on the requested task.
- Inspect relevant files before modifying code.
- Do not make broad refactors unless explicitly requested.
- Do not leave dead code behind.
- Do not introduce Firebase, cloud messaging, analytics SDKs, CDN assumptions, or cloud-only architecture unless explicitly requested.
- Do not add large dependencies without approval.
- Do not hardcode production server URLs, local network IPs, Tailscale IPs, or developer-machine details.
- Do not assume HTTPS is required.  Igloo must support self-hosted HTTP and HTTPS servers.
- Do not commit code that logs passwords, cookies, auth tokens, QR secrets, quick-connect secrets, session tokens, or device credentials.

## Target platforms

Igloo targets TV platforms controlled by a remote:

- Android TV
- Google TV
- Android-based Fire TV / Firestick devices
- Nvidia Shield as the primary real-device development target
- Firestick as a secondary real-device target
- Android Studio TV emulator for fast iteration

Minimum SDK:

```kotlin
minSdk = 28
```

Do not support phone/tablet layouts unless explicitly requested.  The future mobile app will be built separately, likely with React Native.

During development, test on either:

- Nvidia Shield connected through a Tailscale address or local network.
- Android Studio Android TV emulator.

A real Nvidia Shield test is preferred for playback, passthrough, D-pad, and TalkBack behavior.

## Build system and tooling

Use the Gradle wrapper for all Gradle commands.  Do not rely on globally installed Gradle.

The main development machine is Ubuntu 24.04.  Assume commands are run from Linux unless the user says otherwise.

Rules:

- Use `./gradlew` for build and test commands.
- Do not change Gradle, Kotlin, Android Gradle Plugin, or SDK versions unless the task requires it.
- Do not add or modify build plugins unless necessary for the task.
- If a build, test, install, or device command fails, report the command and the failure clearly.
- Keep build-system changes minimal and focused.

## Recommended technology stack

Prefer these defaults unless existing project code establishes a different pattern:

- Kotlin
- Jetpack Compose for TV
- AndroidX TV libraries where useful for TV-specific focus and layout behavior
- AndroidX Media3 / ExoPlayer for video and audio playback
- Media3 Compose UI primitives only where they fit the custom Igloo design system
- Ktor Client with kotlinx.serialization for HTTP and WebSocket work
- DataStore for local preferences
- Coil or another modern Compose-compatible image loading library for posters, backdrops, cast images, crew images, production company logos, album covers, musician images, and playlist artwork
- Manual dependency wiring at first

Do not add Hilt, Koin, Room, Firebase, analytics SDKs, crash-reporting SDKs, or large logging frameworks without approval.

## Design system

The Igloo design system lives in `docs/design-system.md`.

Before creating or modifying UI, read that document and follow it as the source of truth for:

- color tokens
- light/dark theme behavior
- typography and spacing
- focus states
- motion rules
- loading, empty, and error states
- media card behavior
- navigation shell patterns

Rules:

- Do not copy the full design system into this file.
- Dark theme is the default unless the user has selected another preference.
- Use the Igloo glacier focus color consistently.
- Convert hover behavior from the web app into D-pad focus behavior on Android TV.
- Respect reduced-motion settings.
- Every screen must work with D-pad navigation and TalkBack.

## Accessibility and TalkBack

TalkBack support is mandatory.  Accessibility is a product requirement, not a later enhancement.

Every screen must be usable with:

- D-pad remote navigation.
- TalkBack enabled.
- Large-screen TV viewing distance.

Rules:

- Do not create unlabeled buttons, icon buttons, menus, tabs, or actionable cards.
- Do not overload TalkBack with decorative or redundant information.
- Decorative images should be hidden from accessibility services when they do not add meaning.
- Interactive elements must have meaningful labels.
- Media cards should announce the information that matters in context.  For example, a poster card in a grid may only need the title, while a detail or continue-watching card may need title, year, duration, watched state, or progress.
- Avoid TalkBack focus traps.
- Avoid custom focus behavior that breaks screen reader navigation.
- Preserve a predictable reading and focus order.
- Loading, empty, and error states should be understandable to TalkBack users.
- State changes that matter should be announced when appropriate.
- Do not sacrifice the visual UI/UX for accessibility, and do not sacrifice accessibility for visual cleverness.  Design both together.

When modifying UI, reason about the accessibility tree and D-pad focus path before finishing.

## Remote control and focus behavior

Igloo is remote-first.  All primary interactions must work with a D-pad remote.

Rules:

- Do not require touch gestures.
- Do not assume hover or mouse input.
- Do not hide required actions behind hover-only behavior.
- Convert hover reveals from the web design into focus reveals on TV.
- Preserve focus when returning from detail screens, dialogs, player screens, and settings.
- Avoid giant nested scroll containers unless necessary.
- Manually control focus movement only when Compose defaults produce poor D-pad behavior.
- D-pad movement should feel spatially predictable.
- Back behavior must be deliberate and consistent.
- Dialogs and sheets must trap focus only while open and must restore focus to the previous element when closed.
- Sidebar-to-content and content-to-sidebar movement must be predictable.

Main navigation should use a TV-friendly left navigation spine or equivalent TV-native shell.

## Architecture

Prefer simple, modern Android architecture:

```text
UI / Compose screen
  -> ViewModel / screen state holder
    -> Repository
      -> API client / local preference data source
```

Rules:

- Keep architecture simple.
- Prefer MVVM-style screen state management.
- Use ViewModels for screen state and UI logic.
- Represent UI state with Kotlin data classes.
- Use clear loading, success, empty, and error states.
- Prefer unidirectional data flow.
- Keep business/networking logic out of composables.
- Use repositories between ViewModels and data sources.
- Do not add a domain/use-case layer unless it removes real duplication or simplifies complex logic.
- Do not add abstractions before they are needed.
- Prefer existing project patterns once the project has them.

Manual dependency wiring is preferred at first.  Do not add Hilt or Koin without approval.

## Preferred project structure

Prefer organization by feature with shared core modules/components where useful.  Keep the structure understandable for someone learning Kotlin Android development.

A preferred structure is:

```text
app/
  src/main/java/com/igloo/blindpenguin/
    MainActivity.kt

    core/
      config/
      design/
      error/
      navigation/
      network/
      storage/
      ui/

    data/
      api/
      model/
      repository/

    feature/
      auth/
      home/
      movies/
      tvshows/
      music/
      settings/
      player/

    playback/
      model/
      media3/
      progress/

    images/
      ImageUrlResolver.kt
      ImageFallbacks.kt
```

Rules:

- Keep screen-specific code inside its feature package.
- Keep reusable UI components in `core/ui` or a clearly named shared UI package.
- Keep theme tokens and design-system mapping in `core/design`.
- Keep navigation definitions centralized enough to understand app flow.
- Keep API models, generated clients, and repositories out of composables.
- Keep playback-specific logic separate from ordinary screen UI.
- Keep image URL construction and provider-specific image handling outside composables.
- Do not create excessive tiny packages before the project needs them.
- Prefer clear boundaries over heavy abstraction.

## Networking and API

The TV app communicates directly with the official Igloo Go backend.  It must not connect to Jellyfin, Plex, Firebase, cloud services, analytics platforms, CDNs, or third-party media servers.

### Server configuration

The server URL is entered manually by the user during setup and persisted locally.

Supported server URL formats:

```text
http://192.168.1.50:8080/api
http://100.x.x.x:8080/api
http://igloo.local:8080/api
https://example.com/api
```

Rules:

- Do not hardcode production server URLs.
- Do not hardcode LAN IPs, Tailscale IPs, domains, ports, or developer-machine details.
- Support HTTP and HTTPS.
- Do not require HTTPS.
- Do not assume a CDN, reverse proxy, cloud deployment, or public domain.
- Validate the server URL before saving it.
- Normalize trailing slashes so API paths are not accidentally built incorrectly.
- Persist the selected server URL with DataStore or the project’s selected local preference store.

### Development server URLs

Use different development URLs depending on the target:

```text
Android Studio emulator -> http://10.0.2.2:8080/api
Physical Android TV device -> LAN IP, hostname, or Tailscale address
```

Do not use `localhost` for a physical Android TV device.  On a real device, `localhost` refers to the device itself, not the developer machine.

### API contract

`docs/openapi.json` is the source of truth for API routes, request bodies, response bodies, and error formats in this Android TV repository.  If it is missing or appears outdated, check the main Igloo repository at `../Igloo` before creating or changing API calls.

Rules:

- Inspect `openapi.json` before creating or changing API calls.
- Do not invent backend endpoints.
- Do not create request or response models that contradict `openapi.json`.
- Codex may generate API client code from `openapi.json`.
- Keep generated API code isolated from handwritten application code.
- Do not manually edit generated files unless the project explicitly chooses that approach.
- Wrap generated clients behind repositories or service classes when that makes the app code clearer.
- If API client generation requires a new Gradle plugin, generator, or large dependency, ask for approval first.

### Response handling

The backend commonly returns this shape:

```json
{
  "message": "message to say what happened",
  "error": true
}
```

Successful responses may also use the same envelope pattern with `error: false`.

Rules:

- Normalize backend failures into app-level error models.
- Preserve backend error messages when they are safe and useful.
- User-facing errors should be clear and actionable.
- Avoid generic messages like `Something went wrong` when better information is available.
- Every recoverable API failure should provide a retry path where appropriate.
- Do not silently ignore failed requests.

### Networking implementation

Prefer the networking stack selected by the project.  If no stack exists yet, prefer a modern Kotlin-friendly client that supports both HTTP and WebSockets cleanly.

Rules:

- Keep API and networking logic out of composables.
- Network calls should be suspend functions or expose Flow where appropriate.
- Do not block the main thread.
- Keep request construction, response parsing, and error mapping in the data layer.
- WebSocket support will be needed for shared-watch features.
- Request and response logging must be debug-only.
- Do not commit code that logs passwords, cookies, auth tokens, QR secrets, quick-connect secrets, session tokens, or device credentials.

## Authentication and users

Igloo TV should support multiple users from the start.

The TV app should authenticate through the Igloo backend.  Do not implement authentication against Plex, Jellyfin, Firebase, Google accounts, Amazon accounts, or any third-party identity provider unless explicitly requested.

### Pairing flow

The first-time setup should support both:

- Quick-connect code.
- QR code.

The user should be able to choose whichever method is easier at the moment.

Rules:

- Do not require typing a full username and password with a TV remote unless explicitly requested.
- Pairing must be backed by the Igloo API.
- Do not invent authentication endpoints.  Check `openapi.json`.
- If the backend endpoint does not exist yet, create client-side placeholders only when the task explicitly calls for it.
- The app should remember the selected server and authenticated user after setup.
- The app must support logout.
- Expired or invalid sessions should return the user to the setup/authentication flow.

### Multiple users

Rules:

- Support multiple users/profiles from the start.
- Do not assume a single-user household.
- Keep user selection usable with a D-pad remote.
- Do not expose admin-only actions to non-admin users.
- Treat backend permissions as the source of truth.

### Credential and token storage

Rules:

- Never store raw user passwords.
- Do not commit code that logs passwords, cookies, auth tokens, QR secrets, quick-connect secrets, session tokens, or device credentials.
- Store sensitive authentication material using the project’s approved secure storage approach.
- Use DataStore only for non-sensitive preferences unless the data is encrypted or otherwise protected.
- Local PIN behavior may be added later for convenience, but it must not replace server-side authentication.

## Local storage

Use DataStore for local app preferences, such as:

- Server URL.
- Last selected server.
- Theme preference.
- Playback preset.
- Lightweight app settings.

Do not add Room or another local database unless explicitly approved.

The backend is the source of truth for:

- Media libraries.
- Movies.
- TV shows.
- Music.
- Photos, when added.
- Watch progress.
- Watched/unwatched state.
- Recently watched.
- User data and permissions.

Offline browsing is out of scope for the current phase.

## Media playback

Media playback is a core feature of Igloo.  Do not treat it as a generic video-player screen.

Rules:

- Prefer AndroidX Media3 / ExoPlayer for the first implementation.
- Do not add VLC in the first phase.
- Design the playback layer so VLC or another external/player option can be added later if explicitly requested.
- Support both direct playback and HLS playback.
- Direct playback is a priority, but HLS must also be treated as a first-class playback mode.
- Do not support DASH unless explicitly requested.
- Do not transcode, downmix, decode, or transform media in the TV app.
- Audio/video transformation belongs to the Igloo backend and its FFmpeg pipeline.
- Preserve audio passthrough when possible.
- Do not accidentally force stereo output.
- Treat TrueHD, DTS-HD, AC3, E-AC3, and Atmos support as important.
- Expose audio track selection.
- Expose subtitle track selection.
- Support embedded subtitle tracks where the device/player stack can handle them.
- Playback settings selected by the user take priority over automatic choices.
- The movie pre-play/details screen should allow playback settings to be adjusted before playback.
- Save playback progress to the backend every 15 seconds.
- Do not start saving playback progress until at least 15 seconds of actual video playback has occurred.
- In practice, the first progress save should happen around 30 seconds of actual playback.
- Resume position, watched status, watch history, and watched/unwatched state come from the backend.
- Do not invent playback API routes.  Check `openapi.json`.

For detailed player UX, controls, loading states, focus behavior, and visual design, read `docs/design-system.md`.

## Images and metadata

Igloo uses remote metadata and artwork from multiple providers.  Image handling must be reliable, cache-friendly, and accessible without overwhelming TalkBack users.

### Image sources

Movie-related images usually come from TMDB, including:

- movie posters
- movie backdrops
- cast profile images
- crew profile images
- production company logos

Music-related images usually come from Spotify, including:

- album covers
- musician / artist images
- playlist artwork when available

The Igloo backend is the source of truth for stored media metadata.  Do not call TMDB or Spotify directly from the TV app unless explicitly requested.

### URL handling

Rules:

- Do not put TMDB API keys, Spotify credentials, or provider secrets in the TV app.
- Use the image paths or URLs returned by the Igloo API.
- If the backend returns TMDB paths such as `poster_path`, `backdrop_path`, `profile_path`, or `logo_path`, build the final public image URL in a shared image URL helper.
- Do not scatter TMDB URL construction across composables.
- If the backend returns a full Spotify image URL, use it as provided.
- Keep provider-specific URL logic outside UI components.
- Handle missing, empty, malformed, or unreachable image URLs gracefully.

### Image loading

Rules:

- Use a modern Compose-compatible image loading library, preferably Coil unless the project chooses another library.
- Enable normal image caching through the selected image library.
- Use appropriately sized images for TV layouts.
- Do not load full-resolution images when a poster, thumbnail, or medium-size image is enough.
- Preserve visual quality for TV viewing distance.
- Avoid image loading logic inside ViewModels unless the data layer needs to normalize provider paths.
- Do not block the UI while images load.

### Fallbacks

Every image surface must have a fallback.

Examples:

- Movie poster missing -> Igloo poster placeholder.
- Movie backdrop missing -> themed gradient or dark surface.
- Cast or crew profile missing -> person/avatar placeholder.
- Production company logo missing -> text-only company name.
- Album cover missing -> album placeholder.
- Musician image missing -> artist/avatar placeholder.

Fallbacks should follow the Igloo design system and must work in both dark and light mode.

### Accessibility

Not every image should be exposed to TalkBack.

Rules:

- Decorative backdrop images should usually be hidden from accessibility.
- Poster/card images should not duplicate nearby readable text.
- Interactive media cards should announce useful item information, usually the title and only extra context when it helps.
- Cast, crew, musician, and album images should be labeled only when the image itself is the interactive element or when no adjacent text provides the same information.
- Production company logos should not be the only accessible representation of the company.  Provide the company name as text.
- Avoid verbose descriptions that slow down D-pad and TalkBack navigation.

### Metadata display

Rules:

- Do not invent metadata fields that are not provided by the Igloo API.
- Do not call TMDB or Spotify directly to fill missing metadata unless explicitly requested.
- Prefer backend-provided metadata over client-side assumptions.
- Display missing metadata gracefully instead of showing raw `null`, empty strings, or broken placeholders.
- Keep metadata formatting reusable, especially for dates, runtime, genres, cast, crew, album details, and musician details.

For visual treatment of posters, backdrops, cards, gradients, placeholders, focus states, and image-based layouts, read `docs/design-system.md`.

## UI states

Build shared composables for common states instead of recreating them inconsistently:

- `IglooLoading`
- `IglooEmpty`
- `IglooError`
- Skeleton/poster-grid placeholders
- Retry actions

Rules:

- Every loading screen should have an error state.
- Every recoverable error should have a retry action.
- Skeletons should match the final layout to avoid large focus jumps when content loads.
- Empty states should be clear and useful.
- Error messages should provide real information without overwhelming the user.
- Playback errors should be more detailed than ordinary list-loading errors when codec, container, or network information is useful.

## Dependency policy

Keep dependencies intentional and minimal.  Do not add a library just to avoid writing a small amount of straightforward Kotlin.

Before adding, removing, or upgrading dependencies:

- Inspect the existing Gradle files.
- Check whether the project already has a dependency or pattern that solves the problem.
- Prefer the existing project stack over introducing a competing library.
- Do not change Gradle, Kotlin, Android Gradle Plugin, or SDK versions unless the task requires it.
- Use the correct dependency scope: `implementation`, `debugImplementation`, `testImplementation`, or `androidTestImplementation`.
- Explain why the dependency is needed when adding it.

### Allowed project stack

These dependencies are acceptable as part of the planned Igloo Android TV stack, unless the project later chooses a different direction:

- Jetpack Compose for TV.
- AndroidX TV libraries.
- AndroidX Lifecycle and ViewModel libraries.
- AndroidX Navigation / Navigation Compose.
- AndroidX Media3 / ExoPlayer.
- Kotlin coroutines.
- Kotlinx serialization.
- Ktor Client for HTTP and WebSocket work.
- DataStore for local preferences.
- Coil for Compose-compatible image loading.
- Standard AndroidX testing libraries.
- JUnit and Kotlin test tools.

Do not add competing libraries for the same job without a clear reason.  For example, do not mix Ktor and Retrofit unless the project explicitly chooses to do so.

### Requires approval

Ask before adding dependencies that materially affect architecture, storage, build behavior, telemetry, or playback strategy.

Requires approval:

- Room or any local database.
- Hilt, Koin, or another dependency injection framework.
- Retrofit if Ktor is already being used.
- Ktor if Retrofit is already being used.
- VLC or another alternate media player stack.
- OpenAPI generator Gradle plugins or generated-client tooling that changes the build.
- Large logging frameworks.
- Crash-reporting SDKs.
- Analytics SDKs.
- Background task frameworks unless the feature clearly requires them.
- Any library that introduces cloud, account, telemetry, or external-service assumptions.
- Any dependency that significantly increases app size or complexity.

### Forbidden unless explicitly requested

Do not add these by default:

- Firebase.
- Cloud messaging SDKs.
- Analytics or tracking SDKs.
- CDN-related SDKs or assumptions.
- Plex, Jellyfin, or third-party media-server client libraries.
- Abandoned, deprecated, or low-maintenance libraries.
- Legacy packages when modern AndroidX/Kotlin alternatives exist.
- Libraries that require a cloud-hosted backend to make core app features work.
- Libraries that collect user data or telemetry without an explicit product decision.

### OpenAPI client generation

Codex may generate API client code from `openapi.json` when requested or when it clearly improves API correctness.

Rules:

- Keep generated code isolated from handwritten app code.
- Do not manually edit generated files unless the project explicitly chooses that approach.
- Wrap generated clients behind repositories or service classes when useful.
- If generation requires a new plugin, CLI tool, or build-system change, ask for approval first.
- Do not invent API models or endpoints that contradict `openapi.json`.

### Dependency quality

A dependency must be reasonably modern, maintained, and compatible with the project’s Android/Kotlin stack.

Avoid dependencies that:

- Have no recent maintenance activity.
- Are poorly documented.
- Conflict with Compose for TV or Media3.
- Require unnecessary permissions.
- Add background services without a clear need.
- Make the app harder to use on Android TV, Google TV, or Android-based Fire TV devices.

## Code style

- Keep comments minimal.
- Use comments only for non-obvious behavior.
- Prefer clear names over comments.
- Avoid clever Kotlin when readability suffers.
- Avoid one-line wrapper functions that add no value.
- Keep functions reasonably focused, but do not fragment code artificially.
- Some functions may be longer when that keeps the flow easier to understand.
- Prefer explicit types for public APIs and boundary models.
- Keep UI code readable for someone learning Kotlin Android development.
- Avoid over-engineering.
- Favor accessibility, maintainability, and playback correctness over visual cleverness.

## Testing and validation

Testing is required.  Do not treat build success as enough, especially for UI, focus behavior, TalkBack behavior, and media playback.

### Test expectations

For every meaningful change, Codex should run the smallest relevant tests first, then broader validation when practical.

Use the Gradle wrapper:

```bash
./gradlew test
./gradlew build
```

For Android/device tests, use:

```bash
./gradlew connectedAndroidTest
```

Run `connectedAndroidTest` only when an emulator or physical device is available.  If it cannot be run, clearly state why.

### Required test coverage

Add or update tests when the change affects:

- ViewModel state behavior.
- Repository/API response handling.
- Error handling.
- Authentication flow.
- Server URL validation.
- Playback settings logic.
- Playback progress reporting.
- Navigation behavior.
- Accessibility-sensitive UI behavior.
- Focus behavior for TV remote navigation.

Each feature should include happy-path and error-path coverage where practical.

### Device and emulator validation

Codex is expected to validate important app behavior directly on a running Android TV device or emulator when tools are available.

During development, the app may be tested using:

- Nvidia Shield connected through a Tailscale address or local network.
- Android Studio Android TV emulator.
- Android-based Fire TV device when available.

When ADB MCP tools are available, Codex must use them to interact with the app directly on the device or emulator for UI and playback-related work.

Use ADB MCP tools to verify:

- The app installs and launches.
- D-pad navigation works.
- Focus moves predictably.
- Focus does not get trapped.
- Back button behavior is correct.
- TalkBack/accessibility labels are reasonable where inspectable.
- Login/setup flows are usable with a remote.
- Media cards, rows, tabs, dialogs, and menus are reachable.
- Player controls can be reached and operated.
- Playback starts correctly for the tested mode.
- Error and retry states are visible and usable.

Do not rely only on static code review for TV UI changes.

### Accessibility validation

TalkBack support is mandatory.  For UI changes, validate accessibility behavior as much as the available tools allow.

Check that:

- Interactive controls have useful labels.
- Decorative images are not unnecessarily announced.
- Media cards do not repeat redundant information.
- Screen order is logical.
- Dialogs trap focus only while open and restore focus after closing.
- Loading, empty, and error states are understandable.
- D-pad and TalkBack navigation do not conflict.
- Focus indicators are visually clear.

### Manual validation notes

When a change affects UI, playback, navigation, or accessibility, Codex must include manual validation notes in its final response.

Include:

- Device or emulator used, if any.
- Commands run.
- ADB MCP interactions performed.
- What was verified.
- Any tests that could not be run.
- Any behavior that still needs real-device verification.

### Screenshot tests

Do not add fragile screenshot tests by default.

Screenshot tests may be useful for visual regression work, but they require an explicit reason.  Prefer functional UI tests, accessibility checks, and direct device validation first.

### Before finishing

Before marking work complete, Codex must:

- Run relevant unit tests.
- Run a build when practical.
- Run connected/device tests when a device or emulator is available.
- Use ADB MCP tools for direct app interaction when the task affects UI, focus, accessibility, setup, or playback.
- Report any command or device validation that could not be completed.

## Self-hosting assumptions

Igloo servers may run on:

- A home server.
- A VPS such as Linode.
- A cloud VM such as EC2.
- A local network address.
- A Tailscale address.
- A domain with or without HTTPS.

Rules:

- Support custom hostnames and ports.
- Support HTTP.
- Support HTTPS.
- Do not require users to configure HTTPS or TLS certificates.
- Do not assume a CDN.
- Do not assume cloud deployment.
- Do not assume the backend is on the same device or network.

