# Chapter menu & player menus — status (2026-08-24)

## What was done

**Reviewed the previous commit (4cf01aa, "Fix playback progress isolation and restoration") — it is correct.** The per-movie save-session isolation does exactly what the server's upsert
rule needs (movie A's failed save survives movie B's success and retries with A's original
session id and a higher sequence), and the new `playWhenReadyIntent` keeps a recreated player
from autoplaying a movie the user had paused while still preserving the choice across
configuration changes. The four new ViewModel tests and two restoration tests genuinely pin
those behaviors. No changes were needed.

**Confirmed the in-player audio track menu already existed and works.** The "Audio" button
(shown when the stream has two or more audio tracks) opens the track dialog and switches
tracks live through ExoPlayer. Verified on the Shield against the live server with Shutter
Island (3 tracks): the selection mark moved from "English · Stereo" to "English · Surround"
with the menu staying open and playback continuing. Nothing was changed here.

**Built the chapter selection menu** (the genuinely missing piece):

- Chapters now travel from the already-fetched technical details into `MoviePlayRequest` as a
  lean `PlaybackChapter(title, startTimeSec)` list, sorted by start time, and survive activity
  recreation through a JSON slot in the request saver.
- A text-word **Chapters** button sits between Forward and Audio (the §11.8 order), shown only
  when a movie has two or more chapters — present from first composition, since it comes from
  the request rather than the engine.
- The menu follows the same modal recipe as the track menus: radio rows labeled by title (with
  "Chapter N" standing in for blank titles), a trailing start timecode, TalkBack sentences in
  spoken time words ("Chapter 3 of 16, Chapter 03, starts at 18 minutes 38 seconds"), the
  current chapter marked live and holding entry focus.
- **Selecting a chapter seeks and closes the menu** — a deliberate departure from the track
  menus' stay-open rule (a jump's result is the picture behind the scrim), with focus
  returning to the Chapters button. Documented in design-system §11.8 + changelog.
- `IglooRadioRow` gained optional `detail` (trailing muted text) and `semanticLabel` params.

**Fixed a bug found during hardware verification:** if playback errored while a menu was
open, the menu stayed composed over the error surface and D-pad input went to the Retry
button hidden underneath it. A playback error now dismisses any open menu so Retry is
visible and focused. (Applies to the audio/subtitle menus too — the bug predates this pass.)

**Tests** (all green: full JVM suite, `./gradlew build`, and 31 + 5 connected tests on the
Shield):

- `ChaptersTest` — active-chapter lookup, label fallback, spoken-label rules.
- `MoviePlayRequestMappingTest` — chapters sorted/converted, empty cases.
- `MoviePlayerScreenTest` — button gating, request-driven visibility, menu entry focus and
  labels, seek-dismiss-refocus, Back without seeking, transport-key inertness, and
  error-dismisses-menu. The focus-walk helper no longer hard-codes the button order.

**Hardware verification** on the Shield against the live server: chapter jump on Shutter
Island landed exactly on Chapter 3 (13:07), menu closed, focus restored; a movie's chapters
appear before the engine reports tracks; the resume prompt, exit save, and back-navigation
focus restoration all behaved along the way.

## What remains

- **Seek-bar chapter tick marks** (and quality chip, volume controls) — still deferred scope
  from the direct-playback pass.
- **Streaming robustness over the tailnet**: Uncut Gems (4K HEVC remux) produced a
  `ERROR_CODE_TIMEOUT` and, once, a Matroska extractor failure ("No valid varint length mask
  found") after seeking — the player stayed frozen for a long while before the timeout
  surfaced. Likely the tailnet link or backend range serving under a high-bitrate stream, not
  an app regression (1080p Shutter Island streamed and seeked cleanly), but worth a look at
  the backend side and at whether the player should surface a stall sooner.
- The changes are uncommitted on `feature/direct-playback` — review and commit when ready.
