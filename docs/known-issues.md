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
`docs/openapi.json` has since been re-synced **seven times** — `9852401`, `047499e`, `de5d43a`,
`647001a`, `fa906c2`, `5e3a176`, `b8dc4c2` — with no regeneration and no check. A reviewer spotting two
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

A model with no call site is drift waiting to happen: nothing exercises it until a screen wires
the endpoint and it fails at runtime. Until 2026-09-20 roughly two thirds of the wire surface
sat in that state, kept in sync by hand. **Since 2026-09-21 the rule is the other way round: a
model exists only once production calls its route.** The Music review pass deleted every
uncalled model in the files it had touched — the playlist, search, user-stats, settings,
notification and admin-user files whole, plus `Track`, `TrackDetailsData`, `LikedTracksData`,
`ClearedData`, `IdentifyMovieRequest`, `UpdateMovieMetadataRequest` and `DeleteMovieRequest` —
and their serialization cases with them. Files that pass did not touch (`WatchRooms.kt`,
`Devices.kt`, `Metadata.kt`, `Profile.kt`, `UserPin.kt`) still hold uncalled models and fall
under the same rule the next time they are opened.

The consequence is that most spec schemas have **no model at all** — every `Show*` schema and
`/api/shows/*` route except the five the TV Shows index calls (`ShowLibraryItem`,
`ShowsLibraryData`, `ShowGenreWithCount`, `ShowGenresData`, `ShowsStatsData` in `Shows.kt`,
added 2026-09-26), the playlist, search, stats, settings, notification and admin routes, the
scan-status routes, the devices list, the profile updates, `LoginRequest`,
`WatchRoomClientEvent` — and that is expected. `ContinueWatchingEpisodeItem`'s episode keys
(`show_id`, `season_number`, `episode_number`, `episode_name`) are the one partial case: the
Home rail drops `kind: episode` entries until an episode has a screen to open.

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
- **`allOf` has to be flattened.** Every `*Envelope` and `AdminUser` compose with `allOf` and
  have an empty top-level `properties`; `ContinueWatchingItem` is a discriminated `oneOf`. A
  naive walk reports every one of their fields as missing.
- **OpenAPI 3.1 type unions have to be understood.** `"type": ["string", "null"]` is exactly the
  shape the `AuthUser.avatar` bug hid in, so a checker that reads `type` as a string misses the
  whole class of bug it exists to catch.
- **The spec itself can be the wrong side of the drift.** An eighth instance, found 2026-08-15
  wiring the movie detail screen: `Chapter.movie_id` is declared `SqlNullInt64` and the server
  sends a plain number. So a checker that trusts `openapi.json` as truth would have called the
  correct-looking model correct, and a spec-shaped fixture did — the hand-written serialization
  test passed against the object shape while the real payload could not decode at all. Whatever
  the check ends up being, it has to run against a live response, not only the document.
- **A sync can break a model that used to decode.** Instances nine and ten, found 2026-09-19
  wiring the Music screen, both came from the `b8dc4c2` sync: `TrackListItem` had lost
  `file_path` and gained five nullable album/musician columns while `duration` became an
  integer of milliseconds, and `SimpleMusician` had lost `sort_name`. Both Kotlin models kept
  the removed field as a required non-nullable property, so `ignoreUnknownKeys` could not save
  them — every list under `/music/tracks` and `/music/musicians` failed to decode outright.
  Confirmed against the server's own row structs (`GetTracksAlphabeticalRow`,
  `GetMusiciansAlphabeticalRow` in the main repository) rather than a populated live response,
  because the local dev library is empty and the tailnet server needs its own credentials; the
  same sync also typed `MusicianDetailsData`'s three `JsonObject` fields, now modelled.
- **The same sync broke the two movie screens a day later.** Instances eleven to fourteen,
  found 2026-09-19/20 on Home and movie details, all from `b8dc4c2` as well: `Movie` lost its
  seven file columns (`file_path`, `file_name`, `size`, `container`, `mime_type`, `created_at`,
  `updated_at`), so `GET /movies/details/{id}` failed to decode and playback lost its mime type
  (now read from the typed `MovieTechnicalFile` on the technical-details route);
  `VideoStream`/`AudioStream`/`Subtitle` lost `created_at`/`updated_at`;
  `GET /movies/continue-watching` moved to `GET /continue-watching` with a `kind`-discriminated
  movie/episode item under `data.items`; and the uncalled `PlaybackSettings` kept a required
  `is_admin` the schema dropped. The five `MovieDetailsData` lists are typed in the spec since
  this sync too. Every model in `data/model/` was re-checked against the spec on 2026-09-20; the
  uncalled ones were deleted the next day (above), and the ones below are the only deliberate
  gaps among those that remain.

### Related, smaller

- `TheaterMovie` (7 fields), `TmdbMovie` and its nested genre/company/crew/video items,
  `MovieTechnicalFile` (5 fields), `MovieLibraryItem` and `ShowLibraryItem` (`certification`),
  `MoviesLibraryData` and `ShowsLibraryData` (`page`, `per_page`, `sort`), `AlbumTrack`
  (`mime_type`), `TrackListItem` (`codec`, `bit_rate`), `Musician` (`sort_name`, `spotify_id`,
  `created_at`, `updated_at`), `MusicianAlbum` (`release_date`, `track_count`), `MusicianTrack`
  (`codec`, `bit_rate`, `album_cover`), `DeviceTokenData` and `QuickConnectRedeemData`
  (`device`) each omit response fields their schema marks required. Harmless under
  `ignoreUnknownKeys = true` and left alone deliberately — recorded so the next sweep does not
  re-flag them as new. Confirmed still the case on 2026-09-21.
- Some Kotlin class names lag the spec's: `MovieWatchProgress`/`UpdateMovieWatchProgressRequest`/
  `SetMovieWatchedRequest` for `WatchProgress`/`UpdateWatchProgressRequest`/`SetWatchedRequest`,
  `MovieCastMember`/`MovieCrewMember`/`MovieExtraVideo` for `MovieCastCredit`/`MovieCrewCredit`/
  `ExtraVideo`, `TrackGenre` for `AlbumTrackGenre`. The fields match exactly; only the names
  differ. Not a bug, but it will trip any check that matches models to schemas by name.

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
