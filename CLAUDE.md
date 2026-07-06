# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Igloo TV — the official Android TV client (Kotlin + Jetpack Compose) for the self-hosted Igloo media server. The backend/web client lives in a separate repo, expected as a sibling checkout at `../Igloo`; do not clone or modify it. This app talks only to the Igloo Go backend — it is not a Jellyfin/Plex client and must not depend on them, Firebase, analytics SDKs, or cloud services.

**Read `AGENTS.md` before making changes.** It is the authoritative, detailed rulebook for this repo (dependency policy, accessibility requirements, playback rules, networking constraints, testing expectations). This file only summarizes what you need to get oriented; when in doubt, AGENTS.md wins.

## Commands

Always use the Gradle wrapper:

```bash
./gradlew test                        # JVM unit tests
./gradlew build                       # full build (includes lint + tests)
./gradlew assembleDebug               # debug APK only
./gradlew connectedAndroidTest        # instrumented tests (needs emulator/device)

# Single unit test class:
./gradlew :app:testDebugUnitTest --tests "com.igloo.blindpenguincoder.core.navigation.IglooDestinationTest"

# Single instrumented test class:
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.igloo.blindpenguincoder.IglooBaseAppTest
```

Device targets: Android Studio TV emulator for iteration; Nvidia Shield (via LAN/Tailscale) preferred for real playback, D-pad, and TalkBack validation. On the emulator the dev server is `http://10.0.2.2:8080/api`; on a physical device use a LAN/Tailscale address, never `localhost`.

## Source-of-truth docs

- `docs/openapi.json` — the API contract (~100 paths). Never invent endpoints or models; check here first, then `../Igloo` if it seems outdated.
- `docs/design-system.md` — color tokens, typography, focus states, motion rules, screen/UX specs ported from the Igloo web client. Read before any UI work.
- `docs/ffmpeg.md` — how the backend handles transcoding/HLS/subtitles. The TV app never transcodes; it plays direct streams or backend-produced HLS.

## Architecture

Single `:app` module, package `com.igloo.blindpenguincoder`, organized by feature with shared core packages:

```
core/design/      IglooTheme (colors, spacing, radius, typography via CompositionLocal — not Material theming)
core/navigation/  IglooDestination enum drives the nav shell
feature/home/     IglooApp.kt — the app shell: left navigation spine + content pane
MainActivity.kt   thin entry point that sets IglooApp() as content
```

Intended flow as features land (see AGENTS.md for the full structure): Compose screen → ViewModel → Repository → Ktor API client / DataStore. Manual dependency wiring — no Hilt/Koin/Room without approval.

Stack already wired in Gradle (`gradle/libs.versions.toml`): Compose (BOM) + AndroidX TV foundation/material, Media3/ExoPlayer, Ktor client (OkHttp engine) + kotlinx.serialization, DataStore preferences, Coil 3, Navigation Compose. Do not add competing libraries (e.g. Retrofit alongside Ktor).

## Non-negotiables (condensed from AGENTS.md)

- TV-only: D-pad remote and TalkBack must work on every screen. No touch/hover assumptions. Dark theme is default; focus uses the glacier color consistently.
- Focus behavior is hand-managed where needed (see `FocusRequester` wiring between nav spine and content in `IglooApp.kt`); preserve focus on back navigation, avoid focus traps, use `clearAndSetSemantics` with meaningful labels on actionable cards.
- Support both HTTP and HTTPS servers (`usesCleartextTraffic=true` is intentional). Never hardcode server URLs, IPs, or Tailscale addresses.
- Never log or commit tokens, passwords, QR/quick-connect secrets, or session credentials.
- Playback: Media3/ExoPlayer, direct + HLS, no DASH, no client-side transcoding, preserve audio passthrough. Progress saves to backend every 15s, starting only after ~15s of real playback.
- Pre-production app: no backward-compatibility shims or migrations unless asked.
- Keep comments minimal; prefer clear names. Avoid over-abstraction — no domain/use-case layer unless it removes real duplication.
