# Direct Playback — Status

_Last updated: 2026-08-21, branch `feature/direct-playback`._

## Where things stand

Pressing **Play** on a library movie's details page now actually plays the movie. The
foundation pieces that commit `8fa3875` introduced (the player state machine, the ExoPlayer
engine, the authenticated stream data source, the playback gate, and the progress writer) were
all sitting unwired; this pass connected every one of them end to end.

## What was done, in plain language

**The app now knows where the movie lives.** A small addition to the API client builds the
direct-stream URL (`/movies/{id}/stream`), and the app container now assembles the Media3 HTTP
stack that attaches the bearer token to same-origin stream requests and reports a rejected
session back to the sign-in machinery.

**Play assembles a real start request without racing its reads.** When you press Play, the details screen gathers
everything the player needs — the file's format, the audio and subtitle tracks you picked in
Playback Settings (resolved by the exact same rules the dialog shows, so what you saw is what
plays), and your saved watch position. Before anything opens, a pre-flight check asks the TV
whether it can actually make the selected audio track audible (decoder or passthrough). If it
can't — say, a TrueHD track on a TV with no receiver attached — a clear message appears right
on the details page naming the codec, and nothing opens. The same message surface says when a
non-Direct quality mode is selected, since only Direct play exists so far.
Technical details and watch progress are tracked as pending, successful, or failed rather than
as nullable fragments. A Play press waits for both successful reads, repeated presses coalesce,
and a failed read is the only preparation work Play retries. Empty track lists and nullable
progress fields remain valid successful responses.

**A full movie player screen exists now** (`feature/player/MoviePlayerScreen.kt`), built on the
same patterns as the trailer player:

- Transport controls (rewind 10s / play–pause / forward 10s), a seek bar with timecodes, and
  the movie's title in a top bar — all auto-hiding after four seconds of playback, with media
  remote keys working whether or not the controls are visible.
- A **resume prompt** ("Resume from 1:23:45?" / "Start over") whenever there's a saved position
  worth resuming; Back from the prompt simply leaves the player.
- In-player **Audio** and **Subtitles** menus fed by the actual tracks ExoPlayer found in the
  file; picking a row switches the track live, and "None" turns subtitles off. The buttons only
  appear when there's a real choice to make.
- An error surface with Retry — and Retry resumes from where playback stopped, not from zero.
  A revoked session shows Close instead, since retrying a dead session can't work.
- TalkBack support throughout: labeled controls, a polite announcement of play/pause/loading
  state, and the details page leaves the reading order while the player is on top.
- Media3 1.11's Compose `ContentFrame` renders through a fitted `SurfaceView`, preserving source
  aspect ratio and the TV/HDR-quality surface path; the system subtitle renderer stays over it.
- Play intent is independent of rendered playback. Dedicated Play and Pause keys never toggle,
  and Pause during initial load or rebuffering cancels pending autoplay.

**Watch progress is saved for real.** The already-tested progress writer is now driven by the
player: a save every 15 seconds once you've genuinely watched 15 seconds (seeking doesn't
count), plus an eligible final save on exit. Final writes also require 15 seconds of actual
playback, so resuming or seeking and leaving early cannot overwrite server progress. A periodic
failure leaves playback running and holds a polite Retry card in the chrome; exit failures and
timeouts carry that card back to movie details. Retry preserves the session id, increments the
sequence, and a retry or later cadence success clears the card and refreshes Continue Watching
and details.

**The shell hosts the player as its fourth overlay layer** (shell → details → trailer/movie
player), with the same care the other overlays get: closing the player puts focus back on the
exact Play button that launched it, Back is explicitly gated at every layer, and the player
survives activity recreation without losing your position or re-asking the resume question.

Along the way: the `onPlay = {}` placeholder and its slot in the details actions were deleted
(the play action now belongs to the shell, like the trailer), the "is this position worth
resuming" rule now lives in exactly one place shared by the progress strip and the resume
prompt, and three Media3 files gained the `@OptIn(UnstableApi)` markers that the first full
lint run demanded.

## How it was verified

- **JVM tests** — all green (`./gradlew test`). New suites cover the play-request assembly
  (including the stream-order/track-index conversion and the resume-worthiness table), the
  gate decision and where its refusal message lands, and the stream URL.
- **Instrumented tests on the local TV emulator** — all 238 green
  (`./gradlew connectedAndroidTest`, Google TV API 34). New suites drive the player screen
  through a fake engine (resume prompt, transport keys, chrome auto-hide, track menus, exit
  saves, error/unauthorized paths, standby) and the shell overlay (Play opens the player,
  TalkBack traversal, Back restores focus to Play, blocked requests show the notice and open
  nothing). Two latent issues from the foundation commit surfaced on the way, because its
  instrumented test class had never actually run: its method names used spaces, which the
  Android toolchain refuses to package, and one expectation guessed "7.1 surround" from a bare
  8-channel count where the web-parity rule deliberately says just "Surround".
- **Full build including lint** — green.
- **End-to-end on the emulator against a real backend** (local Igloo server, fresh install,
  quick-connect pairing):
  - Pressing Play on a movie with a Dolby Digital Plus/Atmos track was **refused by the
    capability gate** with the full message on the details page — the emulator genuinely has no
    decoder for it, so this was the blocked path working for real, not a simulation.
  - Playing *The Jungle Book* (mp4/AAC) with a seeded position showed the **resume prompt at
    exactly 15:00**; Resume started playback at that position with video visibly rendering.
  - The chrome auto-hid over playback, Up revealed it (title, transport, seek bar reading
    15:21 / 1:18:27, pause glyph while playing); a media fast-forward key seeked +10 s. The
    Audio/Subtitles buttons were correctly absent — the file has one audio track and no
    subtitle streams, so there was no choice to offer.
  - The **15-second progress writer saved to the real server** (position ~16:11 recorded, and
    the file's true duration replaced the seeded guess), and **Back produced the bounded exit
    save** seconds later, restored focus to the Play button, and the details page immediately
    showed the updated "62 min left" resume strip.
- **Not verified on the Shield.** Deliberately skipped this pass at Jose's request. Real-device
  playback (hardware decode, audio passthrough, PGS subtitles, TalkBack on hardware) is still
  an open item below.

## What remains

**Needs real hardware (first priority when the Shield is available again):**

- Play a real movie end to end on the Shield against the live server: video/audio output,
  seeking over HTTP range requests, audio passthrough, PGS and text subtitles, and the
  capability gate's verdicts on real codecs.
- A TalkBack sweep of the player chrome, resume prompt, and track menus on hardware.
- Confirm Continue Watching reflects progress after ~30 s of playback, and that finishing a
  movie flips it to watched.

**Deferred scope (agreed before this pass):**

- Quality chip, chapter markers, and volume controls in the player (design spec §11.8 lists
  them; no code exists yet).
- HLS / transcoded playback modes — the gate currently refuses anything but Direct with a
  clear message.
- MediaSession integration (system now-playing surface / remote transport), for which the
  dependency is already declared.

**Smaller follow-ups noticed during the work:**

- The Audio/Subtitles buttons are text-labeled; if icon glyphs are preferred, two vectors need
  to be authored for `IglooIcons`.
- `deviceCanPlayAudioMime` runs synchronously after preparation succeeds (cheap in practice);
  worth a second look only if a device shows a visible pause.
- Two `HomeViewModelTest` cases can fail when the whole JVM suite runs in one Gradle daemon and
  pass in isolation — pre-existing flakiness worth a look someday, unrelated to playback.
