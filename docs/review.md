# Code review — bugs, security, and performance

**Date:** 2026-10-27
**Scope:** full read-through of `app/src/main` (Kotlin/Compose Android TV client), the manifest,
build configuration and version catalog. Findings only — no code was changed in the session that
produced this file.
**Deliberately not raised:** cleartext HTTP itself. Connecting to a self-hosted server over plain
HTTP on a LAN is a product decision, not a defect. HTTPS problems are only raised where the app
claims a protection it does not actually provide.

Entries already recorded in [`known-issues.md`](known-issues.md) are not repeated here (notably:
album covers being absolute URLs, unfinished settings/playlists/music progress/background music,
and the show page's non-playing extra videos).

---

## Summary

| # | Area | Severity | One-line |
|---|------|----------|----------|
| 1 | Storage | **High** | Corrupt DataStore can brick launch permanently |
| 2 | Player security | **Medium** | Bearer token follows same-protocol cross-host redirects |
| 3 | Player security | **Medium** | A 401 from a foreign URL can delete the stored profile |
| 4 | Network | **Medium** | Same-origin check is a case/port-sensitive string prefix |
| 5 | WebView | **Low** | `embedOrigin` interpolated into page JS unescaped |
| 6 | Auth | **Low** | 401-driven profile deletion is a LAN DoS surface |
| 7 | Auth | **Info** | PIN has no client throttling — server must rate-limit |
| 8 | Auth | **Medium** | `awaitingUser` survives a server change → bogus "wrong password" |
| 9 | Shows | **Low** | Overlapping mark-watched presses can restore an unconfirmed value |
| 10 | Playback | **Low** | Failed progress sessions accumulate; retry card is global |
| 11 | Auth | **Low** | Early/pending-token 401s are dropped |
| 12 | Misc | **Low** | Four small hardening/consistency items |
| 13 | Performance | **Medium** | ~10 requests on every foreground; no cache/freshness check |
| 14 | Performance | **Low** | Paged lists rebuilt in full on every append |
| 15 | Performance | **Low** | `runBlocking` per player request (every HLS segment) |
| 16 | Performance | **Low** | Sign-out wipes the whole image cache under the session lock |
| 17 | Performance | **Low** | Release build is unminified |

Suggested fix order: **1 → 2 → 8 → 13 → 3 → 4 → 17**, then the rest.

---

## High-impact robustness

### 1. A corrupt DataStore can permanently brick launch

**Files:** `core/storage/IglooDataStores.kt:8-9`, `IglooRoot.kt:74-75,87-89`,
`SessionManager.kt:108`

**Problem.** Both stores are created with `preferencesDataStore("...")` and no
`corruptionHandler`:

```kotlin
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("igloo_settings")
val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore("igloo_session")
```

Nothing in the app applies `.catch { }` to `dataStore.data`, and `restoreOnLaunch()` runs
unguarded inside `LaunchedEffect(Unit)`:

```kotlin
LaunchedEffect(Unit) { sessionManager.restoreOnLaunch() }
```

`restoreLocked()` then calls `settings.serverUrl.first()`. The UI-scale preference
(`IglooRoot.kt:74-75`) is collected on the very first frame with the same exposure.

**Impact.** If `igloo_settings.preferences_pb` or `igloo_session.preferences_pb` is ever
unreadable — a crash mid-write, a storage fault, a bad flash — every read throws
`CorruptionException` forever. The splash either crashes or never leaves `Loading`, and the only
recovery is clearing app data or reinstalling. The encrypted profile vault is separately resilient
(it erases unreadable blobs and falls back to an empty vault), but the Preferences file wrapping it
is not.

**Fix.** Give both stores a `ReplaceFileCorruptionHandler` (and/or delete the file and start
empty), and wrap `restoreLocked()` and the UI-scale read so a storage failure lands on
`NeedsServer` rather than an uncaught coroutine failure.

---

## Security

### 2. The player sends the bearer token across a cross-host redirect

**Files:** `playback/media3/BearerStreamDataSource.kt:35-45,72-82`

**Problem.** `BearerResolver` attaches `Authorization` only when the initial `DataSpec` URI is on
the Igloo origin. The underlying source is Media3's `DefaultHttpDataSource`, which re-applies
`DataSpec.httpRequestHeaders` on **every** redirect hop and does not strip `Authorization` when the
host changes. Verified against the resolved artifact in the Gradle cache
(`androidx.media3:media3-datasource:1.11.0`): `open()` calls `makeConnection(url, ..., dataSpec.httpRequestHeaders)`
inside the redirect loop, and `makeConnection` re-adds every header. `handleRedirect` only rejects
cross-*protocol* redirects (`http`↔`https`), not cross-*host* ones.

**Impact.** A `302` from the Igloo server — or from a same-origin HLS asset URL — to another host
carries the profile token there. This is precisely the case the file's own comment claims to
prevent ("a credential sent to a foreign host is a credential leaked"). It is specific to the
player stack: Ktor/OkHttp and Coil strip `Authorization` on cross-host redirects, so the API and
image paths are not affected.

**Fix.** Wrap the `DataSource` (a wrapper already exists for the 401 bridge) and refuse to follow,
or strip the header on, a redirect that leaves the server origin. Alternatively, check
`dataSpec.httpRequestHeaders` in `open()` and drop `Authorization` when the URI is off-origin.

**How to re-verify:** decompile `media3-datasource-1.11.0` and inspect
`DefaultHttpDataSource.open` / `makeConnection`; or unit-test with a local server that answers 302
to a second local port and assert the second request's headers.

### 3. A 401 from a non-Igloo URL can delete the stored profile

**Files:** `BearerStreamDataSource.kt:31-45,53-63`, `SessionManager.kt:253-268`

**Problem.** `BearerResolver.attachedProfileId` is a sticky `@Volatile` field that is set when a
token is attached but never cleared when one is not. `UnauthorizedReportingDataSource` reports a
401 for *any* URL the player fetches, passing that stale id. `SessionManager.handleSessionRevoked`
then removes that profile from the vault.

**Impact.** If a manifest, redirect, or future feature ever points a player request at a foreign
host that answers 401, the TV signs the user out and forgets the profile. All player URLs are
same-origin today, so this is latent rather than frequent — but it is one server/CDN change away
from a lockout.

**Fix.** Signal only when the failing `DataSpec` actually carried the bearer (check its headers or
re-test the URI against the origin), and reset `attachedProfileId` at the start of every
`resolveDataSpec`.

### 4. `isIglooServerUrl` is a raw string prefix check

**Files:** `core/network/ServerUrlProvider.kt:29-30`, `core/config/ServerUrl.kt:40-46`

```kotlin
internal fun isIglooServerUrl(url: String?, origin: String?): Boolean =
    url != null && origin != null && url.startsWith("$origin/")
```

**Problem.** The check is safe against bypass, but fragile in the false-negative direction:
`origin` lowercases the host and omits default ports (`http://host`, not `http://host:80`). The
backend returns *absolute* avatar URLs, and an HLS manifest may carry absolute asset URLs. Any of
those written with a different host case or an explicit default port fails the check, so the bearer
is not attached and the request 401s.

**Impact.** Avatars and (potentially) stream segments silently fall back to placeholders or fail,
even though they are on the correct server. Not a credential leak — the failure mode is "too
strict", not "too loose".

**Fix.** Parse both URLs and compare scheme, host and effective port, as `ServerHealthProbe`
already does for redirects.

### 5. WebView: `embedOrigin` is interpolated into page JavaScript unescaped

**Files:** `playback/youtube/YouTubeIFrameEngine.kt:92-96,159-217` (line 196)

**Problem.** The video key is correctly validated (`^[A-Za-z0-9_-]{1,64}$`), but the server origin
is inserted verbatim:

```js
playerVars: { ..., origin: '$embedOrigin' }
```

`ServerAddress` never validates host characters, and `'` is a legal reg-name character, so a
server address such as `http://a'b` produces a broken or attacker-influenced script. The value is
user-entered, so impact is low — but the file is otherwise careful about exactly this class of
injection, so the gap is worth closing.

**Related, informational.** `addJavascriptInterface` is injected into every frame, so the YouTube
embed can call the bridge. The bridge only emits playback events (no capability), so this is
acceptable as-is.

**Fix.** Validate the host to a conservative character set in `ServerAddress`, or JSON/JS-escape
the origin before interpolation.

### 6. 401-driven profile deletion is a denial-of-service surface on plain HTTP

**Files:** `SessionManager.kt:253-268`

**Problem.** Any single 401 from any endpoint removes the active profile from the vault. On a LAN
over plain HTTP, any device that can answer for the server's address (ARP spoofing, a captive
portal, a misconfigured proxy) can return 401 to any request and permanently de-register the
profile from the TV.

