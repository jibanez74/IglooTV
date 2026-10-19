# Show details — status after the first pass (2026-10-19)

Plain-language record of the `feature/show-details` branch: what the TV show page does, what was
verified and how, and what is deliberately left for the next pass.

## What was done

**A show opens its own page.** Pressing a show on the TV Shows grid, on Home's Recently Added
Shows rail, or on the Home hero's show page now opens the show details page, announced as
"Open {name}". Back closes it and puts focus back on the card or hero that led away. Continue
Watching episode cards still resume straight into the player, as on the web.

**UI and UX only, on purpose.** The page mirrors the web client's `/tv-shows/{id}`, and every
control the web page has is drawn in its final place and can be reached with the d-pad and
TalkBack — but nothing plays and nothing is written yet. Play/Resume, the episode cards and the
extra-video cards are inert: focusable, announced, no button role and no action, so the next pass
only has to add the presses.

**The page, top to bottom** (design-system §11.4.3):

- The movie hero: backdrop, poster, title, tagline, a metadata row (star rating, certification
  and series status as chips, then "10 Seasons · 236 Episodes · 1994–2004", with "2 of 3 Seasons"
  when TMDB knows more than the library holds), genres, and Play/Resume for the selected season —
  "Resume S1 E1" over the movie's resume strip ("11m left"), spoken "Resume season 1, episode 1,
  Holiday Knights" with the exact resume point as state.
- Overview.
- Seasons: a scrolling chip row ("Season 1 · 24" … "Specials · 2", Specials last) that selects on
  **press**, the Genres picker's contract. A press loads the season (cached afterwards), retargets
  the hero and hands focus to the season's first episode. Shows with one season have no chips.
- One episode rail for the selected season: 16:9 stills with a progress bar while resumable and a
  check once watched, "1. Pilot", "23m · September 22, 1994 · 11m left", and a three-line faded
  overview. Each card is one TalkBack sentence, entered with the season named ("Season 2.
  Episode 1, …").
- Key Crew (the creators), Cast (top ten with episode counts), Extra Videos, and "About {name}"
  (original name, networks, production, status, type, language, first and last aired).

**Built from shared pieces.** The movie page's sections, rail wrapper, hero prose and metadata
row, single-action row, resume strip and render models moved to `feature/shared` and are used by
both pages; the Genres picker's chip row became `SelectableChipRow`; Home's announced section
move became `rememberAnnouncedSectionColumn` in `core/ui`, which the show page uses so moves into
sections below the fold are heard. `IglooButton` gained the inert null-press contract,
`IglooMediaRail` pins caller-given vertical edges in every state, and `IglooPosterCard` a detail
line. Movie, album and musician pages render and speak as before.

**Two TalkBack fixes for every details page, found during the emulator pass.**

- *Entry under a screen reader.* When a details page's loading skeleton gave way, focus briefly
  had no owner, Android handed it to the hero summary stop, and TalkBack settled there; the move
  to Play that followed was ignored, so TalkBack's cursor and input focus disagreed and Down
  skipped Play's announcement. Under a screen reader, every details page (movie, in-theaters,
  show, album, musician) now lands on the hero summary on purpose, with Play one press down.
  Sighted d-pad users still land on Play.
- *TalkBack turned on with the app open.* The app kept believing no screen reader was running —
  no reading stops, no announced moves — until restarted. `rememberSpokenAccessibilityEnabled`
  now re-reads after a reported change settles, listens for service changes on API 33+, and
  re-reads on every resume.

**Behind it.** `ShowApi`/`ShowRepository` gained `GET /shows/{id}/seasons/{n}/episodes`; the
show details models gained only the fields the page reads, and the genre, company and
extra-video rows are now one model each for movies and shows. `ShowDetailsViewModel` holds the
page in Loading until the details and the first season have settled, keeps what is on screen
through a failed refresh, and drops late answers for a show or season the user has left.

**A 4K review pass of the page (2026-10-19, Shield, TalkBack off per capture)** walked The New
Batman Adventures, Superman: The Animated Series and Friends, every section, the chip press, the
rail ends and Back to Home and to the grid. The page held up — stable hero across season switches,
focus restored everywhere — and three things were fixed from it:

