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

All findings are now resolved on `feature/auth` (see "Resolution" under each). Findings
2, 3, 4 and the UI/UX finding were fixed first; finding 1 was the authentication rework
itself and was closed by commit `e9d6c30`.

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

1. **Cookie vs. bearer-token mismatch (key input to the auth work).** — RESOLVED
   `docs/tv-client-authentication.md` says TV clients should authenticate with device
   bearer tokens via Quick Connect (`POST /api/quick-connect/initiate` + `redeem`) or
   `POST /api/auth/device-login`, not browser session cookies. The current UI does
   cookie-based email/password login (`POST /api/auth/login`). `AuthApi` /
   `AuthRepository` already implement `deviceLogin` and the quick-connect calls (with
   unit tests), but no UI uses them and nothing sends an `Authorization: Bearer`
   header. Note the same doc marks `/api/quick-connect/approve` and `/api/devices*` as
   browser-session-only — the TV-side wrappers for those will 401 under a device token.

   **Resolution:** commit `e9d6c30`. Cookie storage (`PersistentCookiesStorage`,
   `SessionCookieStore`) was deleted in favour of `core/network/BearerTokenProvider` and
   a Keystore-encrypted `core/storage/DeviceTokenStore`. A `DeviceTokenAuth` Ktor plugin
   attaches `Authorization: Bearer …` to every request except the three credential-issuing
   paths (`/auth/device-login`, `/quick-connect/initiate`, `/quick-connect/redeem`), which
   must never carry a stale token. `QuickConnectScreen` is now the default sign-in mode
   with `LoginScreen` (device-login) behind a toggle. The browser-session-only routes were
   deliberately never implemented on the TV side.

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

## Authentication review & Shield verification (2026-08-02)

Review of both sign-in paths after the bearer-token rework (`e9d6c30`), then an
end-to-end quick-connect run on the physical Shield.

The structure held up: the `DeviceTokenAuth` plugin withholds the token from the three
credential-issuing paths, `AndroidKeystoreCipher` never falls back to plaintext, and
`safeApiCall` rethrows `CancellationException`. The findings were resilience gaps — paths
that retried forever or minted redundant devices rather than telling the user anything.

### Fixed

1. **Post-approval dead-air.** `QuickConnectViewModel.finishApproved()` retried the user
   fetch every 2s forever while the UI still showed the (already consumed) pairing code.
   Added a `SigningIn` phase and capped the retries at six, after which a retryable error
   is shown.
2. **Unbounded initiate retries.** 429/503 retried indefinitely behind an unchanging
   `· · · · · ·` placeholder. Capped at five attempts (~65s of backoff), then a visible
   failure.
3. **Password login re-minted a device token per retry.** If device-login succeeded but
   the user fetch failed, resubmitting called `/auth/device-login` again, creating another
   server-side device. `LoginUiState.awaitingUser` now resumes the stored token instead;
   editing either credential clears it.
4. **Redundant pairing after a transient restore failure.** A server unreachable at launch
   sent an already-tokened device into a fresh pairing loop. `QuickConnectViewModel.start()`
   now routes on `authRepository.hasToken()` — a stored token finishes sign-in, no token
   pairs. This also covers resuming after a lifecycle stop and after a failed sign-in, so
   no `Recovery` hint or `autoStartPairing` plumbing was needed.

DRY/dead code: the duplicated "fetch user → authenticate / clear on 401" logic in both
ViewModels moved to `SessionManager.completeSignIn(): SignInResult`; `onLoggedIn()` was
absorbed and deleted. The unused `Device` model and the two `device` fields referencing it
were removed (`ignoreUnknownKeys` still tolerates them on the wire). `docs/ffmpeg.md`,
deleted in `647001a` while `CLAUDE.md` still cites it, was restored.

Left alone, with reasons: `PUBLIC_AUTH_PATHS` uses `endsWith` (correct for the current API
surface); the poll countdown ignores request latency (the server's 404 is authoritative);
`DataStoreDeviceTokenStore.write` silently drops a token it cannot encrypt (deliberate, and
there is no useful UI response). `UpdateUser*Request` in `data/model/Auth.kt` is
unreferenced but predates this work and belongs to the unbuilt profile screens.

### Shield verification (`SHIELD Android TV`, Android 11, adb over Tailscale)

`./gradlew test` 104/104 green; `./gradlew build` green.

| Step | Result |
|---|---|
| Fresh install → launch | Server setup screen, address field focused, IME open |
| Typed backend address → Done | Health probe passed; sign-in screen with the origin as subtitle |
| Quick Connect default | Pairing code rendered; focus on "Use email & password instead" |
| Approved via `POST /api/quick-connect/approve` (admin cookie session) | Shield left the code screen and landed on the home shell with the user in the spine footer |
| Force-stop + relaunch | Straight to the home shell — no pairing |
| Token at rest | `igloo_session.preferences_pb` holds opaque ciphertext; zero `igd_` occurrences. Server URL stays plaintext in `igloo_settings` by design |
| D-pad | LEFT into spine, DOWN to Sign out, CENTER — glacier focus ring visible throughout, no trap |
| Sign out | Returned to the sign-in screen with a *new* pairing code, confirming the token was cleared |
| Password path | Correct credentials reached the home shell; wrong password showed "Incorrect email or password." with the destructive field border |
| Device hygiene | `GET /api/devices` showed exactly one device per pairing — `SHIELD / android_tv / 0.1.0`. Each sign-out revoked its device; no duplicates accumulated across four sign-in cycles |

Not verified on-device: the `SigningIn` state is only visible for ~100ms against a LAN
backend, so it was covered by unit tests rather than a screenshot. No live TalkBack
listening session was run — the semantics are asserted in tests.

**Pre-existing instrumented-test failures (not caused by this work).** On the Shield,
`./gradlew connectedDebugAndroidTest` fails 3/12 — `AuthGateTest.dpadMovesFromAddressTo`
`ConnectAndValidationReturnsFocus` plus both `QuickConnectGateTest` cases — all
`assertIsFocused` assertions reporting `Focused = 'false'`. Confirmed by stashing all
changes and re-running: the same three fail on unmodified `e9d6c30`. The device was awake
and no other app held window focus. D-pad focus works correctly when driving the app by
hand, so this looks like a test-harness window-focus issue on this device rather than an
app defect. Worth a separate look; the remaining 9, including `AndroidKeystoreCipherTest`
against real TEE keys, pass.
