# Movies screen — status after the tabs pass (2026-09-02)

Plain-language record of where the Movies screen stands after replacing the filter chip row
with a tab control, the way the web app's movies page is organised.

## What was done

**Tabs instead of chips.** The single row of chips (All · Liked · one per genre) is now a strip
of three tabs — **All Movies · Genres · Liked** — sitting between the header and the grid. This
is the web page's shape (All Movies · Genres · Playlists), with Liked standing in for Playlists
until playlists have a TV screen of their own. The strip is fixed and never scrolls.

**Tabs switch on focus.** Landing the d-pad on a tab is the switch — no press needed, the way
Android TV's own tabs behave. Sliding from Liked back to All passes over Genres, which briefly
loads and is immediately superseded; the view model cancels that request and ignores any late
response, so nothing wrong can land. A press on a tab also selects it, which is how TalkBack
activates it and how you retry after a failed switch.

**The Genres tab has a picker.** Under the strip, one chip per genre with its count ("Action ·
146"). Press a chip to see that genre's movies. The tab remembers the last genre you chose, so
coming back lands on it; if the genre list is refreshed and the genre was renamed, the chip
follows the new name, and if it vanished the first genre is chosen instead. If the genre list
can't be loaded at all, the tab shows a short placeholder ("Genres aren't available right now.
Refresh to try again.") that still takes focus, and the moment the list arrives the first genre
is picked and shown.

**Focus was the tricky part, and it's handled.** Every content replacement used to pull focus
into the grid — right for Refresh, Sort and chip presses, but fatal for a tab that selects on
focus: you could never get past the first tab. A switch that lands while you're standing on a
tab now leaves focus there (the grid still returns to the top, so one press down is the first
card). Refresh and Sort go *down* to the selected tab specifically, so a diagonal press can't
accidentally switch sections.

**Everything is tested and verified.**
- 51 JVM tests on the movies view model (9 new: tab selection, remembered genre, renamed genre,
  empty-genres placeholder, auto-select when genres land, superseded pass-over fetch, tab and
  genre revert on failure) — all green.
- 71 instrumented tests on the emulator across the behaviour and accessibility suites (about 20
  new or rewritten for the strip, the picker and the placeholder) — all green.
- Verified end-to-end on the Shield against the live server: the strip renders, focusing Genres
  shows the picker and Action's grid with focus staying on the tab, pressing Adventure swaps the
  grid and lands on its first card, Liked shows the two liked movies, sliding back to All over
  Genres lands cleanly on the full library, Genres remembers Adventure, and Refresh → down
  returns to the selected tab. Screenshots taken at every step.

**Docs updated.** design-system.md §11.4 was rewritten for the tab model and its focus contract,
§9.1 documents the new `IglooTabRow` / `IglooTab` primitive, §9.2 records why tv-material's
`TabRow` was not adopted, and the changelog has the entry.

## What remains

- **Server-down revert on a tab switch** was verified by unit test only (the live server on the
  tailnet can't be cut from the Shield without cutting adb). The rule: the grid stays, the tab
  and genre snap back, the notice reports, and a press on the still-focused tab retries.
- **Playlists** — still out. When it has a screen, it becomes the third tab and Liked moves
  inside it, as on the web.
- **Search** and **Request Movie** — unchanged from the previous pass.
- **Anything needing richer server support** — watched/year filters, other sort fields — still
  blocked on backend work.
