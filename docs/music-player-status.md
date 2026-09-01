# Music player (Play Album) — status

*Pass finished 2026-09-01, on the `feature/music-player` branch (uncommitted).*

## What was done

**The player.** Play Album on the album details screen now opens a full-screen music player:
the album cover centered over black (Music-glyph fallback when there's no artwork), the album
title in a top bar beside Back, and a bottom block with the current track's title, a
"Track N of M · artist" line, a seek bar, and a five-button transport — previous track,
rewind 10s, play/pause, forward 10s, next track. The chrome never auto-hides: there's no
moving picture to reveal, so every control stays visible and Back always means leave.

**The queue.** The whole album is one ExoPlayer playlist in disc-then-track order, so
**auto-advance is free** — a track ending rolls into the next with the title, position line,
and TalkBack announcement updating. Skip semantics are the platform's: next jumps forward,
previous restarts the current track past ~3 seconds and crosses to the prior track under it.
On the remote, **MediaNext/MediaPrevious (and the Skip keys) mean tracks** while
Rewind/FastForward keep the ±10s in-track seek — a music-specific pre-handler intercepts the
skip keys before the shared player key map would spend them on seeks.

**The session.** A per-album MediaSession publishes each track's title, artist, album, and
cover to the system, so the Android TV now-playing surface and remote media keys see the real
metadata (verified via `dumpsys media_session` on the Shield).

**Stop on exit, silence in background.** Back stops the audio immediately and lands focus
back on the Play Album button that launched it. Leaving with Home releases the player and
session entirely (no background playback this pass — a deliberate scope choice); returning
rebuilds the engine **paused at the same track and position**, waiting for an explicit Play.
The same rule the movie player follows.

**Ends and errors.** The album finishing closes the player like a movie ending does. A stream
error pins a Retry that rebuilds the engine at this visit's own playhead; a revoked session
offers only Close.

**Architecture.** The movie player's proven shape, minus everything video-only: a pure-Kotlin
engine seam (`MusicPlayerEngine`) cut exactly at ExoPlayer, a pure reducer
(`MusicPlayerState`) the chrome renders from, and no ViewModel — play-stats reporting was
scoped out (below), and without it there's nothing for one to own. The screen draws the cover
itself with `AsyncImage`; the seam has no surface member, so the fake engine is trivial.
Track streams go through the bearer-authenticated data-source factory; covers are absolute
Spotify URLs and deliberately unauthenticated.

**Tests.** All passing: 21 new JVM tests (the reducer state machine and the album→request
mapping) and 18 new on-device tests across two suites — the screen contract (entry focus,
intent-driven play/pause label, media keys, track changes, restoration, background trips,
error surfaces) and the overlay contract (open from Play Album, details layer leaves TalkBack
traversal, Back closes one layer at a time and restores focus, an overlay close underneath
takes the player with it). The full 94-test connected sweep of music + album + movie player
suites is green on the Shield.

**Verified end-to-end** on the Shield against the live server: Play Album → audio on track 1
with art, title, and an advancing seek bar; fast-forward to the end → natural auto-advance
into track 2; next/previous via remote media keys with standard restart-vs-cross semantics;
remote pause reflected in the chrome and the session (state PAUSED, correct metadata); Home →
session and audio fully released, return → paused at the same position; Back → audio stops,
focus on Play Album; TalkBack sweep — focus box walks the transport, edges pinned, Up lands
on Back.

## What remains

- **Shuffle** — still a host-owned stub; its own branch.
- **Play-stats reporting** (`POST /api/music/user-stats/play`) — scoped out; needs the
  accumulator/completion/retry machinery, and will likely bring the ViewModel with it.
- **Per-track play** (plus the like toggle and overflow menu §11.5 promises on each row) —
  the track rows are still single focus stops.
- **Background playback / MediaSessionService** — deliberately not built; Back and Home both
  stop the audio. If continue-listening-while-browsing is ever wanted, it's a separate pass.
- **A Music destination screen** — the spine's Music tab is still a placeholder; the Home
  albums rail remains the only way into an album.