- *Focused chips looked smudged.* The focus glow is an elevation shadow on the same layer as the
  control, and Android draws it under the layer, so it showed through the translucent `card @ 0.72`
  focus fill as a lighter band and a dark bar along the bottom — on season chips, the Genres
  picker and the sidebar's rows alike. Every ghost focus fill now goes through
  `IglooColors.focusFill(ground)`, which composites the same alpha over the control's ground.
- *Episode cards misaligned when a title wrapped.* Cards now reserve two title lines, so the meta
  line and overview sit on the same rows across the rail.
- *The season chip row cut a scrolled chip at the screen edge.* It now carries the rails'
  leading-edge fade (`core/ui/LeadingEdgeFade.kt`).

One finding outside the branch, the in-theaters movie page's poster clipped at the top of the
screen, is recorded in `docs/known-issues.md` for a later pass.

## How it was verified

- **JVM**: 1,039 tests pass (`./gradlew :app:testDebugUnitTest`), including the new
  `ShowDetailsMappingTest` (16), `ShowDetailsViewModelTest` (15), the show repository and
  serialization cases, and the contract test with the new route.
- **`./gradlew :app:lintDebug`**: 0 errors; 42 warnings, none in the new show code (the rest are
  the existing dependency-version and `ModifierParameter` ones). `:app:assembleDebug` is clean.
- **Emulator** (`Google_TV_API_34_1080p`, against the tailnet library through a plain-HTTP relay
  on the host): the full instrumented suite passes, **642/642**, including the new
  `ShowDetailsFocusTest` (19), `ShowDetailsAccessibilityTest` (19), `IglooButtonSemanticsTest`
  (3) and `LiveFlagTest` (3), and the flipped grid, rail and hero cases. A d-pad pass covered
  Friends (10 seasons), Superman: The Animated Series (Specials last, "Play S0 E1"), The New
  Batman Adventures (Resume with the strip, no extras), the chip press, and Back from the grid,
  the rail and the hero. A TalkBack pass with the verbose speech log walked the whole page and the
  chip press, and the movie page's moves into sections below the fold were all heard as well.
- **Shield** (Android 11, 4K): 211 tests across the 13 suites the branch touches pass. A live
  TalkBack pass with the speech log covered the hero entry, the summary landing, every section
  down to About, the chip press to Season 2, Back to the hero, and the Recently Added Shows round
  trip; TalkBack was switched on with the app open and the reading stops appeared. A 4K visual
  check of the hero, chips and episode rail was taken with TalkBack off. The session survived the
  reinstalls; TalkBack, its log level and logd were restored afterwards.

- **Review-pass fixes** (2026-10-19, later the same day): JVM 1,039/1,039 and lint 0 errors again;
  on the Shield, the seven suites around the touched pieces pass, **136/136**
  (`IglooButtonSemanticsTest`, `IglooMediaRailFadeTest`, `NavigationRailBehaviorTest`,
  `ShowDetailsAccessibilityTest`, `ShowDetailsFocusTest`, `IglooTabRowTest`,
  `MoviesGridBehaviorTest`), and 4K crops of a focused unselected season chip, the focused
  selected sidebar row, Friends' scrolled chip row and its season 10 rail show the smudge gone,
  the fade in place and the cards' rows aligned.

## What could not be verified

- **A season that fails, loads slowly, or has no episodes** was not seen live — the library has
  none and both servers answered within a frame; the instrumented suites cover the loading,
  Retry and empty anchors and their focus.
- **A show with no seasons** exists only in the suites.
- **The "N of M" counts** were checked in the mapping tests, not on screen.

## What remains

- **Playing from the page**: Play/Resume, the episode cards and the extra videos (through the
  existing episode player and trailer player), each returning focus to its control.
- **Marking an episode watched**: a long-press episode menu, also offered as a TalkBack action.
- **Up next** after an episode, as the web's countdown and hand-off to `next_episode`.
- **Person pages** for cast and creators, once the TV app has one; the cards stay inert.
- Cleanup backlog §7.7 stays open: the show view model reads more than once, so it is not the
  fourth single-read details view model that entry anticipates.
