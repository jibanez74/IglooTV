# Cleanup backlog

Duplication, small correctness edges, coverage gaps and polish found during review passes and
deliberately **not** fixed at the time, each with enough detail to act on without rediscovering
it. Delete an entry when it lands.

This is the small stuff. Contract and behaviour gaps with real product consequences live in
[`known-issues.md`](known-issues.md); anything with a design consequence belongs in
`design-system.md` first.

**Opened:** 2026-09-02, from the Movies tab-strip review.

---

## 1. DRY

### 1.1 Twelve copies of the selectable-control recipe — a shared modifier was declined

**Files:** `core/ui/IglooButton.kt` (twice), `SelectablePill.kt`, `IglooRadioRow.kt`,
`IglooMenu.kt`, `IglooPosterCard.kt`, `feature/home/NavigationRail.kt`, `InTheatersCard.kt`,
`HomeHero.kt`, `feature/player/PlayerChromeCommon.kt`, `feature/auth/PinEntryScreen.kt`,
`ProfilePickerScreen.kt`

Each control repeats `var focused`, `focusRing`, `onFocusChanged`, a `clickable` with no
indication and a `clearAndSetSemantics` block. The 2026-09-29 DRY pass took only the light
touch — every site now passes `interactionSource = null` instead of remembering one nothing
reads — and deliberately stopped short of a `Modifier.iglooSelectable(...)`: the sites differ in
where the ring sits (the node itself, or a child such as the poster or avatar), in the disabled
fallback (`focusable()`, nothing, or `clickable(enabled =)`), and in the semantics, so one
modifier would need a flag for each. Revisit only if the clickable configuration must change in
one place.

### 1.4 `IglooTabRow` hand-rolls what `iglooSurface` does

**Files:** `core/ui/IglooTabRow.kt:39-45`, `core/ui/IglooSurface.kt:22`

Now documented — `iglooSurface`'s `clip(shape)` would cut the focused tab's 16dp glow at the
row's bounds — but it is still the one panel in the app that draws its own ground. If
`iglooSurface` ever grows a `clip: Boolean = true`, the tab row should take it, and the comment
should go.

---

## 2. Organisation

### 2.2 The library state model is `public`, its projection is `internal`

**Files:** `feature/library/LibraryUiState.kt`, `LibrarySource.kt`, `LibraryKind.kt` vs
`LibraryContent.kt`

`LibraryFilter`, `LibraryTab`, `LibraryGenre` and the rest of the library pane's model are
`public`, while `LibraryContent` is `internal`, and nothing outside the `:app` module consumes
any of them. Tightening means the whole chain goes `internal` together — `LibraryUiState`,
`LibrarySource`, `LibraryKind`, `LibraryViewModel`, `LibraryActions`, `LibraryScreen` — because
a public view model cannot expose an internal state type. Both test source sets see internals,
so it costs nothing at run time; do it with the Music pane's model so the two stay alike.

---

## 3. Small correctness edges

### 3.1 The tab's height arithmetic drifts at non-1.0 UI scale

**Files:** `core/ui/IglooTabRow.kt:84`, `core/design/IglooDimens.kt:96`

`IglooTheme.sizes.controlHeight - IglooTheme.spacing.xs * 2` subtracts two independently rounded
scaled `Dp`s (each `roundToInt().dp`), so the strip's total height can land ±1dp off
`controlHeight` at scales other than 1.0. The comment at `:83` asserts the row's padding "brings
the strip to `controlHeight`", which is exactly true only at 1.0. Either compute the tab height
from the unscaled values before rounding, or soften the comment.

### 3.2 The tab's radius is not concentric with the row's

**Files:** `core/ui/IglooTabRow.kt:82`, `:41`, `design-system.md` §9.1

A `radius.md` (8) tab inside a `radius.lg` (10) row with `spacing.xs` (4) padding should be
`radius.sm` (6) to nest cleanly. Cosmetic, currently documented as intentional in §9.1 — decide
deliberately rather than leave it as an accident, and if `md` stays, say why there.

