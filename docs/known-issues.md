# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

---

## `AuthUser.avatar` decodes the wrong wire shape and breaks sign-in for users with an avatar

**Found:** 2026-08-13, while sweeping the wire models against `docs/openapi.json`.
**Status:** open. Live on every signed-in path, not latent.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/data/model/Auth.kt`

### The gap

`AuthUser` models `avatar` as a Go `sql.NullString` object, and says so in a comment that names
the backend function as its evidence:

```kotlin
// Auth.kt
// The backend serializes this as a Go sql.NullString object, not a plain
// string (docs/openapi.json is outdated here; see userResponseMap in ../Igloo).
val avatar: SqlNullString? = null,
```

That comment is now the stale part. `userResponseMap` — the function it cites — unwraps the
value before writing it (`../Igloo/server/cmd/api/user_handler.go:22-38`):

```go
var avatarValue any
if avatar.Valid {
	avatarValue = avatar.String
}
// ...
"avatar": avatarValue,
```

So the wire value is a **plain string or `null`**, never an object. `docs/openapi.json` agrees —
`AuthUser.avatar` is `{"type": ["string", "null"]}` — and so does the other endpoint that returns
users, whose `adminUserSummary.Avatar` is a `*string` (`admin_user_handler.go:15-23`). No endpoint
emits the object form. The backend commit that made this change is `1760fdbb`.

### Why it breaks

`explicitNulls = false` covers the `null` case, so a user **without** an avatar decodes fine —
which is why this has not been noticed. A user **with** one sends:

```json
"avatar": "/api/static/avatars/7-1735689600.jpg"
```

kotlinx then tries to read a JSON string as an object and throws `SerializationException`
("Expected start of the object '{'"). `AuthUser` is decoded by `AuthRepository.fetchCurrentUser`
and flows into `ProfileRepository.commitSignIn`, `AppAuthState.Authenticated`, the navigation
rail, and the app shell — so this is sign-in failing outright, not a cosmetic gap.

Note the sibling model already has it right: `AdminUser.avatar` is `String?` (`Users.kt:16`).

### What the fix looks like

1. Change `AuthUser.avatar` to `String?` and delete the comment.
2. Drop the now-dead `SqlNullString` unwrapping at `ProfileRepository.kt:83`
   (`user.avatar?.orNull()` becomes `user.avatar`).
3. Update `ApiModelsSerializationTest.decodesAuthUserEnvelope`, whose fixture currently pins the
   wrong shape (`"avatar": {"String": "", "Valid": false}`) and so cannot catch this.
4. Fix the avatar rendering issue below at the same time — together they are what makes an
   uploaded avatar actually appear on TV.

---

## `IglooAvatar` drops backend-relative avatar paths, so uploaded avatars never render

**Found:** 2026-08-13, while investigating the album-artwork review comment.
**Status:** open. Every uploaded avatar silently falls back to initials.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/core/ui/IglooAvatar.kt`,
`docs/design-system.md:938-939`

### The gap

`IglooAvatar` fetches a remote image only when the stored value is absolute:

```kotlin
val url = avatarUrl?.takeIf {
    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
}
```

Both the code comment and `design-system.md:938-939` justify this the same way — *"`openapi.json`
does not define how a relative avatar path resolves"*. That premise is false. Avatar upload writes
a relative path and persists it as the user's avatar (`../Igloo/server/cmd/api/user_handler.go:383`):

```go
avatarURL := fmt.Sprintf("/api/static/avatars/%s", filename)
```

`/api/static/{path}` is a documented route. So every avatar the server actually produces is
exactly the shape this guard rejects, and the user sees initials instead.

This is the genuine relative-path bug that the home-screen review's album-artwork comment was
reaching for — it is on avatars, not album covers (see the rejected entry below).

### What the fix looks like

Resolve `/api`-relative values against the configured server origin and keep passing absolute URLs
through, mirroring `getMediaImageUrl()` in the web client — but note the web client is same-origin,
so its `/api` branch returns the path unchanged and the TV app must actually prepend the origin.
`isIglooImageUrl` (`core/image/IglooImageLoader.kt:41`) then attaches the bearer token, which is
required: `/api/static` is authenticated. Update the design-system rule at :938-939 in the same
change, since it currently mandates the broken behaviour.

Blocked in practice by the `AuthUser.avatar` decode bug above — fix that first or avatars never
arrive to be rendered.

---

## Wire models drift from `docs/openapi.json` with nothing to catch it

