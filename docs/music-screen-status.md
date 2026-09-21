# Music screen — status after the first pass (2026-09-19)

Plain-language record of where the Music destination stands after the `feature/music-screen`
branch: what was built, what was verified and how, and what is deliberately left.

## What was done

**The spine's Music tab is a real screen.** A heading with the selected section's count and a
Refresh button, a three-tab strip — **Musicians · Albums · Tracks** — and one scrolling surface
per section. Musicians are circular cards with the album and track counts underneath; Albums are
square covers; Tracks is a flat list with big letter headers and Play all / Shuffle all above it.
Each tab keeps its own pages, its scroll position and the card or row you were on, so switching
between tabs is instant and coming back from Home lands where you left. A tab you only slide
across on the way to another never fires a request.

**Every track row has three buttons: Play, Like, More.** All three are always visible and
focusable. Play starts playback, Like flips the heart (shared across every screen, so an album
liked on the Tracks tab is liked on its album page too), More opens a small menu with "Go to
album" and "Go to artist" where those exist. Moving up and down between rows keeps you in the
same button column. The album page's rows gained the same three buttons, so a row's Play now
starts the album at that track.

**Playing from the Tracks tab.** Play on a row queues every track loaded so far, starting at that
one, the way the web does. Play all starts at the top and keeps loading the rest of the library
as it plays. Shuffle all asks the server for a random batch and keeps asking, excluding what it
already has. All three open the same full-screen player, whose title, cover and "Track N of M"
line now follow the queue's source and the current track rather than one album. Closing the
player puts focus back on the exact button that started it.

**Shuffle works everywhere.** The album page's Shuffle button and the musician page's play the
same tracks in a fresh random order.

**A musician page.** Opening a musician card — or "Go to artist" from a row, or an artist chip on
an album page — shows the artist's thumbnail as the backdrop, their name and counts, Play all and
Shuffle, a rail of their albums, every one of their tracks as rows, and a facts panel. Opening an
album from there replaces the page (there is one details layer), and Back returns to the Music
pane.

**A review pass (2026-09-21).** The branch was read end to end for duplication, dead code and
organisation. The album and musician pages now share one set of overlay pieces
(`MusicDetailsShared.kt`), the Music and Movies panes share their focus coordinator and chrome
helpers, the music formatting rules live in one file, the pane's pager is typed per tab, the
queue-fetcher seam sits in the data layer, and every API model nothing calls was deleted along
with a handful of unread fields and parameters. No behaviour changed; every test tag the
instrumented suites assert on is unchanged.

**Behind it.** The music list models had drifted from the server and could not decode a current
response; they were retyped from the server's own row structs and every Music endpoint the
screen needs was wired and tested. The player's request became a queue with a source, and a
small pure-Kotlin controller does the refills.

## How it was verified

- **JVM tests**: 880 pass (`./gradlew :app:testDebugUnitTest`), including new suites for the
  Music view model, the mapping and letter-header rules, the queue controller's refill and race
  rules, the likes view model, the musician mapping and view model, the repository endpoints and
  the serialization cases.
- **`./gradlew :app:lintDebug`** and **`./gradlew :app:assembleDebug`** are clean.
- **Wire shapes** were confirmed against the main repository's Go row structs and, for the
  envelopes, against the local dev server (whose library is empty, so no populated item was
  seen). The tailnet server's credentials were not available to this pass.

## What could not be verified

- **No device or emulator was attached** during this pass and the machine has no AVD, so the
  instrumented suites (`:app:connectedDebugAndroidTest`) — including the new Music tabs, track
  list, musician page and player-queue suites — were compiled but **not run**, and the D-pad and
  TalkBack passes over ADB were not driven. These are the first things to run on the Shield.
- The endless refills, the shuffle exhaustion notice and the failure notice need a populated
  live library to see in practice.

## What remains

- **Playlists** — the fourth tab, with the Liked-tracks view inside it, is its own pass.
- **A two-deep details stack** (musician → album → back → musician) — recorded in
  `cleanup-backlog.md` §7.1; today the second page replaces the first.
- **Play-stats reporting** and **background playback** — unchanged from `music-player-status.md`.
- Everything the review left in `cleanup-backlog.md` §7.
