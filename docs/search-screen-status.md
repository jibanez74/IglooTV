# Search screen — status after the first pass (2026-10-26)

Plain-language record of where the Search destination stands after the `feature/search`
branch: what was built, what was verified and how, and what is deliberately left. The
`feature/voice-search` branch (same day) is recorded at the end.

## What was done

**The spine's Search row is a real screen.** A heading with the result count and a Clear
button, the query field, a six-tab strip — **All · Movies · TV Shows · Albums · Musicians ·
Tracks**, the five categories the server searches — and the selected tab's results. Selecting
Search from the spine lands on the field and the on-screen keyboard appears; the header's Voice
search (below) opens the platform's recogniser. Results update while you type, 400ms after the last key, so a word costs one
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

**Nvidia Shield (2026-10-26)**, Android 11, 4K output, Gboard TV, the LAN library server (404
movies, 667 tracks match "the"). The two Search suites first, as the install:
`SearchPaneBehaviorTest` **28/28** and `SearchPaneAccessibilityTest` **10/10**, re-run with the
touched grid, track-list and music-tab suites after the fixes below: `SearchPaneBehaviorTest`
28, `SearchPaneAccessibilityTest` 10, `MoviesGridAccessibilityTest` 21,
`ShowsGridAccessibilityTest` 11, `TracksListAccessibilityTest` 7, `MusicTabsBehaviorTest` 8 —
**85/85** on the Shield. Then the live drive, TalkBack off: Search from the spine lands on the field with
Gboard up; the keyboard owns the d-pad (arrows move q → e → d), Back closes it on the field, Left
from the empty field opens the spine and Right comes back with the keyboard; "casino" typed shows
"4 results" while the keyboard stays; the keyboard's Search key hides it and lands on the Casino
Royale card; Down walks Movies → Musicians → Tracks with the bottom pinned on the row's Play;
Play opens the music player as "Track 1 of 1", pause, Back lands on that Play; More opens Go to
album / Go to artist and Back returns to More; the musician and the movie open their pages and
Back lands on each card; Clear from the field's Up empties the pane onto the field. "the" shows
"1,144 results" with the Movies rail ending in **See all** / "404 movies"; its press selects
Movies, says "404 movies" and focuses the grid's first card with the tab a press above; sliding
Right across Musicians (18) to Tracks (667) selects each; the Tracks tab paged 48 → 96 on the way
down with no skipped frames (`Choreographer` silent through the whole drive); a Home round trip
kept "the", the Tracks tab, 96 rows and the focused row; a no-match query drew "No tracks match
…" with Down from the field landing on the tab and then the box. 4K crops of the field, strip,
circles and track row are crisp.

TalkBack 14.2 on the Shield (verbose speech log, `action=SPEAK`): the spine says "Selected.
Search. Button"; the field "Editing. Edit box. Search movies, TV shows, albums, musicians and
tracks" then Gboard; letters echo as typed; the settled count once ("4 results for casino: 1
movie, 2 musicians, 1 track."), and once per letter when "the" is typed with a second between
keys ("1,742 results for t…", "1,209 results for th…", "1,144 results for the…") — the cadence
the emulator pass left open is one short sentence per settled search, nothing while the
debounce runs; the Search key "Out of keyboard row 1. You Know My Name…" and "keyboard hidden";
Right "Not liked. Like. Button" and "More actions for You Know My Name…"; tabs "selected.
Movies. Tab" then "Showing 1 of 1 movie", "0 TV shows", "0 albums", "4 results for casino…"
back on All; Clear "Clear the search. Button" then "Cleared text" and the field. Before the fix
below, the count line also reached TalkBack as five identical requests within 150 ms of each
settled search (one per content change on its node), which TalkBack's duplicate filter folded
into one utterance; after it, one request.

Two defects, both fixed on the branch and re-driven:

1. **Clear kept the hidden tab.** Clear (or a query trimmed to blank) emptied every tab but left
   the selected category in place under the prompt, which draws no strip; the next query then
   searched that category — "casino" after a Clear on Tracks answered "Showing 1 of 1 track"
   with no Movies rail. `clearResults` now returns to All (`SearchViewModelTest` pins it) and
   §11.6 says so.
