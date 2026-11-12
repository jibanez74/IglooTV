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
library, music and Watch Rooms panes' header chains, Search, and Quick Connect. VoiceView on the Firestick is
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