### 3.3 `design-system.md`'s web-parity stamp is stale

**Files:** `docs/design-system.md:14`

"**Last verified**: 2026-08-02, against web client `3b48f9a6`" predates several passes. It is
about palette parity rather than behaviour, so nothing is wrong — but a stamp nobody refreshes
stops meaning anything.

---

## 4. Test coverage still open

From the Movies coverage audit; the higher-value gaps were closed in the review pass, these were
not.

**View model** (`app/src/test/…/feature/library/LibraryViewModelTest.kt`, over the movie routes)

- `refresh()` re-fetching page one when `grid is Error` — the foreground-return branch in
  `LibraryViewModel.refresh`.
- `onLikeCommitted()`'s early return when the Liked grid is not `Loaded`.
- `retryAppend()`'s active-job guard.
- `loadStats()` writing the library-wide count while the *requested* tab is All but the committed
  grid is still a filtered list.
- `notice` being cleared by the next successful switch — the failure branch sets it, nothing pins
  the clear.

**Screen** (`app/src/androidTest/…/MoviesGridBehaviorTest.kt`)

- Down from the tab row and the genre row when the content is card-less (`downRequester =
  contentStartRequester` in `LibraryScreen`) — e.g. the Genres tab with chips over an empty
  grid, or over a failed first page.
- Up from the card-less anchors to the strip in the **Loading** and first-page-**Error** states;
  only the placeholder and the empty Liked view exercise that edge today.
- The genre row scrolling horizontally with more chips than fit the panel — the fixture has two
  genres, so `horizontalScroll` (`LibraryGenreRow.kt`) is never actually scrolled.
- The genre list going empty *while a chip holds focus* — the row is disposed under the focused
  node. Only the opposite direction (a list arriving) is covered.
- A tab switch composed mid-flight (`refreshing = true` with a different `tab`).
- Pagination reset on a tab change asserted directly: scroll deep into All, switch, and pin both
  `gridState` at index 0 **and** that no `onLoadMore` fired from the outgoing scroll position
  while the swap was in flight.
- Sort × tabs: a sort flip on Genres or Liked re-anchoring the way Refresh does on All.

**Wiring**

- `LibraryViewModel.actions()` now binds the eight lambdas in one place and
  `ShowLibraryViewModelTest` pins each binding, so a lambda wired to the wrong method no longer
  passes the suite. Still open: every instrumented test hand-feeds a `LibraryUiState`, so one
  test that composes the screen over a real view model against a mock engine would close the
  loop that the focus-a-tab → debounce → request → revert → press-to-retry path depends on.

---

## 5. Movies polish recorded but not done

Each is its own small pass; none is a defect.

1. **The visible count line never names the active view.** `LibraryScreen`'s header draws a generic
   "146 movies" while only the *spoken* form says "146 Action movies". At 10 feet, after a genre
   press, nothing on screen confirms which list you are looking at. Highest value of the five.
2. **The selected genre chip is not scrolled into view.** `LibraryGenreRow`'s `horizontalScroll`
   state is independent of the selection, so re-entering Genres with a remembered genre far to the
   right shows the leftmost chips while the grid shows a genre you cannot see. `bringIntoView` on
   a selection change fixes it.
3. **`total` is not cleared across a switch**, so the header shows the previous view's count
   until the new page lands, then snaps.
4. **The strip has no motion.** The selected fill hard-swaps; a sliding indicator pill is the
   conventional premium treatment. Animate it with `iglooTween`, which snaps under reduced motion.
5. **The Genres tab could carry its active genre** ("Genres · Action"), so the section is legible
   without entering it.
6. **The count's polite live region re-announces on every tab landing.** The 300 ms debounce made
   this much rarer, but suppressing the announcement outright while a tab holds focus is the
   cleaner answer and matches `AGENTS.md`'s "avoid redundant announcements".

---

## 6. Repository hygiene

### 6.1 A music document sits on the Movies branch

**Files:** `docs/music-shuffle.md`, commit `96e01ff` ("Working on the movies tab")