2. **The previous count re-read on every tab switch.** Right onto Movies spoke "selected.
   Movies. Tab", then "4 results for casino…" again, then "Showing 1 of 1 movie"; every tab
   switch repeated the line of the tab just left. The header's count was one node carrying the
   visible text and the spoken description with a polite live region: while a request is out
   the visible text becomes "Searching…" and the spoken line deliberately keeps the count it
   replaces, and TalkBack re-reads a live region on any content change of its node — the text
   change and the layout change both fired. The spoken line now rides a fixed 1dp node of its
   own beside the visible text (`PaneHeader`, so the Movies, TV Shows and Music panes gain it
   too); `SearchPaneAccessibilityTest` pins that the live node has no text while "Searching…"
   shows. On the emulator the request landed inside the same frame, which is why the pass there
   heard it once. Re-driven on the Shield: "selected. Movies. Tab" then "Showing 1 of 1 movie",
   "selected. TV shows. Tab" then "0 TV shows", "selected. Albums. Tab" then "0 albums" — one
   request each; and a Clear from the Albums tab followed by "casino" answers "4 results for
   casino: 1 movie, 2 musicians, 1 track." on All.

## What could not be verified

- **Fire TV.** The Fire OS pass (VoiceView, the Fire keyboard's Back) comes later.
- **Voice input.** Gboard's microphone cannot be driven over adb; the key is there and focusable.
  The app's own Voice search is covered in its own section below.
- **A TV keyboard owns the d-pad while it is up.** Confirmed on the Shield with Gboard TV: with
  the keyboard showing, Up/Down/Left/Right move inside the keyboard, not the pane; Back or the
  keyboard's Search key dismiss it and the field's wiring (up to Clear, down to the strip)
  applies from then on. This is the platform's behaviour and the login screens share it.
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
- The See-all card draws the category's fallback glyph, the same film strip a movie with no
  poster shows; at 4K the two are told apart only by the caption. A glyph of its own (an arrow,
  or the count large) would read as an action at a glance.
- A musician the server returns with "0 albums, 0 tracks" (a featured-artist credit) opens an
  empty page; the server's search ranks it, the pane only shows it.

## Voice search (`feature/voice-search`, 2026-10-26)

**What was done.** A **Voice search** button in the Search header, left of Clear and one press
Up from the field, listens through the platform's speech recogniser bound from the app itself
(`SpeechRecognizer`; the app declares `RECORD_AUDIO` and asks for it on the first press). While
it listens the button reads "Listening…" (its action "Stop listening"), the header's notice line
says "Listening… say a title, album, musician or track", and each partial guess is written into
the field. A recognised phrase replaces the field's text, goes out at once and lands focus on the
first result, as the keyboard's Search key does; Back or the button again stops silently and
restores the field and the search it was waiting for; no match, a refused microphone or a recogniser error is one line in the
header's notice slot ("Didn't catch that. Try again.", "Couldn't search by voice: …"), cleared
by the next keystroke, Clear or attempt. The button is composed only where a recogniser answers
— the Shield and Google TV, not a Fire TV. The remote's own microphone key belongs to the
platform (Assistant, Alexa) and cannot be taken by an app; that was the first question answered
before the design. Spec: design-system.md §11.6 "Voice search".

**Why not the "Speak now" overlay.** The first build launched `ACTION_RECOGNIZE_SPEECH`, which
needs no permission of the app's own. It works on the Google TV emulator (after pinning the TV
search app's handler, because the image carries two and shows a chooser), but on the Shield's
Android 11 build the overlay's recognition service refuses every caller with "call for
recognition service without RECORD_AUDIO permissions" — the shell and an app holding the
permission alike — and closes at once. A recogniser bound from the app's process is checked
against the app's own grant and listens, which is also what Leanback's search and Kodi do on
the Shield; the design was switched that day.

**How it was verified.** JVM, the search package **54/54**: `VoiceSearchOutcomeTest` (8: every
error code and every notice line) and `SearchViewModelTest` now 30 (a voice result fills the
field and searches at once, partials never search, a phrase replaces a query not yet sent and
never repeats an answered one, a blank one is ignored, no match restores the field and leaves a
notice the next keystroke clears, a cancel clears the notice, Clear drops it). Instrumented,
Google TV emulator: `SearchPaneBehaviorTest` **37/37** (the chain through Voice search, the
no-recogniser layout, the press, the listening button and the field showing each guess, Back
while listening, a recognised phrase landing on the first card, a cancel, a no-match notice) and
`SearchPaneAccessibilityTest` **12/12** ("Search by voice" / "Stop listening", the listening
line and a no-match notice as the header's polite live region over a like notice).

Live, Google TV emulator (API 34, the local backend): Search from the spine, Back closes the
keyboard, Up lands on Voice search; a press shows the permission dialog once, then "Listening…";
the emulator has no microphone, so it ends in "Didn't catch that. Try again." with focus on the
button, and Back while listening returns to the plain button with no notice.

Live, Shield (Android 11, the remote's microphone, a person speaking): the permission dialog,
"Listening…", and — once the device's microphone path was repaired, next paragraph — "Casino"
spoken into the remote put "Casino" in the field, "3 results for “Casino”" in the heading and
focus on Casino Royale; a Center press from there opened its page, so the landing is the real
first card.

**A Shield finding that is not the app's.** Before that, every attempt ended in "Didn't catch
that", and so did Google Assistant itself from the remote's microphone key: the audio service
logged "RVS capable devices not present" and fell back to a built-in microphone the Shield does
not have. The cause was the **Android TV Remote Service** package (`com.google.android.tv.remote.service`)
being disabled by user on the device: it hosts the service that streams the remote's microphone
(`IAtvAudioProviderService`), and with it off neither the Assistant nor any app can hear the
remote. `pm enable` on that package fixed both at once, no reboot needed. Worth remembering when
voice search "hears nothing" on a Shield: check the Assistant first, then that package.

**What could not be verified here.** The Fire TV Stick was unreachable on the tailnet during
this pass, so "the button is absent there" rests on the recogniser query and the instrumented
no-recogniser layout test. The Shield pass was driven with TalkBack off (the d-pad drives the
TalkBack cursor otherwise); the spoken lines — "Search by voice", the listening line, the
result count, the first card — are covered by the instrumented accessibility suite, not by a
read of TalkBack's log on the device.

**What the reviews changed (2026-10-27).** Codex and CodeRabbit found the same gaps. The
microphone is now declared optional; `RECORD_AUDIO` had implied it as required (aapt2 badging
showed it), which would keep the app off TVs that report no microphone. Clear, a keystroke, or a
details page, player or the sign-out dialog opening over the pane now cancels a listening session
first, and the view model drops the text it would have put back, so a late outcome can neither
undo the user's edit nor pull focus under an overlay. Each press gets its own `SpeechRecognizer`:
a reused one delivered callbacks queued for a cancelled attempt to the next one, and the Shield's
service does send a late CANCELLED/GRPC error after a cancel. `FakeVoiceSearch.cancel()` now
reports `Cancelled` as the real session does, so the cancel tests prove the field comes back.
On the Shield, rapid press/cancel/press ended cleanly in "Didn't catch that", and Clear while
listening closed the microphone and kept the field empty. The Fire TV was still offline.

**Readiness prompting (2026-10-27).** Recogniser startup now reports `Starting`, with a
"Starting…" button, the accessible action "Cancel voice search", and "Starting voice search…"
in the header's polite live region. Only the active attempt's `onReadyForSpeech` reports
`Listening` and displays the speak prompt. The usual order is `Starting → Listening → Done`,
but completion can precede readiness. Both active phases own Back and cancel on a second
press, typing, Clear or an overlay. Startup captures the query and suspends its debounce;
readiness only advances the phase, and partial guesses are accepted only while listening.

Validation: `:app:testDebugUnitTest` passed **1,161/1,161**, including **37/37** in
`SearchViewModelTest`; `:app:compileDebugAndroidTestKotlin` passed. The focused
`SearchPaneBehaviorTest` and `SearchPaneAccessibilityTest` run passed **60/60** on the
Google TV API 34 1080p emulator. Coverage includes delayed readiness, debounce suspension,
query restoration, early completion, ignored late readiness, startup cancellation and retry,
edit/overlay cancellation in both phases, and the startup-to-listening live-region transition.

The updated debug build was installed on the Shield and reached the listening prompt after
a fresh app launch. Cancellation specifically before readiness, immediate retry from that
cancellation, speech capture at the prompt, and spoken TalkBack announcements still need a
hands-on pass; the remote ADB check did not establish those behaviors.
