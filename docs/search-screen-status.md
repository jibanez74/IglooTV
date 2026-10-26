# Search screen — status after the first pass (2026-10-26)

Plain-language record of where the Search destination stands after the `feature/search`
branch: what was built, what was verified and how, and what is deliberately left.

## What was done

**The spine's Search row is a real screen.** A heading with the result count and a Clear
button, the query field, a six-tab strip — **All · Movies · TV Shows · Albums · Musicians ·
Tracks**, the five categories the server searches — and the selected tab's results. Selecting
Search from the spine lands on the field and the on-screen keyboard appears; its microphone is
the voice input. Results update while you type, 400ms after the last key, so a word costs one
request. The keyboard's Search key sends the query at once, hides the keyboard and puts focus on
the first result. Clear empties everything and hands focus back to the field.

**All shows every category at once.** One rail per category with a match, titled with its full
count ("Movies (404)"), the top eight as that category's own card — poster, square cover, circle
— and a **See all** card at the end when more exist. Tracks are rows with Play, Like and More and
a See-all button. Pressing See all switches to that category's tab and lands on its first card.

**Category tabs are the library and music panes' own surfaces.** The same infinite-scrolling
grids and the same track list, 48 per page, over the search rows. A tab you slide across on the
way to another never fires a request; each tab keeps its pages for the query last sent, and a
new query forgets them all and fetches only the tab you are on.

**Results go where they go elsewhere.** A movie, show, album or musician opens its page and Back
returns to the card. A track row's Play queues the rows on screen from that one — the Tracks
tab's loaded pages or the All view's eight — and closing the player lands on the row. Like and
More behave as on the Music pane. A Home round trip keeps the query, the tab, every loaded page,
the scroll positions and the item you were on.

**Shared pieces, not copies.** The Music pane's paging machine became `PagedLoader`
(`feature/shared`), which Search's six tabs now use too; the track list and the musician card
moved out of the Music screen so Search draws them as-is; `IglooTextField` grew a leading icon, a
spoken-only label, a modifier for its input node and an empty-field left exit. The spec's §11.6
was rewritten to the built behaviour.

## How it was verified

JVM, `./gradlew :app:testDebugUnitTest`: **1,130 tests pass**, including the new
`SearchViewModelTest` (19: blank query, debounce, Search-now, stale and late answers dropped, tab
laziness, paging, retry, play queues), `SearchMappingTest` (11), `SearchRepositoryTest` (4), the six search
routes in `OpenApiContractTest`, two decode cases in `ApiModelsSerializationTest`, and the
unchanged `MusicViewModelTest` over the lifted loader. `:app:lintDebug`: no warnings in any file
the branch touches.

Instrumented, Google TV emulator (`Google_TV_API_34_1080p`, API 34, 1080p, `emulator-5554`),
`./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`:
`SearchPaneBehaviorTest` **28/28**, `SearchPaneAccessibilityTest` **10/10**, and the touched
suites `MusicTabsBehaviorTest`, `TracksListFocusTest`, `TracksListAccessibilityTest`,
`NavigationRailBehaviorTest`, `MusicPlayerOverlayFocusTest`, `MoviesGridBehaviorTest` and
`ShowsGridBehaviorTest` (the shared focus coordinator changed) all green. The full connected run
was not repeated; the gate suites are untouched.

Live, same emulator, the local backend at `http://10.0.2.2:8080` (425 movies): entering Search
from the spine opens the keyboard on the field; "casino" showed three results while typing; the
keyboard's Search key landed on the Casino Royale card; Down walked Movies → Musicians → Tracks;
Play opened the music player and Back closed it onto the row; the card opened the movie page and
Back returned to it; re-selecting Search from the rail kept the query; "the" (1,143 results)
showed the See-all card after eight movies and opened the Movies grid on its first card with the
tab a press above; the last section clears the overscan inset.

TalkBack 14.2 on the emulator (verbose speech log): the rail row says "Selected. Search"; the
field says "Editing. Edit box. Search movies, TV shows, albums, musicians and tracks" and the
keyboard follows; the count line speaks once per settled search ("3 results for casino: 1 movie,
1 musician, 1 track."; the "Searching for …" line heard during that pass was since silenced so a
search is one announcement, not two); the keyboard's Search key reads "Movies (1). Casino Royale, 2006"; Down
reads "Musicians (1). Nicola Fasano & Pitbull. 1 album, 1 track." then "Tracks (1). You Know My
Name…", Right "Not liked. Like" and "More actions for …"; the See-all card reads "See all 404
movies" and its press "Movies", "Showing 48 of 404 movies", "All results. Tab" (focus parks on
the strip for the announced wait, so the disposed card never falls to the rail), then the grid's
first card; the
Tracks tab reads "Showing 48 of 668 tracks" and its rows' sentences.

## What could not be verified

- **Shield and Fire TV.** Emulator only, by request; the Shield pass and the Fire OS pass (VoiceView,
  the Fire keyboard's Back) come later.
- **A TV keyboard owns the d-pad while it is up.** With the keyboard showing, Up/Down/Left/Right
  move inside the keyboard, not the pane; Back or the keyboard's Search key dismiss it and the
  field's wiring (up to Clear, down to the strip) applies from then on. This is the platform's
  behaviour and the login screens share it; it is not something the pane can change.
- **The live count line while typing.** Every settled search re-announces the count. Audible and
  short on the emulator; judge the cadence on the Shield.
- **A one-off on the first live pass**, Back out of the music player opening the rail instead
  of landing on the row, was traced by the review to the All view's track rows remembering
  their focus wiring without the return requester among the keys, so the requester could sit on
  no node after the d-pad had walked into the column. Fixed (the keys now name every requester)
  and pinned by `walkingDownIntoTheTracksAndPlayingClosesBackOntoThatRow`; not re-driven live.

## What the review changed

An independent review of the diff found the track-row wiring above, a narrow window where a new
search replacing rows under a focused card left focus to the platform (the shared focus
coordinator now hands a replaced surface's focus to the skeleton anchor, and the loader counts
the replacement as a generation), a section with a count but no rows (not drawn now), the count
line speaking twice per search, an empty tab's count echoing the empty box, a loader parameter
nobody passed, and the four All-view rails spelled out in full; all taken.

## What remains

- Recent searches and suggestions: the server offers neither; nothing to draw.
- Episodes and people: the server's search covers the five categories only.
- The All view's tracks heading folds into the first row at mapping time, like the Music tab's
  letter headers, so a return that lands on a later row does not hear it. The rails say theirs
  on whichever card focus enters on; the column could do the same at compose time.
- The cursor keys: with text in the field, left and right move the cursor, so the spine is
  reached through the strip or with Back (§11.6 records this).
