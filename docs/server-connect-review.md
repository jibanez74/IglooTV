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

## Status

Findings 2, 3, 4 and the UI/UX finding were fixed on `feature/auth` (see "Resolution"
under each). **Finding 1 remains open** — it is the authentication rework itself and is
input to that task, not a defect to patch beforehand.

## UI/UX finding — RESOLVED

**Error text is hidden behind the on-screen keyboard.** The field error renders below
the address field, which the open IME covers. Submitting via IME Done with an invalid
or unreachable address gives no visible feedback until the keyboard is dismissed.
TalkBack users do get the announcement (the error is an assertive live region);
sighted users see nothing. Suggested direction: close the IME on submit, or ensure the
error is brought into view above the keyboard.

**Resolution:** both. The visible message moved out of `IglooTextField` into a new
`core/ui/IglooInlineError.kt` banner rendered at the top of the auth card, above the
fields — so an open IME cannot cover it. `IglooTextField` keeps `errorText` for the
`error()` semantics and now tints its border destructive when unfocused (focus stays
glacier, per the design system). The IME is dismissed on submit in both auth screens;
in `ServerSetupScreen` the hide runs *after* the error-refocus, because requesting
focus on a `BasicTextField` re-shows the keyboard. `LoginScreen`'s hand-rolled
restore-error block folded into the same banner. `AuthGateTest` now asserts the banner
sits above the input.

## Code review findings

1. **Cookie vs. bearer-token mismatch (key input to the auth work).** — STILL OPEN
   `docs/tv-client-authentication.md` says TV clients should authenticate with device
   bearer tokens via Quick Connect (`POST /api/quick-connect/initiate` + `redeem`) or
   `POST /api/auth/device-login`, not browser session cookies. The current UI does
   cookie-based email/password login (`POST /api/auth/login`). `AuthApi` /
   `AuthRepository` already implement `deviceLogin` and the quick-connect calls (with
   unit tests), but no UI uses them and nothing sends an `Authorization: Bearer`
   header. Note the same doc marks `/api/quick-connect/approve` and `/api/devices*` as
   browser-session-only — the TV-side wrappers for those will 401 under a device token.

2. **Emulator-only placeholder shown on real devices.** — RESOLVED. The address field
   placeholder in `ServerSetupScreen.kt` is `http://10.0.2.2:8080`, which is only
   meaningful on an emulator; on the Shield it is a confusing hint. The error message's
   example (`http://192.168.1.5:8080`) is a better model.

   **Resolution:** placeholder changed to `http://192.168.1.5:8080`, matching the
   validation message. The `10.0.2.2` references in `CLAUDE.md` and `AGENTS.md` are
   correct emulator guidance and were left alone.

3. **Inconsistent timeout error mapping.** — RESOLVED. `safeApiCall` maps
   `HttpRequestTimeoutException` to `AppError.Network`, while `ServerHealthProbe` maps
   it to `AppError.Timeout`, so the same failure is worded differently on the setup
   screen vs. the login screen.

   **Resolution:** `ServerHealthProbe`'s private cause-chain walker moved to
   `core/network/NetworkErrorMapping.kt` as `Throwable.toTransportError()`, now used by
   both. Two user-visible consequences: API-call timeouts read as `Timeout` instead of
   `Network`; and `SSLException` on an API call now maps to `TlsVerification` instead of
   `Network` (it extends `IOException`, so a bad certificate used to advise checking the
   network) — matching what the probe already said for the same handshake failure.
   `AppError.Timeout`'s message no longer claims "within 10 seconds", which was only ever
   the probe client's deadline (the API client uses 30s). New `SafeApiCallTest` covers
   all nine mappings.

4. **Session cookie stored in plain DataStore.** — RESOLVED. AGENTS.md (credential and
   token storage) and `docs/tv-client-authentication.md` (token storage) both ask for
   protected/Keystore-backed storage for auth material. The session cookie currently
   lives in unencrypted DataStore; the same concern applies to the future device
   token.

   **Resolution:** a `SecretCipher` interface with an `AndroidKeystoreCipher`
   implementation — AES-256/GCM through `AndroidKeyStore`, provider-generated 12-byte IV
   prepended to the ciphertext, 128-bit tag, no user-auth or unlocked-device requirement
   (a TV must restore its session on boot with nobody present). No new dependency:
   `androidx.security:security-crypto` 1.1.0 deprecates all of its APIs in favour of
   direct Keystore use. `DataStoreSessionCookieStore` encrypts before writing, never
   falls back to plaintext if encryption fails, and erases anything it cannot decrypt —
   which is also what wipes the plaintext cookie left by earlier builds. The cipher is
   a named property in `IglooAppContainer` so the device bearer token can reuse it.

## Fix verification (2026-08-01)

Nvidia Shield (`SHIELD_Android_TV`, Android 11) over Tailscale adb, against the dev
backend. Note: instrumented tests fail with "No compose hierarchies found" when the
Shield is in daydream mode (`mWakefulness=Dreaming`) — wake it first.

- `./gradlew test` — green, including new `SafeApiCallTest` (9) and
  `DataStoreSessionCookieStoreTest` (6).
- `./gradlew build` — green (lint + tests), no warnings.
- `./gradlew connectedDebugAndroidTest` on the Shield — 10/10, including
  `AndroidKeystoreCipherTest` (5) exercising real TEE-backed keys: round-trip, distinct
  ciphertexts for identical plaintext, tampered blob rejected, legacy plaintext rejected,
  truncated blob rejected.
- Driven D-pad pass: placeholder correct; IME closes on submit and the banner renders
  above the field (still readable when the IME is reopened by the error-refocus); D-pad
  down/up traverses field ↔ Connect with no trap; errored unfocused field shows the
  destructive border while focus stays glacier; blackhole address produces the timeout
  wording, refused port the network wording; wrong credentials produce the banner on the
  login screen.
- Keystore round-trip in the real app: signed in, confirmed the stored value is opaque
  Base64 with no cookie field names (the pre-fix build stored
  `{"name":"session","value":"…"}` in the clear at the same path), then force-stopped and
  relaunched — the session restored straight to the home shell.
- Legacy plaintext erasure: built the pre-fix commit in a scratch worktree, signed in to
  write a plaintext cookie, installed the new build over that data without clearing. The
  app landed on the login screen (server URL retained, no crash) and the session
  datastore was left empty — the old token was actively erased, not just ignored.
- The assertive live region is asserted on-device by `AuthGateTest`; no live TalkBack
  listening session was run.
