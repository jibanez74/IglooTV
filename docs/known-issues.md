# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

---

## TalkBack with a physical remote bypasses the app's d-pad wiring

**Found:** 2026-11-10, tracing "Left from Home always reaches Sign out" on the Shield with its own
remote (TalkBack 14.2 leanback; the Google TV emulator behaves the same).
**Status:** open. The spine entry and the hero's node were fixed on `fix/talkback-rail-entry`
(design-system §6.3, §11.2, §11.3.1); everything below is not.

TalkBack for TV takes Up, Down, Left and Right from a real remote itself
(`Pipeline ... focusDirection= action=NAVIGATE direction=N ... inputMode=2`). It searches the
accessibility tree by geometry from its cursor, then sends `ACTION_FOCUS` and
`ACTION_ACCESSIBILITY_FOCUS` to the winner. The app never receives the key. A search that finds
nothing fails silently (`success=false`). Back and Select still reach the app. So none of the
following runs for a TalkBack user holding a remote:

- `focusProperties` edges: hand-wired targets and `FocusRequester.Cancel` pins alike.
- `onKeyEvent` / `onPreviewKeyEvent` handlers on the arrows.
- Focus memory that a requester walks onto a remembered card.

Seen so far:

- **The hero's pages never turn.** Right on the hero finds nothing to its right and fails, so a
  TalkBack user cannot reach the second page.
- **Right from the spine lands on whatever is level with the row.** From Sign out that is the
  "Recently Added Shows" heading, not the pane's entry anchor.
- **Rails and grids ignore focus memory.** Down from the hero reads the Continue Watching heading,
  then the card under it, not the remembered card. `AnnouncedSectionMove` and `AnnouncedLazyMove`
  never run: TalkBack scrolls lists itself.
- **Input focus and TalkBack's cursor drift apart.** When the cursor moves onto a node that is not
  input-focusable (a rail heading, "Signed in as …"), input focus stays where it was. Seen: input
  focus on the Settings row while the cursor was on the Continue Watching heading.

Not yet checked, but wired the same way: the details pages (reading stops, extras rails, season
chips, the More menu), the player chrome's Cancel pins, the up-next card, every dialog, the
library and music panes' header chains, Search, Quick Connect, and a watch room's screen and its
Watch Together dialog. VoiceView on the Firestick is
untested too.

**Why earlier passes missed it.** `adb shell input keyevent` (and `input dpad keyevent`) is
injected after the accessibility input filter, so TalkBack never sees those keys and the app
handles them. Every TalkBack pass before 2026-11-10 drove the d-pad that way.

**How to test it.** Drive a virtual remote through `/system/bin/hid`: a uhid consumer-control
device with descriptor `05 0C 09 01 A1 01 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0`,
with `"bus": "bluetooth"` (required on Android 11). Send two-byte usage reports: 0x41 select,
0x42 up, 0x43 down, 0x44 left, 0x45 right, 0x224 back, `[0, 0]` to release. Writing to the real
remote's `/dev/input/event2` is denied by SELinux. Read TalkBack's verbose log for
`action=SPEAK`, as in the TalkBack tracing notes.

**Where a fix starts.** Under TalkBack the accessibility tree's geometry is the navigation model,
so every hand-wired edge needs a geometric equivalent or an accessibility action. Fixes found so
far:

- Hide what TalkBack must not land on (the collapsed spine).
- Size a node so the right neighbour is geometrically next to it (the hero's text column).
- Possibly a custom accessibility action for page turns (TalkBack on TV reaches actions through
  its menu).

Audit screen by screen with the virtual remote before changing code.

---

## A watch room cannot be edited

**Found:** 2026-11-13, building the room screen (design-system §11.10.1).
**Status:** partly fixed 2026-11-16. The owner can now change who is invited (**Edit invitees**,
`PUT /api/watch-rooms/{id}/members`), and an invitee can leave (`POST /api/watch-rooms/{id}/leave`).
The room's mode, audio and subtitles are still fixed when it is created; the server has no route to
change them.

## An empty watch room forgets where it was

**Found:** 2026-11-13, reading `server/cmd/api/watch_room_ws.go`.
**Status:** open, server-side.

The server keeps a room's position in memory only, and drops it when the room's last connection
closes or the server restarts: the next viewer finds it paused at 0. A TV that comes back to such
a room alone puts its own position back (§11.10.1), but a viewer opening it later, after
everyone has left, starts the movie over. The fix is the server's: keep the last position with
the room.

## A watch room's drift is corrected only on room events

**Found:** 2026-11-13, matching the web client's sync (§11.10.2).
**Status:** open, by design for now.

A member's player is matched to the room when someone plays, pauses or seeks, when it connects,
and when its own engine comes back — never in between. A TV that rebuffers falls behind and stays
behind until the next event. The web client behaves the same. A periodic check (re-follow every
so often, or after each rebuffer) would close it; the cost is a seek, and a rebuffer, whenever two
members' players disagree by more than 1.5 s.

## Seeking far in a watch room waits on its one encoder

**Found:** 2026-11-13, reading the room's HLS session (§11.10.2).
**Status:** open, server-side.

A room has one HLS session from 0, never rebased, so a far seek waits for the room's encoder to
reach it: the player's long-poll and 503 budget, minutes for a transcode. A remux room's playlist
is a live EVENT playlist until FFmpeg finishes, and ExoPlayer clamps a seek past its live edge,
so a member who joins late or jumps ahead trails the room until the encoder catches up. The
server would need seekable room sessions (a second session window, as personal sessions rebase).

## A command made while a room's link is down stays on that TV

**Found:** 2026-11-13, building the room's reconnect (§11.10.1).
**Status:** open, deliberate.

While a room's socket is reconnecting, a play, pause or seek moves only that TV, and the room's
state wins once the link is back — unless the TV was the only one there, when it puts its own
position back. Queueing the commands for the reconnect would replay a viewer's pauses onto a room
that moved on without them.

