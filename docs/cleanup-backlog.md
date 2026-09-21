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

### 1.1 Five copies of the selectable-control recipe

**Files:** `core/ui/SelectablePill.kt`, `core/ui/IglooRadioRow.kt:45`,
`feature/home/NavigationRail.kt:235`, `core/ui/IglooButton.kt:59`

The tab-strip review folded `IglooFilterChip` and `IglooTab` onto one `SelectablePill`, and
deliberately stopped there. Three more copies of the same body remain — `var focused by
remember`, `focusRing`, `.onFocusChanged`, the identical five-line `clickable(interactionSource
= remember { MutableInteractionSource() }, indication = null, onClick = …)`, and a
`clearAndSetSemantics` block. `NavigationRail.RailRow` even re-derives the "never set `selected
= false`, TalkBack would say *not selected* on every other row" rule with its own copy of the
comment (`NavigationRail.kt:267-268`).

They are not trivially mergeable: `RailRow` is a `Row` with an icon and a fading label,
`IglooRadioRow` has an inert non-clickable variant, and `IglooButton` carries variants,
recession and `labelVariants`. The shared part is the *modifier chain*, not the layout — so the
fix is probably a `Modifier.iglooSelectable(focused, onFocusChanged, onClick, semantics)` rather
than another composable. Worth doing only if a sixth control appears, or if the clickable
configuration ever needs to change in one place.

### 1.2 `committedFilter()` restates the derived `MoviesUiState.filter`

**Files:** `feature/movies/MoviesViewModel.kt:468`, `:120`

```kotlin
private fun committedFilter(): MoviesFilter? = when (committedTab) { … }   // :468
val filter: MoviesFilter? get() = when (tab) { … }                          // :120
```

The same three-branch mapping written twice, over the requested tab/genre in one and the
committed pair in the other. A top-level `fun filterFor(tab: MoviesTab, genre:
MoviesFilter.Genre?): MoviesFilter?` that both call would leave one copy. Small, and the two
readings do mean different things, so the comment carrying that distinction has to survive the
merge.

### 1.3 The no-genres copy is a literal in three places

**Files:** `feature/movies/MoviesScreen.kt:716`,
`androidTest/…/MoviesGridBehaviorTest.kt:37`, `androidTest/…/MoviesGridAccessibilityTest.kt:256`

`"Genres aren't available right now. Refresh to try again."` is a private const in the screen and
retyped in both instrumented suites (once as a const, once inline). Test duplication of user copy
is normal in this repo, but three copies of one sentence is one more than it needs; the
accessibility suite should at least use its own file's const rather than the inline string.

### 1.4 `IglooTabRow` hand-rolls what `iglooSurface` does

**Files:** `core/ui/IglooTabRow.kt:39-45`, `core/ui/IglooSurface.kt:22`

Now documented — `iglooSurface`'s `clip(shape)` would cut the focused tab's 16dp glow at the
row's bounds — but it is still the one panel in the app that draws its own ground. If
`iglooSurface` ever grows a `clip: Boolean = true`, the tab row should take it, and the comment
should go.

---

## 2. Organisation

### 2.1 The Movies state model lives in the view model file

**Files:** `feature/movies/MoviesViewModel.kt:26-131`

`MoviesFilter`, `MoviesTab`, `MoviesAppendState`, `MoviesUiState` and the debounce constant are
~95 lines of state model ahead of the class, while the sibling projection already has its own
file (`MoviesContent.kt`). A `MoviesUiState.kt` would match. Held back because `AGENTS.md` rules
out refactors that are not asked for — do it the next time this feature is opened for real work.

### 2.2 The Movies state model is `public`, its projection is `internal`

**Files:** `feature/movies/MoviesViewModel.kt:38,49,59,69` vs `MoviesContent.kt:11`

`MoviesFilter` and `MoviesTab` are `public`; nothing outside `feature/movies` and its tests
consumes either. Tightening to `internal` costs nothing (both test source sets see internals) and
makes the module boundary honest. Check `IglooRoot.kt` and `HomeTestFixtures.kt` compile first.

### 2.3 Two expressions that read worse than they compute

**Files:** `feature/movies/MoviesScreen.kt:125-126`, `:199-205`

`?: 0.takeIf { content is MoviesContent.Empty }` on an `Int` literal is a clever way to say
"zero, but only in the empty state" and takes a second read. And the skeleton's `loadingLabel`
is chosen with an `if (content is MoviesContent.GenresLoading)` at the call site, where the
`when` branch it sits in already knows which case it is — a `MoviesContent.loadingLabel` property
next to `isSkeleton` in `MoviesContent.kt` would put it with its siblings.

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

**View model** (`app/src/test/…/MoviesViewModelTest.kt`)

- `refresh()` re-fetching page one when `grid is Error` — the foreground-return branch at
  `MoviesViewModel.kt:185`.
- `onLikeCommitted()`'s early return when the Liked grid is not `Loaded` (`:269`).
- `retryAppend()`'s active-job guard (`:295`).
- `loadStats()` writing the library-wide count while the *requested* tab is All but the committed
  grid is still a filtered list (`:491`).
- `notice` being cleared by the next successful switch — `:398` sets it, nothing pins the clear.

**Screen** (`app/src/androidTest/…/MoviesGridBehaviorTest.kt`)

- Down from the tab row and the genre row when the content is card-less (`downRequester =
  contentStartRequester`, `MoviesScreen.kt:149`) — e.g. the Genres tab with chips over an empty
  grid, or over a failed first page.
- Up from the card-less anchors to the strip in the **Loading** and first-page-**Error** states;
  only the placeholder and the empty Liked view exercise that edge today.
- The genre row scrolling horizontally with more chips than fit the panel — the fixture has two
  genres, so `horizontalScroll` (`MoviesGenreRow.kt:80`) is never actually scrolled.
- The genre list going empty *while a chip holds focus* — the row is disposed under the focused
  node. Only the opposite direction (a list arriving) is covered.
- A tab switch composed mid-flight (`refreshing = true` with a different `tab`).
- Pagination reset on a tab change asserted directly: scroll deep into All, switch, and pin both
  `gridState` at index 0 **and** that no `onLoadMore` fired from the outgoing scroll position
  while the swap was in flight.
- Sort × tabs: a sort flip on Genres or Liked re-anchoring the way Refresh does on All.

**Wiring**

- Nothing connects `MoviesActions` to `MoviesViewModel`. Every instrumented test hand-feeds a
  `MoviesUiState` and every unit test drives the view model without composition, so a lambda wired
  to the wrong method in `IglooRoot.kt:274-284` would pass the whole suite. One test that composes
  the screen over a real view model against a mock engine would close the loop that the
  focus-a-tab → debounce → request → revert → press-to-retry path depends on.

---

## 5. Movies polish recorded but not done

Each is its own small pass; none is a defect.

1. **The visible count line never names the active view.** `MoviesScreen.kt:303` draws a generic
   "146 movies" while only the *spoken* form says "146 Action movies". At 10 feet, after a genre
   press, nothing on screen confirms which list you are looking at. Highest value of the five.
2. **The selected genre chip is not scrolled into view.** `MoviesGenreRow`'s `horizontalScroll`
   state is independent of the selection, so re-entering Genres with a remembered genre far to the
   right shows the leftmost chips while the grid shows a genre you cannot see. `bringIntoView` on
   a selection change fixes it.
3. **`totalMovies` is not cleared across a switch**, so the header shows the previous view's count
   until the new page lands, then snaps.
4. **The strip has no motion.** The selected fill hard-swaps; a sliding indicator pill is the
   conventional premium treatment. Gate it on `IglooTheme.reducedMotion`.
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

### 7.2 The Music and Movies panes still draw their own grid, header, tab row and skeleton

**Files:** `feature/movies/MoviesScreen.kt`, `feature/music/MusicScreen.kt`

The review pass of 2026-09-21 shared what had one body — the focus coordinator and ownership
(`feature/shared/PaneFocus.kt`), the scroll padding, tab presentation, Refresh labels and row
counts (`feature/shared/PaneChrome.kt`), the paging helpers (`Paging.kt`). What it left are the
composables that look alike but diverge: `MoviesGrid`/`MusicGrid` (the Movies grid carries the
silent Liked reconcile and a `Populated(items)` model, the Music grid is generic over its card),
`MoviesHeader`/`MusicHeader` (the Movies header has the sort control and the genre count line),
`MoviesTabRow`/`MusicTabRow` (identical modulo the enum — the easiest of the four), and the two
grid skeletons. Merging them is a Movies-side change that needs the Movies instrumented suites
re-run; `MoviesUiState`'s loose paging fields could move onto `PagedState` in the same pass.

### 7.3 §1.1's trigger has fired

The Music pane's tab strip and the album page's artist buttons reuse existing controls, so no
sixth copy of the selectable recipe was added — but the count of controls sharing that modifier
chain is now high enough that `Modifier.iglooSelectable(...)` is worth doing.

### 7.4 The Liked-tracks view waits for Playlists

Every track row has a heart, but there is no list of liked tracks: on the web it lives inside
the Playlists tab, which is deferred. `GET /music/tracks/liked` is not wired for that reason.

### 7.5 The `MusicActions` wiring is untested, like `MoviesActions` (§4)

`IglooRoot.kt` binds ten lambdas to `MusicViewModel`; a lambda bound to the wrong method would
pass every suite. One composed-over-real-view-model test would close both holes at once.

### 7.7 Three single-read details view models with one shape

**Files:** `feature/music/AlbumDetailsViewModel.kt`, `feature/music/MusicianDetailsViewModel.kt`,
`feature/movies/TheaterMovieDetailsViewModel.kt`

`open(id)` / `close()` / `retry()` / `refresh()` / `load(id, userInitiated)` with the same
stale-id guard and the same keep-on-background-failure rule, each over its own
`Loading/Loaded/Error` triple. About 60 lines apiece; a generic base or a shared
`DetailsLoad<T>` state would touch `IglooApp`, the fixtures and every details test for less
than it saves, which is why the 2026-09-21 review left them. Worth doing if a fourth appears.

### 7.6 Lint's `ModifierParameter` on the skeleton anchors

`MusicGridSkeleton` / `TracksListSkeleton` take `anchorModifier: Modifier`, as `MoviesGridSkeleton`
does; lint wants the parameter named `modifier`. It is not the composable's own modifier — it is
the anchor cell's — so the name is right and the warning is noise. Suppress or rename together
with the Movies one.
