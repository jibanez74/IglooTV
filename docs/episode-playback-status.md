# Episode playback — status after the `feature/play-episode` passes (2026-10-21, 2026-10-23)

Plain-language record of the branch: what the show page can now do, what was verified and how,
and what is deliberately left for the next pass. The page itself is described in
[show-details-status.md](show-details-status.md).

## What was done

**Play/Resume and the episode cards play.** On the show details page the hero's Play/Resume
button starts the selected season's target episode and each episode card starts its own, through
the same video player movies and Continue Watching use. Focus returns to the control that
launched when the player closes, and the page re-reads the show and the selected season on the
way back, so the hero relabels ("Play S1 E1" becomes "Resume S1 E1" with its "21m left" strip)
and the rail's bars and checks follow.

**One launcher for every episode surface.** Home's Continue Watching preparation moved out of
`HomeViewModel` into `feature/shows/EpisodePlayLauncher.kt`, which both Home and
`ShowDetailsViewModel` own: a press reads the episode's header, technical details and the
caller's saved position in parallel, builds the request with the default mode and tracks, and
falls back to Remux when the capability gate refuses Direct play (the page has no Playback
Settings; the in-player menus stay the place to change quality or tracks). Repeated presses on one
episode coalesce; a press on another abandons the first; a refresh or the page closing abandons a
preparation and drops a launch the host has not taken. The address pairing Home used for its
rails became `withApiBaseUrl(serverUrl)` in `core/network/ServerUrlProvider.kt`, shared instead
of copied.

**The host treats the page like the movie page.** A press names the control that asked (hero or
rail), the host arms the landing and accepts the request only for that press (a recreation never
finishes a press from the old activity), two new `VideoPlayOrigin` values restore focus to
Play/Resume or to the rail's last-focused card, the player goes down with the page, and the show
page leaves TalkBack traversal while the player is up. A press that went nowhere is a polite
notice under the action; a failed exit save follows the user back to the page as the movie hero's
Retry card, a section of the focus chain between Play/Resume and Overview. TalkBack hears the
verb alone as Play/Resume's action ("double-tap to Resume") and "Play 1. Pilot" on a card.

**Not changed.** The extras rail stays inert (marking watched and up-next landed in the second
pass below), and the contract sync in `docs/openapi.json` (a People endpoint, `tmdb_id` on
credits, wording) touched nothing the client calls.

## How it was verified