That commit contains **only** a 227-line music-shuffle API spec — unrelated to the tab work,
referenced by nothing, and under a message that describes something else entirely. It belongs on
the music branch, or at minimum in a commit whose message says what it is. Left alone because
moving it means rewriting history.

---

## 7. Recorded by the Music screen pass (2026-09-19)

### 7.1 The details slot is single: musician ↔ album replaces rather than stacks

**Files:** `feature/home/IglooApp.kt`, `IglooRoot.kt`

Opening an album from the musician page (or an artist from the album page) closes the page that
was up and opens the other; Back then lands on the Music pane node that opened the *first* page.
The web goes musician → album → back → musician. A two-deep stack touches every host gate that
reads the open flags and the `DetailsOrigin` machinery; deferred, and recorded in §11.5.1.

### 7.4 The Liked-tracks view waits for Playlists

Every track row has a heart, but there is no list of liked tracks: on the web it lives inside
the Playlists tab, which is deferred. `GET /music/tracks/liked` is not wired for that reason.

### 7.7 Three single-read details view models with one shape — only the state is shared

**Files:** `feature/music/AlbumDetailsViewModel.kt`, `feature/music/MusicianDetailsViewModel.kt`,
`feature/movies/TheaterMovieDetailsViewModel.kt`

`open(id)` / `close()` / `retry()` / `refresh()` / `load(id, userInitiated)` with the same
stale-id guard. The 2026-09-29 DRY pass shared the state — `DetailsState<T>` and one
`errorOrKeep` in `feature/shared/DetailsState.kt`, also used by `MovieDetailsViewModel` — and
deliberately left the three view models as separate classes: a base class or loader would save
about forty lines apiece for an abstraction every details test would then have to understand.
Worth doing if a fourth appears.

### 7.6 Lint's `ModifierParameter` on the skeleton anchors

`PaneGridSkeleton` and `TracksListSkeleton` take `anchorModifier: Modifier`; lint wants the
parameter named `modifier`. It is not the composable's own modifier — it is
the anchor cell's — so the name is right and the warning is noise. Suppress or rename both
together.

---

## 8. Recorded by the DRY pass (2026-09-29)

### 8.1 Two dimensions still repeat across files

`Dp.scaled()`'s own rule sends a dimension used in two or more files to `IglooDimens` and the
§5 tables. Two remain: the 64dp brand mark on the auth screens (`feature/auth/AuthLayout.kt`,
`WelcomeScreen.kt`), and the 14dp / 10dp skeleton text stubs (`core/ui/IglooSkeletonCell.kt`,
`feature/shared/TrackRow.kt`, `feature/home/HomeHero.kt`). Each is a new token to name and
document, a design-system call rather than a cleanup, so it was left for one.

### 8.2 The start effect names nine view models twice

**Files:** `IglooRoot.kt`

`LifecycleStartEffect` keys on the nine session view models it then refreshes one by one. The
list can only be written once behind a shared `refresh()` interface the view models do not have;
adding one for this is not worth it.

### 8.3 Copy that says the same thing differently

- Session expiry is worded three ways: `feature/auth/ErrorMessages.kt`
  (`toLibraryDisplayMessage`), `SessionManager.revokedNotice`, and
  `playback/model/PlaybackMessages.kt`.
- A failed like reads "Couldn't update like status: …" on a movie and "Couldn't update like: …"
  on a track (both through `AppError.toFailureNotice` now, so aligning them is one word).
- "Rated … out of 10" opens a sentence in `MovieDetailsMapping.kt` and sits mid-sentence in
  `InTheatersCard.kt`, so the two spell it with different capitals.

### 8.4 Track rows repeat their like wiring

`AlbumDetailsSections.kt`, `MusicianDetailsScreen.kt` and `MusicScreen.kt` each derive
`liked = likes.isLiked(id)` and `likePending = id in likes.pendingIds` for `TrackRow`. Two lines
per site; a wrapper would add a composable to save them.

