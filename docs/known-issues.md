# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

Smaller things — duplication, organisation, coverage gaps and polish — live in
[`cleanup-backlog.md`](cleanup-backlog.md).

---

## TalkBack cursor drifts to the profile avatar during rapid Home rail state swaps

**Found:** 2026-10-14, manually checking Recently Added Shows fixtures on the Shield.
**Status:** open; input focus restores correctly, but TalkBack cursor restoration remains unresolved.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/core/ui/IglooMediaRail.kt`,
`app/src/main/java/com/igloo/blindpenguincoder/core/ui/FocusRequesters.kt` (`rememberRefocusAfterSwap`)

With TalkBack active, focus a Recently Added Shows card, move to the second card, then rapidly
swap that rail through Loading → Error → empty Loaded → populated Loaded. This was reproduced
with a temporary fixture session; the harness has been removed. TalkBack must remain bound
during reproduction: `UiAutomation`, including the connection made by `AnimationScaleRule`,
suppresses the service.

The incoming rail anchor receives input focus, and the remembered second card regains it when
content returns. However, TalkBack's green cursor moves to the profile avatar instead of following
the focused rail node. The loaded card initially held both input and TalkBack focus. These were
visual observations; spoken output was not audibly verified.

The automated Home rail focus and exported accessibility-tree checks pass, so those checks alone
do not cover this service-active transition. Investigate the timing of the shared state-swap
focus request against Compose's accessibility snapshots: it currently calls `requestFocus()`
directly, while `requestFocusAnnounced` already handles newly composed nodes elsewhere. This is a
possible cause, not a confirmed diagnosis. Verify any fix on the Shield across loading, error,
empty and loaded states, including a user moving focus away while restoration is pending.

---

## TalkBack re-reads a focusable empty state whenever focus leaves or reaches it

**Found:** 2026-10-12, tracing TalkBack on the Shield during the home hero's TV show page.
**Status:** open; predates that branch.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/core/ui/IglooEmpty.kt` (`IglooFocusableEmpty`)

`IglooFocusableEmpty` is a focus target *and* a polite live region. Compose reports a focus
change on a node as a content change with no change type, and TalkBack speaks any content change
on a live-region node. So with Continue Watching empty, pressing Up from its "Nothing in progress
yet…" card to the hero speaks the hero, then queues "Nothing in progress yet…" again behind it;
pressing Down onto the card asks TalkBack to speak it three times (deduplicated to one). Seen in
TalkBack's verbose log (`action=SPEAK`) on TalkBack 14.2 for TV.

The live region is there so a rail that goes from loading to empty is announced. The hero had
the same pairing for a page turn and dropped the live region: TalkBack already speaks a change to
the description of the node its cursor is on, and a node that is not focused needs no
announcement of its own. The same change here would keep "loading → empty" spoken while the
anchor is focused and stop the re-reads; it needs its own Shield pass across every empty rail and
grid, which is why it was not folded into the hero branch.

---

## Rejected: album cover URLs are absolute and must not be "resolved"

**Found:** 2026-08-13, raised as a home-screen review comment and investigated.
**Status:** closed, no change. Recorded so it is not raised again.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/feature/home/HomeViewModel.kt:194`

A review comment argued that `HomeViewModel` passes album covers to Coil without resolving
backend-relative paths such as `/api/static/albums/...`, citing the web client's
`media-image-url.ts` as evidence that shape is supported. It is not:

- `albums.cover` has exactly two writers, `../Igloo/server/cmd/api/music_scanner.go:1029` and
  `:1106`, and both store the raw Spotify CDN URL. Nothing downloads album art into
  `static/albums/`, and that directory is empty on disk.
- The live database holds 211 albums: 190 `https://i.scdn.co/...`, 21 empty string, **zero**
  relative paths. `music_scanner_test.go:397` asserts the absolute-URL shape.
- There is no music image proxy route in `docs/openapi.json`, so there is no base URL to resolve
  against — unlike TMDB, which has `GET /api/tmdb/images/{size}/{file}`.
- `AGENTS.md:528` says to use a full Spotify image URL as provided; the shared-resolver mandate at
  `AGENTS.md:526` is scoped to TMDB path fields, which is why the movie rails call `tmdbImageUrl()`
  and the album rail does not.

The cited `media-image-url.ts` `/api` branch returns the path **unchanged** — the web client is a
same-origin SPA served by the Go binary, so the browser resolves it. It never builds an absolute
URL and says nothing about what the field contains.

If the scanner ever starts storing artwork locally this becomes real, but `IglooPosterCard` already
degrades to the music placeholder on a load error rather than showing a broken card. The genuine
relative-path bug was on avatars, where uploads really are stored as `/api/static/...`; that one
was fixed on 2026-08-14 by `avatarImageUrl` and does not apply here.
