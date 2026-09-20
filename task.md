# Task — `feature/music-screen`

Working notes for the current branch: what was last finished, what this branch is for, and what
has to be true before it merges into `dev`.

**Last updated:** 2026-09-15
**Branch:** `feature/music-screen`
**Base:** `dev` (both at `6d8cc40`)

---

## Where the branch stands right now

`feature/music-screen` is **at `dev`'s tip with zero commits of its own** — `git log
dev..feature/music-screen` is empty in both directions. The branch exists; the work has not
started.

The working tree holds two untracked documents and no code changes: `README.md`, written
2026-09-15, and this file. Verified when the README was written: `:app:testDebugUnitTest`,
`:app:lintDebug` and `:app:assembleDebug` all pass; `:app:connectedDebugAndroidTest` was **not**
run, because no device was attached.

```text
?? README.md
?? task.md
```

So there is nothing to merge yet. Everything below §"The work" is the plan for this branch.

## What was last worked on

**The Movies tab strip and its review pass** (2026-09-02, commits `4e29916` → `6d8cc40`,
five commits, all already on `dev`). The Movies filter-chip row became an `All Movies · Genres ·
Liked` tab strip matching the web page's shape: tabs switch on focus with a 300 ms debounce so
sliding past a tab fires no throwaway request, a press selects immediately (which is how TalkBack
activates it and how you retry a failed switch), the Genres tab carries a chip picker with count
labels and remembers the last genre, and `IglooFilterChip`/`IglooTab` were folded onto one shared
`SelectablePill`. 65 JVM tests on the view model and 87 instrumented tests on the Shield, plus an
end-to-end live-server pass with screenshots at every step. Written up in
[`docs/movies-screen-status.md`](docs/movies-screen-status.md).

Immediately before that, on the music side: the **album details screen** (2026-08-31,
[`docs/album-details-status.md`](docs/album-details-status.md)) and the **Play Album music
player** (2026-09-01, [`docs/music-player-status.md`](docs/music-player-status.md)).

Most recently (uncommitted): the project `README.md`.

## What this branch is for

The **Music destination screen** — the last item all three music-side status docs name as open,
in the same words: *"the spine's Music tab is still a placeholder; the Home albums rail remains
the only way into an album."*

Target shape, from [`docs/design-system.md`](docs/design-system.md) §11.5 — **four tabs**:

| Tab | Content | Notes |
| --- | --- | --- |
| **Musicians** | Circular cards | Musician detail follows the backdrop + hero + list pattern |
| **Albums** | Square cards | Opens the existing album details overlay |
| **Tracks** | Flat list with letter headers, plus **Play all** / **Shuffle all** | Rows carry play + like + overflow, **all three focusable** |
| **Playlists** | — | Scope decision needed; see §Open questions |

§11.5 is authoritative here, and §11.5.1 already records the deferral this branch is meant to
close on track rows.

---

## The work

Ordered so each step is verifiable before the next. Tests are part of each step, not a phase at
the end.

### 0. Commit the README

- [ ] Commit the untracked `README.md` on this branch (or cherry-pick it onto `dev` if it should
      land independently of the music work — it is unrelated to the feature).

### 1. Data layer

The wire models are **already generated** in `data/model/Music.kt` — `AlbumsData`,
`MusiciansData`, `MusicianDetailsData`, `TracksData`, `LikedTracksData`, `LikedTrackIdsData`,
`MusicStats`, `ShuffleTracksData`, `TrackDetailsData`, `TrackLikeToggleData` — plus
`data/model/MusicPlaylists.kt`. `data/api/MusicApi.kt` and `data/repository/MusicRepository.kt`
currently expose only **three** of them: `latestAlbums()`, `albumDetails(id)` and
`trackStreamUrl(id)`.

- [ ] Add the endpoints this screen needs to `MusicApi.kt`, with their real query parameters from
      `docs/openapi.json`:
      - `GET /music/albums` — `page`, `per_page`
      - `GET /music/musicians` — `page`, `per_page`
      - `GET /music/musicians/{id}`
      - `GET /music/tracks` — **`limit`, `offset`** (note: not page/per_page, unlike the other two)
      - `GET /music/tracks/liked` — `page`, `per_page`
      - `GET /music/tracks/liked-ids`
      - `GET /music/stats`
      - `POST /music/tracks/{id}/like`
      - `GET /music/playlists` (only if Playlists is in scope — see §Open questions)
- [ ] Mirror them in `MusicRepository.kt` through `safeApiCall`, following the caching and
      error-mapping shape `MovieRepository` already uses.
- [ ] **Verify every model against a live response, not against the spec.**
      [`docs/known-issues.md`](docs/known-issues.md) documents eight drift instances, one of which
      was the *spec* being wrong (`Chapter.movie_id`), and records that roughly two thirds of the
      143 declared types have no call sites — which is exactly the set this step is about to start
      using. Curl each endpoint and compare. Fix the model, or record the conflict, rather than
      silently choosing a side.
- [ ] Tests: repository suites built on `app/src/test/…/data/repository/TestHttp.kt` (request
      shape, decode, paging, error mapping) and serialization cases in
      `ApiModelsSerializationTest.kt` for any model whose shape this pass corrects.

### 2. The Music pane and its tab strip

- [ ] Add a `Music` branch to `PaneBranch` in `feature/home/IglooApp.kt` (~line 924) so the
      destination stops falling through to `PlaceholderContent`.
- [ ] Build `feature/music/MusicScreen.kt` + `MusicViewModel.kt`. **Reuse the Movies pass rather
      than re-deriving it**: `core/ui/IglooTabRow.kt` / `SelectablePill.kt` for the strip, and
      `MoviesViewModel.kt`'s switch model for the behavior — focus-to-switch, the 300 ms debounce
      constant, press-to-commit-immediately, revert-and-notice on a failed switch, and per-tab
      state retention. Four tabs here instead of three.
- [ ] Session-scoped view model, registered through
      `feature/auth/AuthenticatedSessionViewModelStoreOwner.kt`, so paging and scroll survive
      Home↔Music and are cleared on sign-out.
- [ ] Immutable state with explicit loading / loaded / empty / error per tab, using the shared
      `IglooLoading` / `IglooEmpty` / `IglooInlineError` recipes (`docs/design-system.md` §10) —
      skeletons must match the real grid's column count and card aspect so focus does not jump.
- [ ] Tests: a `MusicViewModelTest` covering the switch/debounce/revert matrix the way
      `MoviesViewModelTest` does, plus an instrumented behavior suite on `TestIglooApp`.

### 3. Musicians tab

- [ ] Circular cards in a grid, paginated over `page`/`per_page`, with the design system's
      artwork fallback for a musician with no image.
- [ ] Focus contract: strip → grid → (right edge pinned, the way `MoviesFocus.kt` pins Movies).
- [ ] Tests: paging, empty, error + retry; TalkBack announcement per card carrying only what
      helps the user act.

### 4. Albums tab

- [ ] Square cards over `GET /music/albums`, paginated. Card press opens the **existing** album
      details overlay — `feature/music/AlbumDetailsScreen.kt` is already built and is already the
      third occupant of the host's one details slot.
- [ ] `DetailsOrigin` needs a case for "opened from the Music pane's album grid" so Back restores
      focus to the exact card. Today the only album path is
      `DetailsOrigin.Rail(HomeRail.LatestAlbums)`.
- [ ] Tests: open → Back → focus lands on the originating card; the overlay still closes one layer
      at a time.

### 5. Tracks tab

The biggest piece, and the one that closes §11.5.1's recorded deferral.

- [ ] Flat list over `GET /music/tracks` (`limit`/`offset`) with **letter headers**.
- [ ] **Three focusable actions per row** — play, like toggle, overflow menu — none hidden until
      focus. This is also the fix for the album details screen's one-stop rows, so do both or
      state plainly why not.
- [ ] **Play all** and **Shuffle all** above the list.
- [ ] Per-track play needs a player entry point that is not "the whole album": today
      `MusicPlayerScreen` is handed an album queue. Decide and document whether a track list
      becomes a queue of its own or a single-item queue.
- [ ] Like toggle over `POST /music/tracks/{id}/like`, with `liked-ids` seeding the initial state.
      A mutation failure is an announced `IglooNotice`, not an inline error card
      (`docs/design-system.md` §10).
- [ ] Tests: reducer/view-model cases for the toggle and its failure path; instrumented focus
      chain across all three row actions; a TalkBack suite asserting each row reads as one
      sentence with its actions distinguishable.

### 6. Musician detail

- [ ] Backdrop + hero + list, over `GET /music/musicians/{id}`, as the **fourth** occupant of the
      one details slot — mutually exclusive with both movie pages and album details, closing the
      others on open exactly as `IglooRoot`'s existing callbacks do.
- [ ] This finally gives the album details screen's artist chips a destination; they are inert
      today purely because no musician screen existed.
- [ ] Tests: focus contract + accessibility contract suites, mirroring
      `AlbumDetailsFocusTest` / `AlbumDetailsAccessibilityTest`.

### 7. Shuffle

Shuffle is a host-owned stub in three places today and has a full written spec already:
[`docs/music-shuffle.md`](docs/music-shuffle.md) — two queue models (an endless server-fed rolling
queue for whole-library shuffle, a client-randomized finite queue for album/musician/playlist),
with constants and race rules matching the web client.

- [ ] Decide whether Shuffle lands on this branch or its own. Both status docs say "its own
      branch"; the Tracks tab's **Shuffle all** button makes at least the library-shuffle half
      hard to defer.
- [ ] If in scope: `GET /music/tracks/shuffle` (`limit` ≤ 200, `exclude` ≤ 200 ids), the rolling
      queue, and the album/musician finite-shuffle path.

### 8. Documentation and verification

- [ ] Update `docs/design-system.md` §11.5 to describe the screen **as built**, the way §11.4 and
      §11.5.1 were written after their passes, plus a changelog entry. A number changed there
      means a number changed in `core/design/` and its test.
- [ ] Delete the "A Music destination screen" bullet from `docs/album-details-status.md` and
      `docs/music-player-status.md` once it is true, and write a `docs/music-screen-status.md`
      wrap-up in the same plain-language done/remaining shape.
- [ ] Full connected run on the **Shield** (`:app:connectedDebugAndroidTest`), plus an end-to-end
      live-server pass driven by ADB with screenshots — every tab, both detail overlays, playback
      from a track row, and Back out of each layer. Report the device, what was verified, and what
      could not be.
- [ ] `:app:lintDebug` and `:app:assembleDebug` clean.

---

## Explicitly not in this branch

Carry-over items that exist, are written up, and should **not** grow this branch. Listed so they
are not mistaken for gaps in the music work.

| Item | Where it lives |
| --- | --- |
| Play-stats reporting (`POST /api/music/user-stats/play`) — needs accumulator/completion/retry machinery and will likely bring a player ViewModel with it | `docs/music-player-status.md` |
| Background playback / `MediaSessionService` — Back and Home both stop audio today, deliberately | `docs/music-player-status.md` |
| Wire-model drift has no automated check; the fix needs `docs/openapi.json` on the test classpath, `allOf` flattening, OpenAPI 3.1 type unions, and a live response | `docs/known-issues.md` |
| Movies polish: the count line not naming the active view, the selected genre chip not scrolling into view, strip motion, the re-announcing live region | `docs/cleanup-backlog.md` §5 |
| Movies test gaps: `MoviesActions` is wired to nothing under test, plus seven named view-model/screen cases | `docs/cleanup-backlog.md` §4 |
| Three remaining copies of the selectable-control recipe (likely a `Modifier.iglooSelectable`, not another composable) | `docs/cleanup-backlog.md` §1.1 |
| Movies **Playlists** tab — when playlists get a screen, Liked moves inside it, as on the web | `docs/movies-screen-status.md` |
| Search, TV Shows, Photos, Settings panes | still `PlaceholderContent` |
| Server-down revert on a tab switch, never tested against a genuinely unreachable server (the tailnet host cannot be cut from the Shield without cutting adb) | `docs/movies-screen-status.md` |

One hygiene note that *is* this branch's business: `docs/music-shuffle.md` was committed on the
Movies branch under the message "Working on the movies tab" (`96e01ff`,
`docs/cleanup-backlog.md` §6.1). It belongs with this work. Left in place because moving it means
rewriting history.

## Open questions to settle before building

1. **Is Playlists in scope?** §11.5 lists it as the fourth tab, but music playlists are 12
   endpoints including collaborators and reordering, and `MusicPlaylists.kt`'s models are entirely
   unexercised. A "Playlists" tab that only lists and opens is a defensible first cut; creation,
   collaborators and reorder are clearly a separate pass.
2. **Does Shuffle land here or on its own branch?** See §7 — **Shuffle all** on the Tracks tab
   forces at least a partial answer.
3. **Do the album details track rows get their three actions in this pass?** They are the same
   component problem as the Tracks tab's rows, and §11.5.1 recorded the deferral, so doing both
   together is cheaper than doing them twice.
4. **What queue does a single track produce?** A track list queue, or a one-item queue. This
   decides whether `MusicPlayerScreen`'s album-shaped entry point needs to generalize.

## Merge checklist

- [ ] JVM tests pass: `./gradlew :app:testDebugUnitTest`
- [ ] Connected tests pass on the Shield: `./gradlew :app:connectedDebugAndroidTest`
- [ ] `./gradlew :app:lintDebug` and `./gradlew :app:assembleDebug` clean
- [ ] Driven end-to-end over ADB against a live server, D-pad only, with TalkBack on for at least
      one full pass of each tab
- [ ] `docs/design-system.md` §11.5 rewritten as-built, with a changelog entry
- [ ] Status docs updated; `docs/music-screen-status.md` written
- [ ] No dead code, no commented-out implementations, no leftover diagnostics
- [ ] New backlog items recorded in `docs/cleanup-backlog.md` rather than left in a review thread
- [ ] Report written: commands run, device used, behavior verified, what could not be verified,
      remaining real-device risk
