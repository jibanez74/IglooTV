# Album Details screen — status

*Pass finished 2026-08-31, on the `dev` branch (uncommitted).*

## What was done

**The screen.** Clicking an album on the Home "Recently Added Albums" rail now opens a full
album details screen, mirroring the web app's album page. It shows the album cover blown up as
a full-bleed backdrop (with the same scrim treatment the movie pages use), the square cover
beside the title, artist, chips for release date / track count / total duration, the genre
line, the Spotify popularity meter (brand-green bar and glyph), and Play Album + Shuffle
buttons. Below that: a display-only Artists row, the track list (index, title, genre tags,
duration per row, grouped under "Disc N" headers for multi-disc albums), and an "Album
Details" facts panel — release date, totals, artist, genres, disc count, an audio-quality
summary like "FLAC · 900 kbps · stereo", and the popularity score. Fields the server doesn't
have are simply dropped, never rendered blank.

**Navigation and focus.** The overlay joins the same details slot the two movie pages use:
Back closes it and lands focus on the exact album card that opened it, the loading skeleton
holds entry focus where Play Album appears so nothing jumps when the data lands, and the
d-pad chain is fully wired — actions → every track row in order → facts panel, with no
direction able to escape into the shell underneath. TalkBack reads each track row as one
sentence (disc number folded into each disc's first row), the hero as one summary sentence,
and the facts panel as one announcement.

**Data layer.** The album-details API response is now fully typed (it was raw JSON blobs),
with a new repository call for `GET /music/albums/details/{id}`. One trap dealt with: this
endpoint reports durations in **milliseconds** while the tracks-list endpoint uses seconds —
conversion happens once, at the mapping edge, and is pinned by tests.

**Deliberate scope choices (agreed up front):**
- ~~Play Album and Shuffle are real, focusable buttons that **do nothing yet**~~ — **Play Album
  is now live** (2026-09-01, see `music-player-status.md`); Shuffle stays a stub for its own
  branch. Track rows are still one focus stop each, no play/like/overflow buttons yet.
- An album with zero tracks shows no Play/Shuffle at all (web parity).
- Artist chips are display-only — there's no musician screen to open yet.

**Tests.** All passing: serialization and repository tests for the new wire models, a full
ViewModel/mapping unit suite (disc grouping, audio-quality edge cases, ms formatting, error
and refresh behavior), and two new on-device suites (focus contract, TalkBack contract) plus
a reworked home-rail test now that album cards are clickable.

**Verified end-to-end** on the TV emulator against both the local dev backend and the live
server (through a temporary relay): open album → details render with real data → walk the
whole list → facts panel → Back restores the card. Audio quality showed "AAC · 291 kbps ·
stereo" from real scans; the cover-as-backdrop hero rendered with live Spotify artwork.

**Docs.** design-system.md gained §11.5.1 ("The album detail screen, as built") and a
changelog entry, including the recorded deferral of §11.5's three-action track row.

## What remains

- **Playback wiring** — ~~Play Album~~ done (2026-09-01, the music player pass —
  `music-player-status.md`). Still open: Shuffle, per-track play, and the like toggle and
  overflow menu §11.5 promises on each row.
- **A Music destination screen** — the spine's Music tab is still a placeholder; the albums
  rail on Home is currently the only way into an album.
- **Musician details** — the artist chips stay inert until a musician screen exists.
- **Not visually exercised on real data**: the popularity meter and multi-disc headers — no
  album on either server has a Spotify score or a second disc. Both are pinned by the
  on-device tests with fixtures, so they'll light up when the library has such an album.
- The library albums on the local dev server have no covers, so the glyph-fallback path is
  what you see there; the live server exercises the artwork path.
