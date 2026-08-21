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

**Play assembles a real start request.** When you press Play, the details screen gathers
everything the player needs — the file's format, the audio and subtitle tracks you picked in
Playback Settings (resolved by the exact same rules the dialog shows, so what you saw is what
plays), and your saved watch position. Before anything opens, a pre-flight check asks the TV
whether it can actually make the selected audio track audible (decoder or passthrough). If it
can't — say, a TrueHD track on a TV with no receiver attached — a clear message appears right
on the details page naming the codec, and nothing opens. The same message surface says when a
non-Direct quality mode is selected, since only Direct play exists so far.

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

**Watch progress is saved for real.** The already-tested progress writer is now driven by the
player: a save every 15 seconds once you've genuinely watched 15 seconds (seeking doesn't
count), plus one final save on any exit — Back, the movie ending, an error, even the session
being torn down. Finishing a movie records full progress so the server flips it to watched, and
Continue Watching and the details page refresh themselves afterwards.

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
- **Not verified on the Shield.** Deliberately skipped this pass at Jose's request. Real-device
  playback (actual video/audio output, passthrough, PGS subtitles, TalkBack on hardware) is
  still an open item below.

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
- `deviceCanPlayAudioMime` runs synchronously on the Play press (cheap in practice); worth a
  second look only if a device shows a visible pause.
- Two `HomeViewModelTest` cases can fail when the whole JVM suite runs in one Gradle daemon and
  pass in isolation — pre-existing flakiness worth a look someday, unrelated to playback.
