# HLS playback — status after the review pass

**Branch:** `feature/hls-playback` · **Pass completed:** 2026-08-26 · **Device:** Nvidia Shield (mdarcy,
Android 11), live backend over Tailscale HTTPS.

This is the wrap-up for the review of the branch's HLS work and the remediation that followed it.
The review's verdict was that the architecture is sound and the pure-function layering is genuinely
good — the defects were at the edges, and the real risk was that nothing had been shown to play a
frame of HLS on hardware. Both halves are now addressed.

---

## What the review found, and what was done

| | Finding | Severity | Status |
|---|---|---|---|
| F1 | In-player switch to **Direct** bypassed the audio-capability gate, so a TrueHD/DTS movie on a TV without passthrough could land on silent video. `MoviePlaybackServices.canPlayAudioMime` was plumbed in for exactly this and never read. | High | **Fixed** — the engine now runs the same `evaluatePlaybackGate` and refuses in the gate's own words, in place, without touching what is playing. |
| F2 | An unexpected exception in the manifest path (e.g. `serverUrl.require()`'s `IllegalStateException`, which the repository deliberately rethrows) escaped `scope.launch` and would kill the app mid-movie. | Med-high | **Fixed** — the restart coroutine routes non-cancellation throwables to the terminal error surface; the keepalive absorbs a throwing tick and keeps ticking. |
| F3 | The 10-second HLS resume rewind was re-applied on **every** engine reconstruction, so repeated background trips walked the movie backwards. | Medium | **Fixed** — `startPlayback` takes `rewindOnResume`; only a backend-carried resume point earns the rewind. Verified on hardware over two background trips. |
| F4 | Worst-case preflight wait was ~5 minutes (6 capacity attempts × a 45s manifest timeout) with only Back to escape. | Medium | **Fixed** — `HLS_START_TOTAL_BUDGET_MS` (90s) is a wall-clock ceiling on one `start`, on the coroutine clock so it is testable. |
| F5 | The ticker kept reporting the frozen outgoing source during a rebase, snapping the seek bar backwards and feeding the progress cadence a stale second. | Low-med | **Fixed** — no `Time` emission while a switch is pending. |
| F6 | Play during a preflight un-froze the source the design intends to hold frozen. | Low | **Fixed** — `play()` records intent only while `pendingMode != null`; `prepareSource` applies it. |
| F7 | Audio/subtitle labels could disagree between Playback Settings and the in-player menus when the wire list arrived unsorted. | Low | **Fixed** — `playbackSettingsUi` now labels from the same `stream_index` order the play request uses. |
| F8 | The keypress optimistically rewrote the host's saved `MoviePlayRequest.mode`, so a **failed** switch left a replacement engine rebuilding into a mode that had already proved it could not start. | Low | **Fixed** — `QualityOptionsChanged` carries the engine's `requestedMode`; the screen persists that, not the press. |
| F9 | Dead 3-arg `hlsLoadRetryDelayMs` overload; two controller narration strings hardcoded while the other four sentences were centralized. | Housekeeping | **Fixed** — overload removed, strings moved to `PlaybackMessages.kt`. |

### Deliberately not changed

- **The scope creep in the HLS commits** — AGENTS.md cut by ~890 lines, CLAUDE.md deleted, and four
  `docs/` files removed — is flagged, not reverted. It is unrelated to HLS and is the author's call.
- **`TrailerPlayerScreenTest.mediaTransportKeysSeekTenSecondsRegardlessOfChrome`** was failing on the
  branch *before* this pass (confirmed by running it in a clean worktree at `9b3bfd7`). The merge-base
  commit `8cc6162` taught `formatSpokenTime` to join with " and " and this one expectation was never
  updated. Corrected here as a one-word test fix, since a red suite hides real regressions.

---

## Test coverage added

The engine — 540 lines holding every one of F1–F6 — previously had **no** tests at all. It does now.

**JVM** (`app/src/test/`)
- `HlsSessionControllerTest`: the wall-clock budget gives up on time rather than attempt count; a
  budget that is not exceeded does not cut a session short; a throwing keepalive tick neither ends the
  loop nor escapes the scope.
- `MovieRepositoryTest`: manifest 404 → `Lost`, 401 → `Failed(unauthorized)`, `IOException` →
  unreachable, and a programming error rethrown rather than dressed as a transport failure.
- `HlsSessionPolicyTest`: the budget's relationship to the attempt budgets; the surviving retry rule.

**Instrumented** (`app/src/androidTest/`)
- **`ExoMoviePlayerEngineTest`** (new, 11 cases): resume-rewind vs. reconstruction; a concrete audio
  ordinal for a movie with audio and none for a video-only one; in-window seek vs. backwards and
  far-forward rebases; an HLS audio switch as a new session at the same position and a re-selection as
  inert; Direct→HLS stop-and-restart with a rotated session key; the ladder reporting the effective
  profile without rewriting the request; the F1 refusal leaving the session running; a refused manifest
  stopping every backend path; and a programming error reaching the error surface rather than the crash
  handler.
- **`HlsLoadErrorPolicyTest`** (new, 5 cases): 503 honors `Retry-After` and outlasts Media3's backoff
  through the whole capacity budget; every other failure keeps Media3's own delay and give-up point;
  fail-fast classifications stay immediate; the retry count covers the capacity budget.
- `TrackOptionsTest` (+6): the HLS helpers that were previously untested, including the `sub:<n>`
  format-id round trip through a track list mixing image-based and text subtitles — the case the id
  prefix exists for.
- `MoviePlayerScreenTest` (+4): a refused quality leaving playback and the saved request alone and the
  refusal not surviving the visit; an accepted switch clearing a standing refusal; an effective profile
  never rewriting the requested mode; and no compounding rewind across two background trips.

---

## Verification

- `./gradlew testDebugUnitTest` — **635 tests, 0 failures**.
- `./gradlew lintDebug assembleDebug` — clean.
- `./gradlew connectedDebugAndroidTest` on the Shield — **305 tests, 0 failures** (10 skipped, as before).

### Device readiness audit (before the pass)

1920×1080 override @ 320dpi, manual surround with AC3 / DD+ / DTS / TrueHD / Atmos enabled, 9.3 GB
free, backend reachable directly over `tun0`, display awake and held awake.

### Driven by hand on the Shield, against the live backend

Test title: **Shutter Island** (h264 1080p, 3 audio tracks, 2 PGS subtitles, resume at 14:03).

1. **Remux start from a resume point** — the session started at **13:53**, exactly the requested
   14:03 minus the 10-second rewind, and the seek bar showed the movie's full **2:18:04**, not the
   session window. First frame arrived without a black screen.
2. **Effective vs. requested profile** — the Quality menu marked **1080p — best quality** while the
   request stayed Remux: the backend's remux safety gate forced a transcode and the mark reported what
   actually ran. Entry focus landed on the marked row.
3. **Quality switch mid-playback** — Remux/1080p → **720p** swapped the source in place, kept the
   position, kept the dialog up, and moved the mark.
4. **HLS audio switch** — the Audio menu listed all three wire tracks (the mux carries only one, so
   these can only come from the wire summaries); switching English → Spanish created a new session at
   the same position and playback continued through it.
5. **Seeks** — an in-window backward seek landed instantly with no rebuffer; a far-forward jump
   (17:43 → 34:54) rebased and played; a seek back to 0:00, before the session's start, rebased and
   played from the opening.
6. **Chapters on a rebased session** — the active chapter tracked the absolute timeline correctly at
   every position, including inside sessions whose media starts mid-movie.
7. **Background and return, twice** — Home, waited past the 120-second keepalive, returned: the player
   overlay survived, a fresh engine and session prepared at **1:06**, **paused**, with a frame on
   screen. A second trip returned to **1:06** again — not 0:56. This is F3, confirmed on hardware; before
   the fix each trip cost another ten seconds.
8. **Image-based subtitles correctly absent** — both of this title's subtitle tracks are PGS, which
   cannot be sideloaded as WebVTT, so no Subtitles button appeared. Correct, not a gap.

### Not yet driven on hardware

- The **F1 Direct refusal** end to end. It needs the Shield's passthrough temporarily disabled to make
  the device genuinely unable to play TrueHD; the app's PIN gate re-armed after the force-stop that
  set that up. Covered by `ExoMoviePlayerEngineTest` and `MoviePlayerScreenTest`.
- **Text subtitles on a rebased session** (cue alignment after a mid-movie start). Shutter Island's
  tracks are both image-based; this needs a title with SRT tracks.
- **Passthrough sanity on Direct** for a TrueHD/Atmos title, via the DIRECT output thread's processing
  format in `dumpsys media.audio_flinger`. Nothing in this branch touches `DefaultAudioSink`'s
  negotiation, but it is worth confirming.
- **Progress-reporting cadence** across an HLS session (15s threshold, ~30s first periodic report).

---

## Remaining risks

- The engine's instrumented tests deliberately point every URL at TEST-NET, so they assert
  orchestration — which choices become a new session, which are answered without one — not decoding.
  Frame-accurate offset behavior is only covered by the hand-driven pass above.
- The capacity path (**503 + `Retry-After`**) has never been exercised against a genuinely busy server;
  its rules are unit-tested, but the narration and the 90-second ceiling have not been seen for real.
- Session-lost recovery (**segment 404** mid-play) likewise has unit coverage but no hardware
  reproduction; it needs a server-side eviction to trigger.
