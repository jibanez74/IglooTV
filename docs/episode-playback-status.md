# Episode playback — status after the `feature/play-episode` pass (2026-10-21)

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

**Not changed.** The extras rail stays inert, marking watched and up-next are not on the page,
and the contract sync in `docs/openapi.json` (a People endpoint, `tmdb_id` on credits, wording)
touched nothing the client calls.

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

## What remains

- **Extra videos** on the show page, through the trailer player.
- **Marking an episode watched**: a long-press episode menu, also offered as a TalkBack action.
- **Up next** after an episode, as the web's countdown and hand-off to `next_episode`; an episode
  that ends closes the player back to the page.
- **The Resume prompt after a "Resume" press**: pressing "Resume S1 E1" still asks "Resume from
  2:45?", today's movie-page and Continue Watching behaviour, kept for consistency. A request
  flag to skip the prompt when the control already said "Resume" would be the fix.
- Person pages for cast and creators; the cards stay inert.
