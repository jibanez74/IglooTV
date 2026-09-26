# TV Shows screen — status after the first pass (2026-09-26)

Plain-language record of where the TV Shows destination stands after the `feature/tv-shows-screen`
branch: what was built, what was verified and how, and what is deliberately left.

## What was done

**The spine's TV Shows tab is a real screen.** It is the Movies index for shows: a heading with
the count ("3 shows"), Sort (A–Z ⇄ Z–A) and Refresh, a two-tab strip — **All Shows · Genres** —
the Genres tab's chip picker ("Comedy · 2", spoken "Comedy, 2 shows"), and the same
infinite-scrolling poster grid, showing each show's name over its premiere year with the TV
glyph when there is no poster. Every focus rule, announcement and failure behaviour is the
Movies one with the noun changed. The web page's shape, minus the Playlists tab it never had.

**No Liked tab, on purpose.** The backend keeps no likes for shows, so the strip has two tabs
rather than three; the pane's data source simply offers no liked list and the tab is never drawn.

**Cards are inert for now.** There is no show details screen yet, so pressing a poster does
nothing and TalkBack announces the card as "Friends, 1994" with no "Open" action — the design
system's rule for a focus target with nothing to do, rather than a button that lies.

**One screen serves both Movies and TV Shows.** Rather than copy the Movies pane, it moved whole
into `feature/library/` and learned its kind: `LibraryViewModel` pages whatever routes a
`LibrarySource` names (the movie routes or the show routes, bound by one small factory each),
and `LibraryScreen` takes its heading, nouns, tags and glyph from `LibraryKind`. Every Movies
test tag and spoken phrase is byte-identical to before; the Movies view-model suite migrated by
renaming identifiers alone, with no assertion touched. `MoviePosterItem` became `PosterItem`, the
paged-list query and its 48-per-page constant moved to `data/api/PagedListQuery.kt` for both
APIs (with its comment corrected: the backend clamps a larger page size, it does not reject it),
and four backlog entries closed on the way (`filterFor`, the state model's own file, the two
expressions that read worse than they computed, and the actions wiring, now bound once in
`LibraryViewModel.actions()` and pinned by a test).

**Behind it.** `ShowApi`, `ShowRepository` and the five models the index calls
(`ShowLibraryItem`, `ShowsLibraryData`, `ShowGenreWithCount`, `ShowGenresData`,
`ShowsStatsData`) — nothing for details, seasons or episodes, per the rule that a model exists
only once a screen calls its route. The host gives the pane its own branch, grid state and focus
memory, so a Home → TV Shows → Home round trip lands on the card the user left, and re-reads
the count, the genres and (only while the grid is empty) page one on every foreground return,
exactly as Movies and Music do.

## How it was verified

- **JVM tests**: 913 pass (`./gradlew :app:testDebugUnitTest`), including the migrated
  `LibraryViewModelTest` (68 cases, unchanged), the new `ShowLibraryViewModelTest` (9: the show
  routes, the wire mapping, the two-tab strip, the Liked tab being unreachable, the actions
  binding), `LibraryKindTest` (the wording and tags per kind), `ShowRepositoryTest` (8) and
  three serialization cases.
- **`./gradlew :app:lintDebug`** (0 errors; the 41 warnings are the pre-existing dependency and
  `ModifierParameter` ones) and **`./gradlew :app:assembleDebug`** are clean.
- **Instrumented suites on the Shield** (`connectedDebugAndroidTest` with the leave-installed
  flag): 160 pass in one clean run — the two Movies grid suites (62 + 20, every tag and phrase
  unchanged), the two new Shows grid suites (13 behaviour, 11 accessibility: the anchors in every
  state, the two-tab strip, the picker, the inert card, the Home round trip, Back to the spine, and
  every announcement above), the navigation rail, the base shell, the Home rails, the confirm
  dialog and the tab row. The two shell cases that used TV Shows as the placeholder pane moved
  to Photos; its rail row sits past the fold of the rail's scrolling column at 540dp, so they
  scroll it into view before pressing, the way d-pad focus would. One earlier run lost its last
  three cases to the Shield falling asleep mid-run; the display was kept on for the recorded run.
- **Live pass on the Shield** against the tailnet library (3 shows, 6 genres), d-pad only,
  screenshots at every step: the spine's TV Shows row opens the grid of Friends, Pinky and the
  Brain and The Mandalorian with their posters, names and years, "3 shows" in the header, the
  first card focused and announced as "Friends, 1994" with no action; OK on a card does nothing;
  up lands on the All Shows tab and right onto Genres, which selects on focus, draws the six chips
  and shows The Mandalorian under "Showing 1 of 1 Action & Adventure show"; pressing the Comedy
  chip swaps the grid to Friends and Pinky and lands on the first card; up from the first row
  returns to the Comedy chip; Sort flips to Z–A and reorders the grid; Refresh re-reads page one
  with the grid staying on screen and re-anchors on the first card; left from column one opens
  the spine on TV Shows; a Home round trip comes back to the exact card, with Genres, Comedy and
  Z–A remembered; Back from a card opens the spine on TV Shows and right returns to the card.
  With TalkBack switched on the same walk — card, chip, tab, Refresh, Sort, back down, into the
  spine — follows input focus node for node with the announcements above.

## What could not be verified

- **The paging tail** ("Loading more shows.", the append Retry) needs more than 48 shows; the
  tailnet library has three, so it is covered by the instrumented suites and the shared machine's
  Movies coverage only.
- **The loading skeleton and the empty and error states** were not seen live — the library is
  populated and the server was up — and rest on the instrumented anchors.
- **The "Refreshing…" label** flips back before a screenshot can catch it against this server;
  the instrumented suite pins the state description instead.
- **Back twice** (spine → exit the app) was deliberately not pressed on the signed-in device.

## What remains

- **Show details, seasons and episodes** — the next pass. Until it lands the cards stay inert
  and the pane has no return requester; when it lands, the host gains a `DetailsOrigin` for the
  shows grid the way Movies has one, and the card gets its `onItemSelected`.
- **Episodes in Continue Watching** — Home still drops `kind: episode` entries
  (`MovieRepository.continueWatchingMovies`) until an episode has somewhere to open.
- **Likes for shows** — the backend has none; if it grows them, the source gains a liked
  fetcher and the strip its third tab with no screen change.
- **Search** — unchanged; `GET /search/shows` is not wired.
- Everything the review left in `cleanup-backlog.md` §7 (the Music pane still draws its own
  grid, header, tab row and skeleton; §7.2 now describes the Music-side merge).