- **JVM**: 1,061 tests pass (`./gradlew :app:testDebugUnitTest`), including the extended
  `ShowDetailsViewModelTest` (23: the launch, the notice, coalescing and abandonment, close,
  refresh, a season switch leaving a preparation alone, the Remux fallback) and
  `ShowDetailsMappingTest` (the target's episode and verb); `HomeViewModelTest`'s episode cases
  pass unchanged through the shared launcher.
- **`./gradlew :app:lintDebug`**: 0 errors; 34 warnings and 4 hints, none in the touched code.
  `:app:assembleDebug` is clean.
- **Emulator** (`Google_TV_API_34_1080p`, against the local backend's seven-show library): a
  d-pad pass opened Friends, pressed "Play S1 E1" (the player opened as "Friends · S1 E1 ·
  Pilot", timecodes advancing), backed out after the save threshold to find focus on the hero
  and the button relabelled "Resume S1 E1" over "21m left", moved into the rail (the card
  showing its bar and "21m left"), pressed the card (the Resume prompt at 2:45, Resume played
  on), and backed out to the card, refreshed to "20m left".
- **Instrumented, emulator**: the ten suites around the touched pieces pass, **223/223**
  (`ShowDetailsFocusTest` 22, `ShowDetailsAccessibilityTest` 22, `VideoPlayerOverlayFocusTest`
  17, `VideoPlayerScreenTest` 52, `ShowsGridBehaviorTest` 13, `HomeHeroFocusTest` 20,
  `HomeRailBehaviorTest` 38, `ShellBleedTest` 4, `IglooButtonSemanticsTest` 3,
  `MovieDetailsFocusTest` 32), after one fix to a new accessibility case that had asserted the
  Retry card's message on the card node instead of its text node.
- **Emulator, TalkBack** (verbose speech log): the show page landed on the hero summary; Down
  read "Resume from 2 minutes and 59 seconds; 20 minutes remaining. Resume season 1, episode 1,
  Pilot. Button" with "Press select to Resume"; the press opened the Resume prompt; Back put the
  cursor back on the button; the rail card read its one sentence with "20 minutes remaining" and
  "Button", its press opened the prompt, and Back returned to the card.
- **Shield** (Android 11, 4K, TalkBack on, against the user's LAN server): the same ten suites
  pass, **223/223** (after the same fix). A live pass with the speech log walked Home to Friends,
  heard "Play season 1, episode 1, Pilot. Button" and "Press select to Play", pressed it
  ("Episode player", "Playing: Friends · S1 E1 · Pilot"), backed out (a short play, no progress
  written) to "Friends" and the button again, then the Overview stop, the selected chip and the
  rail card. TalkBack, its log level and logd were restored afterwards; the test package was
  removed from both devices.

## What could not be verified

- **A failed exit save** was not produced live (it needs the backend to go away between the
  player's last cadence save and Back); the Retry card, its place in the chain and its focus
  hand-back are covered by `VideoPlayerOverlayFocusTest` and `ShowDetailsFocusTest`.
- **A press that goes nowhere** (the notice) was not produced live for the same reason; the
  view model and accessibility suites cover it.
- **Activity recreation mid-preparation** drops the landing request by design (the armed site is
  plain `remember`, the movie page's rule); not exercised live.

## The second pass (2026-10-23): Up next, Resume without asking, marking watched

Plain-language record of the pass that closed the three items the list below used to carry.

### What was done

**Up next.** The player's request now carries the episode that follows (the server's
`next_episode`), and fifteen seconds before an episode ends the Up Next card appears in the
bottom-end corner over the picture: the next episode's still, "UP NEXT", "S1 E2 · Title" and a
count of the episode's own remaining seconds ("Playing in 12s", or "Resuming in 12s" when the
viewer has a position on it). It takes focus the moment it appears, so Center plays the next
episode now; Back dismisses it and the episode plays out to a close as before; Up and Down bring
the chrome back under it (the card lifts above the transport block); a seek back past the lead
retracts it. **The end of the episode advances at once** — no post-end countdown, where the web
counts ten seconds after the video ends — whenever a next episode exists and the card was not
dismissed, even if it never showed. The hand-off is an in-place swap: the finished episode's
screen disposes (its exit save records the end, flipping it watched; its engine goes) and a fresh
one mounts for the next episode exactly as a launch does, playing from the saved position with no
Resume prompt. The next request is prepared while the card is up through the same
`EpisodePlayPreparer` a launch uses; if the end comes first the ended frame is held under the
card reading "Loading next episode…"; a preparation that fails shows why on the card and the end
closes the player. Movies and a show's last episode close as before.

**The page follows the player.** When the player closes after one or more hand-offs the host
names the episode that last played, and the show page selects its season (kept, cached or read)
and lands focus on that episode's card when the rail launched — the rail is rebuilt with its
focus memory seeded on the landed card — or on the relabelled Play/Resume when the hero did.

**Resume without asking.** A control that already said Resume — the hero's "Resume S1 E3", a
card drawn with its progress bar, a Continue Watching card — starts at the saved position without
the Resume prompt (`promptForResume = false`); one that said Play keeps the prompt for a position
the page did not show. The movie page is unchanged.

**Marking watched.** Holding Center on an episode card opens "Episode options" beside it, one
row: "Mark watched" or "Mark unwatched". The write is optimistic — the check and the hero's target
follow at once, marking watched clears the saved position as the server does — the server's
answer re-reads the season and refreshes Home's Continue Watching, a refusal puts the row back
with a polite notice under the rail ("Couldn't update watched status: …"). TalkBack gets the same
choice as a custom action on the card: with TalkBack on, Center reaches a card as a click, so the
hold is not a gesture there and the action is the path.

**Plumbing.** `EpisodePlayLauncher`'s reads moved into `EpisodePlayPreparer`, which Home, the show
page and the player's hand-off share; `VideoPlayRequest` gained `episode` (season number and the
up-next) and `promptForResume`, both saved across recreation; `VideoPlayerViewModel.endSession`
takes the media it ends, because under the hand-off the old screen's dispose and the new screen's
session start share one apply pass; `ShowEpisodePlaybackData` decodes `next_episode`;
`ShowApi`/`ShowRepository` gained the watched write; `SetMovieWatchedRequest` became the shared
`SetWatchedRequest`.

### How it was verified

- **JVM**: 1,091 tests pass (`./gradlew :app:testDebugUnitTest`), including the new
  `UpNextTest`, the launcher/preparer, watched and landing cases of `ShowDetailsViewModelTest`,
  the saver's new slots, the serialization and contract tests for `next_episode` and the watched
  route, and `VideoPlayerViewModelTest`'s media guard.
- **`./gradlew :app:lintDebug`**: 0 errors; 34 warnings and 4 hints, none new.
- **Instrumented, emulator** (`Google_TV_API_34_1080p`): the four suites around the touched
  pieces pass, **148/148** (`VideoPlayerScreenTest` 68, `VideoPlayerOverlayFocusTest` 19 — the
  card's appearance, focus, count, Play now, the held end, dismissal, retraction, a failed
  preparation, the key map, the menu wait, the lift above the transport, the background trip,
  the in-place hand-off and the landing call; `ShowDetailsFocusTest` 36 and
  `ShowDetailsAccessibilityTest` 25 — the held Center, the menu's trap, Back and selection, the
  custom action, the notice, and every landing case).
- **Live, emulator, against the local backend** (Friends): "Resume S1 E1" resumed with no
  prompt; the card appeared at fifteen seconds, focused, and the end swapped to S1 E2 from 0:00
  with the new title; Center on the card swapped at once (S1 E2 → S1 E3); S1 E24's card offered
  "S2 E1 · Resuming in 2s" and the end crossed into Season 2; Back on S2 E1's card dismissed it,
  the episode played out, the player closed and the page landed on Season 2 with S2 E1's card
  focused and checked; a rail launch (S1 E4 → E5) landed on E5's card with E4 checked; a hero
  launch (S1 E6 → E7) landed on Play/Resume with the rail's memory on E7. Holding Center on a card
  opened "Mark watched", the check appeared at once, a second hold offered "Mark unwatched", Back
  closed the menu onto the card.
- **TalkBack, emulator** (verbose speech log): "Resume from 19 minutes and 38 seconds; 2 minutes
  remaining. Resume season 1, episode 6, The One with the Butt. Button" → "Episode player",
  "Playing: Friends · S1 E6 · …"; the card read "Up next: season 1, episode 7, The One with the
  Blackout. Button" on taking focus; the hand-off spoke "Playing: Friends · S1 E7 · …"; Back
  landed on "Play season 1, episode 5, …" and the rail entered on "Episode 7, The One with the
  Blackout". The first cut announced the count through a separate 1dp live region, which
  TalkBack's cursor jumped onto and which was not spoken alongside the focus read; the sentence
  now rides the card's own description (read with focus, re-read when the status changes) and the
  live region is gone.

### What could not be verified

- The **TalkBack actions menu** entry for "Mark watched" was not opened live (TV TalkBack's
  actions menu was not driven by key); `ShowDetailsAccessibilityTest` pins the custom action and
  performs it.
- A **failed preparation** and a **failed watched write** were not produced live; the screen and
  view-model suites cover them.
- The Shield and the Fire TV Stick were not used this pass.

## What remains

- **Extra videos** on the show page, through the trailer player.
- Person pages for cast and creators; the cards stay inert.
