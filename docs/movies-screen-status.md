# Movies screen — status after the tabs pass and its review (2026-09-02)

Plain-language record of where the Movies screen stands after replacing the filter chip row
with a tab control, the way the web app's movies page is organised, and after the review pass
that followed it.

## What was done

**Tabs instead of chips.** The single row of chips (All · Liked · one per genre) is now a strip
of three tabs — **All Movies · Genres · Liked** — sitting between the header and the grid. This
is the web page's shape (All Movies · Genres · Playlists), with Liked standing in for Playlists
until playlists have a TV screen of their own. The strip is fixed and never scrolls.

**Tabs switch on focus.** Landing the d-pad on a tab is the switch — no press needed, the way
Android TV's own tabs behave. The highlight moves the instant you land; the actual load waits a
third of a second, so sliding from Liked back to All no longer fires a throwaway request for
Genres on the way past, and the Refresh button stops flashing "Refreshing…" for a tab you were
only crossing. A press on a tab selects it immediately, skipping that wait — that is how
TalkBack activates it, and how you retry after a failed switch.

**The Genres tab has a picker.** Under the strip, one chip per genre with its count ("Action ·
146"). Press a chip to see that genre's movies. The tab remembers the last genre you chose, so
coming back lands on it; if the genre list is refreshed and the genre was renamed, the chip
follows the new name, and if it vanished the first genre is chosen instead. A genre list that
comes back *successfully empty* is believed, and clears the remembered choice; a list that fails
to load leaves the last one you saw alone.

**Waiting for genres no longer looks like a failure.** If you land on Genres before the list has
arrived, the tab shows the ordinary loading skeleton. Only once the request has actually
finished — empty, or failed — does it switch to the short placeholder ("Genres aren't available
right now. Refresh to try again."). Pressing Refresh there now visibly says "Refreshing…" while
the genre list is re-read, because that round trip is the only request the press makes; Sort
there does nothing on purpose, since flipping the label with no list to sort would have the
header claiming an order the hidden grid was never put in.

**Focus was the tricky part, and it's handled.** Every content replacement used to pull focus
into the grid — right for Refresh, Sort and chip presses, but fatal for a tab that selects on
focus: you could never get past the first tab. A switch that lands while you're standing on a
tab now leaves focus there (the grid still returns to the top, so one press down is the first
card). Refresh and Sort go *down* to the selected tab specifically, so a diagonal press can't
accidentally switch sections.

**Four bugs the review found and fixed.**
- **A request you had already left could yank you back.** Pressing Refresh on All and then
  moving onto Genres before its list had loaded left that Refresh still running. When it failed,
  it snapped the selection back to All out from under the tab you were standing on, and reported
  an error about a request you never made. Leaving a list now cancels its page first.
- **"Genres aren't available right now" while they were still loading.** See above — the tab
  could not tell "not asked yet" from "there are none", and said the failing thing for both.
- **Refresh and Sort did nothing, silently, on that placeholder** — while the placeholder's own
  text tells you to refresh.
- **A failed page could revive a deleted genre.** If the genre list came back empty while a page
  request was out, the failure put the old genre back — leaving the tab showing that genre's
  movies with no chip anywhere to change it.

**Everything is tested and verified.**
- 65 JVM tests on the movies view model (12 new for the review: the debounce and the deliberate
  press, the two genre waiting states, the placeholder's Refresh and Sort, the superseded page,
  the untouched focus generations, the append cursor after a failed switch, an unchanged genre
  list, and the dropped-genre revert) — all green.
- 87 instrumented tests on the **Shield** across the behaviour, accessibility and new
  `IglooTabRow` suites — all green. New: the tab strip's own component test (select-on-focus,
  the press, `Role.Tab`, selected-only-when-true, the action label), Sort's way down to the
  selected tab, a reverted tab keeping focus while the grid still reaches the strip, the strip
  surviving every grid state, the picker returning with the remembered chip anchored, the
  anchor's fallback chip, and both genre waiting states.
- Verified end-to-end on the Shield against the live server: the strip renders, focusing Genres
  shows the picker and Action's grid with focus staying on the tab, pressing Adventure swaps the
  grid and lands on its first card, Liked shows the two liked movies, sliding back to All over
  Genres lands cleanly on the full library, Genres remembers Adventure, and Refresh → down
  returns to the selected tab. Screenshots taken at every step.

**Tidied while in there.** `IglooTab` had been copied out of `IglooFilterChip` almost line for
line; both now sit on one shared `SelectablePill` body, with the five real differences as
parameters. The tab strip's three private alpha constants went back to inline values, the way
every other control writes them, and the strip's `0.50f` ground joined the design system's alpha
table it had been missing from. The tab's three parallel `when` blocks (label, spoken label, test
tag) became one lookup, and the focus tracker's "is a tab focused" flag became derived rather
than a second flag written from five places.

**Docs updated.** design-system.md §11.4 was rewritten for the tab model and its focus contract,
§9.1 documents the `IglooTabRow` / `IglooTab` primitive and the shared pill, §9.2 records why
tv-material's `TabRow` was not adopted, §3.1 gained the strip's alpha, and the changelog has both
entries.

## What remains

- **Server-down revert on a tab switch** was verified by unit test and by an instrumented test
  that feeds the reverted state directly, but not against a genuinely unreachable server (the
  live server on the tailnet can't be cut from the Shield without cutting adb). The rule: the
  grid stays, the tab and genre snap back, the notice reports, and a press on the still-focused
  tab retries.
- **Polish, duplication and coverage gaps the review recorded but did not fix** — the count line
  not naming the active view, the selected genre chip not scrolling into view, three more copies
  of the selectable-control recipe, and the remaining test gaps — are all written up in
  [`cleanup-backlog.md`](cleanup-backlog.md).
- **Playlists** — still out. When it has a screen, it becomes the third tab and Liked moves
  inside it, as on the web.
- **Search** and **Request Movie** — unchanged from the previous pass.
- **Anything needing richer server support** — watched/year filters, other sort fields — still
  blocked on backend work.
