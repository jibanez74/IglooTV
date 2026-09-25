# AGENTS.md

## Project identity

Igloo is a premium media-center client written in Kotlin exclusively for Android TV, Google TV, and Android-based Fire TV devices. This repository contains the official TV client for the Igloo platform; it is not a mobile, tablet, desktop, Plex, or Jellyfin client.

The application communicates with the Igloo Go backend in the main server/web repository. It is pre-production: do not preserve backward compatibility, add migrations, or create compatibility layers unless a task explicitly requires them.

## Sources of truth

- Before API work, read `docs/openapi.json`. Do not invent routes, payloads, response models, or error formats.
- Before UI work, read `docs/design-system.md`. It defines Igloo's visual language, focus behavior, accessibility conventions, and shared screen states.
- When either document conflicts with implemented behavior, report the conflict instead of silently choosing one.
- The main Igloo repository may be inspected at `../Igloo` when the local API contract is incomplete or backend behavior must be confirmed.
- Treat the main repository as read-only. Do not clone, pull, or modify it.
- The web client may inform product behavior, but web interactions must be reinterpreted for a remote-controlled TV experience.
- Discover ordinary build files, source directories, and existing patterns from the repository; do not rely on a preferred template from this file.

## Working rules

- Inspect relevant code and tests before changing anything.
- Follow established project patterns unless the task requires changing them.
- Keep changes focused on the requested outcome.
- Do not perform broad refactors, dependency migrations, or unrelated cleanup.
- Do not leave dead code, temporary diagnostics, or commented-out implementations.
- Keep business, networking, storage, and playback logic out of composables.
- Do not implement speculative future features or abstractions.
- If requirements, contracts, or existing behavior conflict materially, stop and ask rather than guessing.
- Keep comments purposeful and minimal. Add a comment only to explain non-obvious intent, constraints, workarounds, or behavior that the code cannot express clearly. Do not narrate obvious code, and remove comments that are outdated, misleading, redundant, or no longer match the implementation.

## TV-only product requirements

Igloo is a 10-foot, landscape television experience. Every design and implementation decision must prioritize television viewing and remote control use.

- Never design a phone, tablet, touch-first, or generic responsive-mobile interface.
- Do not use handset patterns such as bottom navigation, floating action buttons, portrait layouts, narrow content columns, or touch-only gestures.
- Do not require a touchscreen, mouse, keyboard, hover state, swipe, or long-press gesture for primary functionality.
- Every primary action must be reachable and operable with a standard D-pad remote.
- Design for 16:9 displays, television viewing distance, overscan-safe spacing, and large-screen information density.
- Prefer TV-native navigation rails, media rows, grids, hero areas, dialogs, and player controls.
- Convert web hover behavior into deliberate D-pad focus behavior; do not copy the web UI literally.
- D-pad movement must be spatially predictable, and focused elements must remain visibly distinct.
- Preserve focus when returning from details, dialogs, settings, authentication, or playback.
- Back behavior must be deliberate and consistent.
- Dialogs must contain focus while open and restore it to the invoking control when closed.
- Avoid nested scrolling and manual focus overrides unless Compose defaults demonstrably produce poor TV behavior.
- Do not assume Google Play Services or the Play Store is available; core features must work on Fire TV devices.
- Nvidia Shield is the primary real-device playback target, with Fire TV and the Android TV emulator as additional targets.
- The minimum supported Android version is API 28 unless the project configuration explicitly changes.

## Premium experience

Igloo should feel like a polished, modern streaming platform rather than a utility application or enlarged mobile interface.

- Follow `docs/design-system.md` for color, typography, spacing, artwork, focus, motion, navigation, and state treatment.
- Use cinematic artwork, strong hierarchy, balanced spacing, clear focus treatment, and restrained motion.
- Keep screens visually rich without making them crowded or difficult to navigate.
- Use animation to clarify focus and state changes, not as decoration that delays interaction.
- Respect reduced-motion preferences.
- Avoid generic Material defaults when they conflict with Igloo's TV-specific design language.
- Loading, empty, error, and retry experiences are part of the premium product and must not feel unfinished.
- Performance is part of the UX: avoid unnecessary recomposition, visible jank, blocking work, wasteful image loads, and slow focus response.