**Found:** 2026-08-13, resolving the home-screen review's two contract comments.
**Status:** open. The known instances are fixed; the mechanism that produced them is not.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/data/model/`

### The gap

The models were generated once (`05ec605`, "Add API models generated from docs/openapi.json").
`docs/openapi.json` has since been re-synced **six times** — `9852401`, `047499e`, `de5d43a`,
`647001a`, `fa906c2`, `5e3a176` — with no regeneration and no check. A reviewer spotting two
dropped fields by eye is what surfaced this; a full manual sweep then found three more, and the
`AuthUser.avatar` bug above is a sixth that a presence-only sweep still misses because the field
is present and only its *type* is wrong.

Most of these models have no call sites at all, so the drift stays invisible until someone wires
up the endpoint and it fails at runtime. `MoviePlaylist*`, `Notification*`, and the settings models
are all currently unreferenced outside their own files.

### What the fix looks like

Either regenerate the models from the spec as part of each sync, or add a test that walks
`components.schemas` and asserts each model's required fields match — presence *and* type. The
serialization tests added alongside this entry pin the contract shape for the models touched so
far, but only those, and only by hand-written fixture.

### Related, smaller

- `MoviePlaylistSummary` duplicates nine fields from `MoviePlaylist` instead of composing it,
  which is why both copies carried the stale `folder_id` and drifted identically. Worth collapsing.
- `TheaterMovie`, `AdminUser` (`has_pin`), and `DeviceTokenData` (`device`) each omit response
  fields their schema marks required. Harmless under `ignoreUnknownKeys = true` and left alone
  deliberately — recorded so the next sweep does not re-flag them as new.

---

## Watch progress: the player still has to own the save session identity

**Found:** 2026-08-10, seeding watch progress by hand to verify the Home rails.
**Status:** partly landed. `UpdateMovieWatchProgressRequest` now carries all four fields
(2026-08-13); the caller-side rules below are still unimplemented because nothing sends it yet.
**Files:** the future playback/progress caller

### What remains

The model is correct now, but the two fields it gained only work if the caller manages them.
From `../Igloo/server/cmd/api/watch_progress_handler.go` and the `UpsertMovieWatchProgress` query:

| Field | Type | Rule |
| --- | --- | --- |
| `save_session_id` | UUID string | Identifies one continuous playback session. Validated as a real UUID (36 chars, correct dashes, hex). |
| `save_sequence` | int64 | Monotonically increasing counter **within** a session. Must be `> 0`. |

The upsert applies a write only when:

```sql
WHERE movie_watch_progress.save_session_id <> excluded.save_session_id
   OR movie_watch_progress.save_sequence   <  excluded.save_sequence
```

A different session always wins; within the same session only a strictly higher sequence wins.
A late-arriving save from earlier in the same session is **silently dropped** — no error, so the
client cannot detect it and must not treat a 200 as "my value is now stored".

So the player must mint one UUID per playback session and increment a counter on each save, and
the sequence must keep increasing across retries of the *same* save, or a retried write can be
dropped by the `<` comparison. AGENTS.md sets the cadence — save every 15 seconds, first save only
after ~15s of real playback, so in practice around 30s.

Other server-side behaviour worth knowing before wiring this up:

- `progress_sec` is clamped server-side to `[0, duration_sec]`.
- `duration_sec` must be `> 0`; both values must be finite (NaN/Inf are rejected).
- At `progress_sec / duration_sec >= 0.98` the server marks the movie **watched** instead of
  storing progress, and responds `{"watched": true}`. The app's `MovieWatchProgressUpdateData`
  already models that response correctly.

---

## Rejected: album cover URLs are absolute and must not be "resolved"

**Found:** 2026-08-13, raised as a home-screen review comment and investigated.
**Status:** closed, no change. Recorded so it is not raised again.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/feature/home/HomeViewModel.kt:194`

A review comment argued that `HomeViewModel` passes album covers to Coil without resolving
backend-relative paths such as `/api/static/albums/...`, citing the web client's
`media-image-url.ts` as evidence that shape is supported. It is not:

- `albums.cover` has exactly two writers, `../Igloo/server/cmd/api/music_scanner.go:1029` and
  `:1106`, and both store the raw Spotify CDN URL. Nothing downloads album art into
  `static/albums/`, and that directory is empty on disk.
- The live database holds 211 albums: 190 `https://i.scdn.co/...`, 21 empty string, **zero**
  relative paths. `music_scanner_test.go:397` asserts the absolute-URL shape.
- There is no music image proxy route in `docs/openapi.json`, so there is no base URL to resolve
  against — unlike TMDB, which has `GET /api/tmdb/images/{size}/{file}`.
- `AGENTS.md:528` says to use a full Spotify image URL as provided; the shared-resolver mandate at
  `AGENTS.md:526` is scoped to TMDB path fields, which is why the movie rails call `tmdbImageUrl()`
  and the album rail does not.

The cited `media-image-url.ts` `/api` branch returns the path **unchanged** — the web client is a
same-origin SPA served by the Go binary, so the browser resolves it. It never builds an absolute
URL and says nothing about what the field contains.

If the scanner ever starts storing artwork locally this becomes real, but `IglooPosterCard` already
degrades to the music placeholder on a load error rather than showing a broken card. The genuine
relative-path bug is on avatars — see the `IglooAvatar` entry above.
