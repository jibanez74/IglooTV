# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

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
`AuthUser.avatar` decode bug (fixed 2026-08-13) was a sixth that a presence-only sweep still
misses, because the field was present and only its *type* was wrong. Any check worth adding has
to compare types, not just field names.

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
relative-path bug was on avatars, where uploads really are stored as `/api/static/...`; that one
was fixed on 2026-08-13 by `avatarImageUrl` and does not apply here.
