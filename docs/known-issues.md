# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

Smaller things — duplication, organisation, coverage gaps and polish — live in
[`cleanup-backlog.md`](cleanup-backlog.md).

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
`AuthUser.avatar` decode bug (fixed 2026-08-14) was a sixth that a presence-only sweep still
misses, because the field was present and only its *type* was wrong.

A second full sweep on 2026-08-14 found a seventh, `PlaylistCollaboratorMutationData` (fixed the
same day), which misses in a third direction again: the field was present *and* correctly named,
and only the schema it referenced was wrong. `PlaylistCollaboratorMutationEnvelope.data
.collaborator` refs `PlaylistCollaboratorMutation` — the row without the user join, so no
`username` and no `email` — while the model reused the list-shaped `PlaylistCollaborator`, whose
copies of both are non-nullable. Worth noting because it is the first instance that *cannot*
decode at all rather than being absorbed by `ignoreUnknownKeys`.

Most of these models have no call sites, so drift stays invisible until someone wires up the
endpoint and it fails at runtime. Of 143 declared types, **29** are referenced in production, 17
only by the serialization test, and **97 nowhere at all** — so roughly two thirds of the wire
surface is unexercised. The doc used to name `MoviePlaylist*`, `Notification*` and the settings
models; the dead set also covers every admin-user, watch-room, search, Spotify/TMDB and
user-stats model.

### What the fix looks like

Either regenerate the models from the spec as part of each sync, or add a test that walks
`components.schemas` and asserts each model's required fields match — presence *and* type. The
serialization tests pin the contract shape for the models touched so far, but only those, and
only by hand-written fixture.

Three things a check has to handle before it is worth having, all learned from doing the sweep
by hand:

- **`docs/openapi.json` is not on the JVM test classpath.** `app/build.gradle.kts` has no
  `sourceSets` or `testOptions` block and `app/src/test/` has no `resources/` directory, so the
  check needs a `sourceSets["test"].resources.srcDir(...)` entry pointing at `docs/` (or a copy
  task). Reading it via a relative `File(...)` path would depend on Gradle's working directory.
- **`allOf` has to be flattened.** Every `*Envelope`, plus `MoviePlaylistSummary`,
  `MovieLibraryItem`, `ContinueWatchingMovie` and `AdminUser`, composes with `allOf` and has an
  empty top-level `properties`. A naive walk reports every one of their fields as missing.
- **OpenAPI 3.1 type unions have to be understood.** `"type": ["string", "null"]` is exactly the
  shape the `AuthUser.avatar` bug hid in, so a checker that reads `type` as a string misses the
  whole class of bug it exists to catch.
- **The spec itself can be the wrong side of the drift.** An eighth instance, found 2026-08-15
  wiring the movie detail screen: `Chapter.movie_id` is declared `SqlNullInt64` and the server
  sends a plain number. So a checker that trusts `openapi.json` as truth would have called the
  correct-looking model correct, and a spec-shaped fixture did — the hand-written serialization
  test passed against the object shape while the real payload could not decode at all. Whatever
  the check ends up being, it has to run against a live response, not only the document.

### Related, smaller

- `MoviePlaylistSummary` duplicates **ten** fields from `MoviePlaylist` instead of composing it,
  which is why both copies carried the stale `folder_id` and drifted identically. The same
  flatten-instead-of-compose shape appears in three more models the spec composes with `allOf`:
  `MovieLibraryItem` and `ContinueWatchingMovie` both restate `LatestMovie`, and `AdminUser`
  restates `AuthUser` where the schema is literally `allOf: [$ref AuthUser]`. Worth collapsing —
  and note this is the *cause* of two of the three omissions below, not a separate problem.
- `TheaterMovie` (7 fields), `AdminUser` (`has_pin`), and `DeviceTokenData` (`device`) each omit
  response fields their schema marks required. Harmless under `ignoreUnknownKeys = true` and left
  alone deliberately — recorded so the next sweep does not re-flag them as new. Confirmed still
  the case on 2026-08-14, with nothing new in that category.
- `UpdatePlaybackSettingsData.settings` is typed `UpdatedPlaybackSettings` where the schema says
  `UpdatePlaybackSettings`. The fields match exactly; only the Kotlin class name differs. Not a
  bug, but it will trip any check that matches models to schemas by name.
- The five `MovieDetailsData` lists (`cast`, `crew`, `genres`, `production_companies`,
  `extra_videos`) are `additionalProperties: true` in the spec — genuinely untyped, not drifted.
  They were typed on 2026-08-15 against live responses instead; a spec-driven check has nothing
  to compare them to and should skip rather than flag them.

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
was fixed on 2026-08-14 by `avatarImageUrl` and does not apply here.