**Fix.** Consider requiring a second consecutive 401, or publishing the gate with a "this profile
was signed out" notice while keeping the profile stored, so re-pairing is not required.

### 7. PIN has no client-side throttling — confirm the server does

**Files:** `feature/auth/PinEntryViewModel.kt`

**Problem.** The client sends each 4-digit PIN to the server and applies no attempt limit. A
4-digit PIN is 10 000 guesses, and the TV is a convenient oracle. The design correctly treats the
PIN as a UI convenience over an already-stored token (documented in code), so the only real
protection is the server's rate limit.

**Action.** Confirm the backend throttles `verify-pin` (e.g., 429 with `Retry-After`) and that the
client surfaces it. No client change strictly needed.

---

## Correctness / bugs

### 8. `LoginViewModel.awaitingUser` survives a server change

**Files:** `feature/auth/LoginViewModel.kt:36,76,127-129`, `IglooRoot.kt:175-177`

**Problem.** The login view model is Activity-scoped (`viewModel(key = "login")`) and therefore
outlives the sign-in screen. `changeServer()` does not reset `awaitingUser`. Sequence:

1. User submits email/password; `deviceLogin` succeeds and stores a pending token.
2. `completeSignIn()` fails transiently (network), leaving `awaitingUser = true` and the password
   cleared.
