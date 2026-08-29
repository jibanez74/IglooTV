# Movies screen — status after the filters & sort pass (2026-08-29)

Plain-language record of where the Movies screen stands after this pass, which brought it to
feature parity with the web app's movies page (adapted for TV).

## What was done

**Sort toggle.** The header gained an A–Z ⇄ Z–A button next to Refresh. It only flips
direction because that is all the server supports — there is no sort-by-year or by-rating on
the backend. The button never disables, reserves the width of both labels so it doesn't jump
when pressed, and tells TalkBack its current order and what a press will do.

**Genre filtering.** A chip row now sits between the header and the grid: All · Liked · one
chip per genre with its movie count ("Action · 145"). Selecting a chip swaps the grid to that
genre's movies, keeps the current sort direction, and shows that view's own count. The genre
list loads alongside the library; if that fetch fails the row quietly shows just All and Liked
until a later refresh — no error card for something that decorative.

**Liked movies view.** The Liked chip shows everything you've liked. Liking and unliking still
happens on a movie's details page — and a shown Liked grid now quietly updates itself when you
toggle a like there, so coming Back never shows a stale list. Each view has its own empty
message ("No liked movies yet…", "No Action movies in your library.").

**Failure behavior.** If switching filters or sort fails (server unreachable), the grid you
were looking at stays put, the chip selection snaps back to match it, and the failure is
reported as a one-line notice by the header — same contract Refresh already had.

**A real bug found and fixed during on-device verification.** Unliking the *only* liked movie
and pressing Back used to dump focus onto the navigation rail instead of the Movies pane. Root
cause: Back tries to restore focus to the card you came from; that card no longer existed, and
Compose reports that focus request as "fine" while doing nothing. The pane's card-less states
(loading, error, empty) now also carry the Back-return anchor, so Back always lands inside the
pane. There's a regression test for it.

**Everything is tested and verified.**
- 36 JVM tests on the movies view model (12 new: filters, sort, revert-on-failure, silent
  liked reconcile, count rules) plus 2 new details-view-model listener tests — all green.
- 46 instrumented tests on the emulator (10 new focus/behavior, 5 new TalkBack) — all green.
- Verified end-to-end on the TV emulator against the real backend: sort flip, genre paging
  (network requests confirmed hitting the right endpoints), the full like → Liked → unlike
  round trip, and the server-down failure path. Screenshots taken at every step.

**Docs updated.** design-system.md §11.4 was rewritten (it previously *forbade* sort and
filters on this screen), §9.1 documents the new `IglooFilterChip` primitive, and the changelog
records the pass including the focus bug.

## What remains

- **Playlists** — deliberately left out of this pass. The server supports browsing and even
  adding movies to playlists, but the web app itself can't add movies to playlists yet, so TV
  would be building ahead of web. Needs its own screen and its own pass.
- **Search** — its own destination in the spine, still a placeholder. The design system
  already sketches it (§11.6); the movies search endpoint is ready on the server.
- **Request Movie** — skipped as desktop-shaped (free-text TMDB search). Could become a
  voice-input flow later if wanted.
- **Shield sanity pass** — everything was verified on the emulator; the chip row's d-pad
  edges (first-chip-left, last-chip-right) deserve a one-minute check on the Shield next time
  it's convenient, since it's the primary real device.
- **Anything needing richer server support** — watched/year filters, other sort fields,
  progress bars on library cards — all blocked on backend work first; the API only offers
  title-direction sort and single-genre filtering today.
