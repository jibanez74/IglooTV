# Server-connect feature: review & on-device verification (2026-08-01)

Findings from a code review and real-device verification of the server setup feature
(enter server URL → health probe → login screen), done on the `feature/auth` branch
before starting TV authentication work.

**How it was verified:** debug build installed on a physical Nvidia Shield (adb over
Tailscale) against the Igloo dev backend running on the dev machine, reached via the
dev machine's Tailscale address on port 8080. App data was cleared first so the app
started from the server setup screen. Unit tests (`./gradlew test`) were green before
the device run.

## Verification results — all passed

| Step | Result |
|---|---|
| Fresh launch | Server setup screen shown, no nav chrome, address field focused (IME opens) |
| Empty submit | Validation error "Enter your server address, like http://192.168.1.5:8080." shown; focus stays on the field |
| Unreachable server (valid format, dead port) | "Couldn't reach the server. Check the address, port, and network connection." shown; retry possible |
| Happy path (real backend address) | `GET /api/health` succeeded; app transitioned to the login screen with the server origin in the subtitle |
| Force-stop + relaunch | App skipped server setup and restored straight to the login screen with the saved server |

This covers the gap left by the test suite: unit tests cover URL parsing, probe
behavior, ViewModels, and `SessionManager`, but no instrumented test performs a real
connect round-trip.

## UI/UX finding

**Error text is hidden behind the on-screen keyboard.** The field error renders below
the address field, which the open IME covers. Submitting via IME Done with an invalid
or unreachable address gives no visible feedback until the keyboard is dismissed.
TalkBack users do get the announcement (the error is an assertive live region);
sighted users see nothing. Suggested direction: close the IME on submit, or ensure the
error is brought into view above the keyboard.

## Code review findings

1. **Cookie vs. bearer-token mismatch (key input to the auth work).**
   `docs/tv-client-authentication.md` says TV clients should authenticate with device
   bearer tokens via Quick Connect (`POST /api/quick-connect/initiate` + `redeem`) or
   `POST /api/auth/device-login`, not browser session cookies. The current UI does
   cookie-based email/password login (`POST /api/auth/login`). `AuthApi` /
   `AuthRepository` already implement `deviceLogin` and the quick-connect calls (with
   unit tests), but no UI uses them and nothing sends an `Authorization: Bearer`
   header. Note the same doc marks `/api/quick-connect/approve` and `/api/devices*` as
   browser-session-only — the TV-side wrappers for those will 401 under a device token.

2. **Emulator-only placeholder shown on real devices.** The address field placeholder
   in `ServerSetupScreen.kt` is `http://10.0.2.2:8080`, which is only meaningful on an
   emulator; on the Shield it is a confusing hint. The error message's example
   (`http://192.168.1.5:8080`) is a better model.

3. **Inconsistent timeout error mapping.** `safeApiCall` maps
   `HttpRequestTimeoutException` to `AppError.Network`, while `ServerHealthProbe` maps
   it to `AppError.Timeout`, so the same failure is worded differently on the setup
   screen vs. the login screen.

4. **Session cookie stored in plain DataStore.** AGENTS.md (credential and token
   storage) and `docs/tv-client-authentication.md` (token storage) both ask for
   protected/Keystore-backed storage for auth material. The session cookie currently
   lives in unencrypted DataStore; the same concern applies to the future device
   token.