3. User taps "Change server", enters server B. `ServerRepository.connect` clears the vault because
   the origin changed.
4. User returns to the login screen and presses Submit. Because `awaitingUser` is still true, the
   app skips `deviceLogin` and calls `completeSignIn()` with no credential, gets 401, and reports
   **"Incorrect email or password."**

Editing either field resets the flag (`updateCredentials`), so the user can recover — but the
error is misleading and no credentials were ever sent.

**Fix.** Reset `awaitingUser` in `changeServer()`, or store the server origin alongside the flag
and require it to match before resuming.

### 9. Overlapping episode "mark watched" presses can restore an unconfirmed value

**Files:** `feature/shows/ShowDetailsViewModel.kt:272-302`

**Problem.** On failure the row is restored from `write.before`. With two quick presses, the second
write's `before` is the first write's *optimistic* value. The serial guard
(`watchedWrites[episodeId]?.serial != write.serial`) stops the wrong write from winning, but not
the wrong baseline from being restored. If both writes fail, the card can end up showing a watched
state the server never confirmed.

**Fix.** Keep the pre-run value (the first write's `before`) as the failure baseline for the run,
not the value captured by the latest press.

### 10. Failed progress sessions accumulate; the retry card is global

**Files:** `feature/player/VideoPlayerViewModel.kt:69,142-166,236-252`,
`feature/home/IglooApp.kt:870-874`

**Problem.** `discardSettledSession` removes a session only when `failedSnapshot == null`, so every
movie/episode whose exit save failed stays in `saveSessions` for the ViewModel's lifetime (the
authenticated session). `retryFailedSave()` retries *all* sessions, and `progressSyncUiState` is a
single global value — a failure recorded on movie A can be shown on movie B's details page (the
host only checks the launch origin).

**Impact.** Small memory growth, and a misleading error message attached to the wrong title.

**Fix.** Bound/clear the map (e.g., drop failures older than N minutes or once the next media
starts), and scope the visible failure to the current media.

### 11. Early and pending-token 401s are dropped

**Files:** `core/network/AuthEvents.kt:15-19`, `SessionManager.kt:66-68`

**Problem.** `AuthEventBus` uses `replay = 0`, so a 401 emitted before the session manager's
collector subscribes is lost (a startup race, or a very early image/stream request). The collector
also ignores `profileId == null`, so a 401 on a *pending* token outside `completeSignIn` never
reaches `forgetExpectedCredentialLocked`.

**Impact.** The session stays "valid" until the next request 401s, which then does the right thing.
Mostly a delay, but it can leave the UI on a screen whose data silently failed.

**Fix.** Use `replay = 1`, or revalidate once after subscription; and handle the null-profile case
by clearing the pending token.

### 12. Minor hardening / consistency

- **`MAX_PROFILES` enforced only in the UI.** `data/repository/ProfileRepository.kt:156` defines
  the cap, but only `feature/auth/ProfilePickerViewModel.kt:54` checks it. `commitSignIn` will
  happily add a seventh profile from any future caller. Enforce in the repository.
- **Password retained after abandoning login.** The Activity-scoped `LoginViewModel` keeps the
  typed password when the user leaves via "Change server". Clear it there (it is already cleared
  once a token is minted).
- **HLS URL built without encoding.** `data/api/VideoPlaybackApi.kt:54-61` joins query values
  manually (`"$name=$value"`), while the Ktor path uses `parameter()` and encodes. All current
  values are numeric/UUID/codec names, but `profileId` originates server-side. Mirror the Ktor
  behaviour (percent-encode) for safety.
- **Shared `NumberFormat` instance.** `core/ui/MediaFormatting.kt:10-13` keeps one
  `NumberFormat.getIntegerInstance()`; `NumberFormat` is documented as not thread-safe. It appears
  to be called only from composables today, but a future background caller would introduce a data
  race. Use `ThreadLocal` or format per call.

---

## Performance

### 13. Every foreground does roughly ten network requests

**Files:** `IglooRoot.kt:324-350`, `feature/home/HomeViewModel.kt:175-185,369-392,394-417`