## TalkBack and accessibility

TalkBack support is a first-class product requirement. Every screen must be fully usable with TalkBack and a D-pad while preserving Igloo's premium visual design and TV-native experience.

- Accessibility must be integrated into the intended design, not delivered through a simplified, visually degraded, or functionally reduced alternative.
- Do not remove artwork, hierarchy, motion, or rich layouts merely to make accessibility implementation easier.
- Do not create a separate accessible version of a screen.
- Every feature available visually must also be understandable and operable with TalkBack.
- Treat inaccessible actions, focus traps, broken reading order, and misleading announcements as functional defects.
- Give every actionable card, button, icon, tab, menu item, and player control meaningful semantics.
- Hide decorative artwork from accessibility services when it conveys no additional information.
- Avoid redundant announcements; nearby artwork and text should not repeat the same content unnecessarily.
- Media-card announcements should include only the context that helps the user act, such as title, progress, year, or watched state.
- Do not make every plain text element focusable. Attach important information to the relevant focusable element or provide a deliberate reading stop when required by the design system.
- Keep traversal and D-pad focus order predictable.
- Announce important loading, error, selection, and state changes when appropriate.
- Ensure dialogs, menus, and overlays restore focus correctly when dismissed.
- Reason about both the visible focus path and accessibility semantics before finishing UI work.
- Validate TalkBack behavior on an emulator or real TV device whenever a usable target is available.

## Modern technology policy

- Prefer current stable, actively maintained Kotlin, AndroidX, Compose, and Media3 APIs.
- Consult current official documentation when API behavior, recommended patterns, or version compatibility matters.
- Prefer modern stable APIs over deprecated, legacy, or compatibility-first implementations.
- Do not use experimental APIs by default. Use one only when it solves a concrete problem and its tradeoff is documented.
- Respect the project's existing version catalog and dependency choices.
- Do not perform unrelated Kotlin, Gradle, Android Gradle Plugin, SDK, or dependency upgrades.
- If a modern implementation requires a significant upgrade or architectural change, explain the benefit and obtain approval first.
- Use structured concurrency, lifecycle-aware state collection, immutable screen state, and unidirectional data flow.
- Use the Gradle wrapper for every build or test command; never rely on globally installed Gradle.

The established stack is Kotlin, Jetpack Compose for TV, AndroidX TV libraries where useful, AndroidX Media3/ExoPlayer, Ktor Client, kotlinx.serialization, coroutines, DataStore, and Coil. Continue using the established library for each responsibility rather than introducing a competing stack.

## Architecture

Use a simple MVVM-style flow:

```text
Compose UI -> ViewModel / screen state -> Repository -> API or local data source
```

- Represent screen state explicitly with immutable Kotlin data classes.
- Model loading, success, empty, and error states rather than relying on nullable data alone.
- Keep screen-specific code inside its feature area and reusable UI in the established shared packages.
- Keep navigation centralized enough that application flow remains understandable.
- Keep API models, request construction, response parsing, and error mapping in the data layer.
- Keep playback implementation separate from ordinary screen UI.
- Keep provider-specific image URL construction and fallbacks outside composables.
- Do not add a use-case/domain layer unless it removes real duplication or clarifies genuinely complex behavior.
- Do not add Hilt, Koin, Room, or another architectural framework without explicit approval.

## Networking and authentication