**Problem.** A single `LifecycleStartEffect` calls `refresh()` on nine view models. On each
foreground:

- Home: 5 rail requests (`continue watching`, latest movies, latest shows, latest albums, in
  theaters) **plus** 2 detail requests for the newest movie and newest show used by the hero.
- Music: a stats request.
- Track likes: its refresh.
- Session: `GET`-style revalidation (throttled to 5 minutes).

There is no HTTP cache, no ETag/`If-Modified-Since`, and no freshness check. A TV that wakes from
standby several times an hour re-downloads the whole home payload each time, plus the two hero
details even when the newest item has not changed.

**Fix.** Add a staleness rule (skip if the last successful refresh is under ~5 minutes old and the
process was not backgrounded for long), reuse the hero's details when the newest id matches, and
consider conditional requests for the library lists. This is the largest avoidable network/CPU cost
in the app.

### 14. Paged lists are rebuilt in full on every append

**Files:** `feature/shared/PagedLoader.kt:180-187`

`present(loaded)` re-maps the entire accumulated list for each page (for tracks this includes
rebuilding every letter header). Over a session this is O(n²) allocations: a 10 000-track library
at 50 per page costs roughly a million item operations plus the associated garbage.

**Fix.** Only relevant if very large music libraries are expected; otherwise leave it. An
incremental `present` (append the new rows plus the new headers) would remove the cost.

### 15. `runBlocking` on every player request

**Files:** `playback/media3/BearerStreamDataSource.kt:38`

Every resolved `DataSpec` — including every HLS segment, i.e. every few seconds — blocks the
loader thread on a coroutine mutex read. It is documented and safe (loader thread, uncontended
mutex), but it is unnecessary: the credential changes rarely and could be published as a
`@Volatile` snapshot, with the provider updating it whenever `BearerTokenProvider.set` runs.

### 16. Sign-out wipes the entire image cache while holding the session lock

**Files:** `SessionManager.kt:356-360`, `core/image/ImageCache.kt:24-29`

`logoutLocked` awaits `imageCache.clear()` — memory plus `diskCache.clear()` — inside
`transitionMutex`. On a TV with a large poster cache this can take seconds, blocks every other
session transition, and forces the next profile to re-download the entire library. The comment
explains why the whole cache is cleared (entries are keyed by URL with no profile association).

**Fix.** Key cache entries per profile (or clear only avatar entries), or move the clear outside
the mutex once local credential removal has completed.

### 17. Release build is unminified

**Files:** `app/build.gradle.kts:24-32`

```kotlin
release {
    isMinifyEnabled = false
    ...
}
```

No R8 optimization and no resource shrinking. For a Compose + Media3 + Coil + Ktor app on
low-end TV hardware this means a larger APK, more DEX to verify at install, and a slower cold
start. ProGuard rules already exist; enabling `isMinifyEnabled = true` and
`isShrinkResources = true` for release is usually a measurable win. Keep `isMinifyEnabled = false`
for debug if desired.

---

## Checked and found sound

Recorded so a later session does not re-investigate these:

- **Secret handling:** tokens live in one AES-GCM Keystore-encrypted blob, never logged, never
  displayed; `allowBackup="false"`; no plaintext fallback when encryption fails (the vault is
  dropped instead).
- **Ktor client:** `expectSuccess = false`, bearer plugin attaches only to opted-in requests, 401
  bridge suppresses "expected" 401s at the source, cancellation rethrown, explicit connect/request/
  socket timeouts.
- **Server probe:** manual redirect handling blocks host changes and https→http downgrade; no
  credential attached.
- **Auth state machine:** revocation is scoped to the credential actually attached (a slow 401
  from a previous profile cannot remove the current one), transitions are mutexed, detached
  logout cannot be cancelled mid-persist, local cleanup happens before the network revoke.
- **Player lifecycle:** every engine cancels its own scope and releases players/sessions;
  `PlaybackIntent` separates host pause from user pause; exit progress saves use `NonCancellable`;
  HLS sessions are stopped on release.
- **Focus/empty-state safety:** the `.first()` anchors in `PaneGrid`, `IglooMediaRail`,
  `SelectableChipRow`, `TracksList` and the search sections are all guarded by
  `Populated`/`shownSections` filters — I specifically looked for reachable
  `IndexOutOfBoundsException`s and found none.
- **No** `!!`, no `TODO`/`FIXME`, no `GlobalScope`, no `Thread.sleep`, no insecure
  `TrustManager`/`HostnameVerifier`, no WebView debugging enabled.

---

## Not re-raised (known issues)

- Album covers arriving as absolute URLs and not being "resolved".
- Settings destination, playlists, music play-stat reporting, background music playback, and the
  show page's extra videos being unfinished.
- Subtitle behaviour documented in [`subtitles-bug.md`](subtitles-bug.md).