- Igloo connects to a user-configured Igloo server; never hardcode hosts, ports, LAN addresses, Tailscale addresses, or production domains.
- Preserve valid HTTP and HTTPS server support. Never disable certificate validation or install a trust-all verifier to support self-hosting.
- Validate and normalize the server URL before saving it.
- Keep network work off the main thread and expose suspend functions or `Flow` where appropriate.
- Normalize backend failures into clear app-level errors and provide retry actions for recoverable failures.
- Do not silently discard request failures.
- Authentication is provided only by the Igloo backend. Do not add third-party identity providers.
- Support multiple household profiles; never assume the television has only one user.
- Store credentials using the project's approved secure storage, never ordinary unencrypted preferences.
- Sign-out affects only the selected profile. Clear its in-memory credential and user-scoped caches without removing other profiles.
- Backend permissions are authoritative; never expose admin-only behavior based only on client assumptions.
- Never log passwords, cookies, tokens, pairing codes, QR secrets, device credentials, or sensitive request headers.
- Do not call Plex, Jellyfin, TMDB, Spotify, analytics, or other third-party services directly.
- The existing isolated YouTube trailer WebView is the only approved direct third-party media exception; it must never receive Igloo credentials or cookies.

## Playback

- Media playback is a core product feature, not a generic video-player screen.
- Use AndroidX Media3/ExoPlayer and follow the existing playback architecture.
- Treat direct playback and HLS as first-class playback modes.
- Do not add DASH, VLC, or another player stack unless explicitly requested.
- Never transcode, decode, downmix, or transform media in the TV client; transformation belongs to the backend FFmpeg pipeline.
- Preserve audio passthrough when the device and media support it; never force stereo accidentally.
- Treat TrueHD, DTS-HD, AC3, E-AC3, and Atmos behavior as important and verify it on real hardware when possible.
- Support audio-track and subtitle-track selection.
- Honor explicit user playback settings before automatic choices.
- Keep player lifecycle, audio focus, resource release, and error recovery correct.
- Resume position, watched state, history, and progress come from and return to the backend.
- Report progress every 15 seconds of actual playback, beginning only after the initial 15-second threshold; the first periodic report normally occurs around 30 seconds.
- Also report progress on pause, when the app goes to the background, and when the player closes or the movie ends, exactly as the web client does: those writes need only a position past 30 seconds or at 95% of the runtime, never a played-time floor, and the client must not cancel them. The backend alone decides watched state, at 95%.
- Read the API contract and design system before changing playback APIs or player UX.

## Images

- Use backend-provided image paths or URLs; never embed TMDB or Spotify credentials.
- Keep provider URL resolution centralized and separate from image-loader cache infrastructure.
- Use appropriately sized artwork and normal memory/disk caching; do not load full-resolution images unnecessarily.
- Every artwork surface requires a design-system-appropriate fallback.
- Clear memory and disk image caches when a profile signs out so user-scoped imagery does not remain on a shared TV.
- Do not block the UI while images load.

## Dependencies and security

- Prefer existing dependencies and straightforward Kotlin over adding a library for a small task.
- Ask before adding or replacing dependency injection, storage, networking, playback, code-generation, telemetry, or background-work frameworks.
- Do not add Firebase, analytics, tracking, crash reporting, cloud messaging, CDN assumptions, or third-party media-server SDKs unless explicitly requested.
- Do not add abandoned, deprecated, poorly maintained, or unnecessarily permission-heavy libraries.
- Explain the purpose and impact of every new dependency.
- Keep debug network logging redacted and disabled in release builds.
- Never commit secrets, credentials, private endpoints, or developer-machine details.

## Testing and completion

- Add or update tests for meaningful state, repository, authentication, networking, playback, navigation, focus, and accessibility behavior.
- Cover happy paths and important failure paths where practical.
- Run the smallest relevant tests first, followed by broader validation when warranted.
- Use the actual Gradle tasks configured by the repository. Typical checks include unit tests, debug assembly, lint, and connected Android tests.
- Run connected tests only when a usable emulator or device is available; report when they cannot be run.
- UI, navigation, focus, TalkBack, setup, and playback changes require emulator or real-device validation whenever tools and a target are available.
- Use available ADB tooling to install, launch, navigate, inspect, and exercise the affected behavior directly.
- For playback, passthrough, D-pad, and TalkBack changes, prefer real Nvidia Shield validation over emulator-only validation.
- Do not rely on build success or static review alone for TV UI work.
- Do not add fragile screenshot tests by default.
- Before finishing, report commands run, device or emulator used, behavior verified, validation that could not be performed, and remaining real-device risks.
