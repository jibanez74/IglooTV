# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

Smaller things — duplication, organisation, coverage gaps and polish — live in
[`cleanup-backlog.md`](cleanup-backlog.md).

---

## With TalkBack on, d-pad Down into an off-screen grid row moves sideways

**Found:** 2026-09-29, in the DRY pass's Shield check. **Cause confirmed:** 2026-09-30, on the
Shield, with an accessibility-event trace.
**Status:** open, to be fixed in its own branch. Home's horizontal rails probably have the same
problem when Right reaches a card that isn't on screen yet, but that hasn't been checked.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/feature/shared/PaneGrid.kt`

In Movies → Genres → Action (149 movies in five columns), 38 Down presses from the Genres tab end
on the last row's fourth card, Zack Snyder's Justice League, instead of its first, X2. With
TalkBack off, the same presses at the same pace end on X2. So the grid's own focus wiring is
correct, and the fault only appears with TalkBack on.

The trace, taken by logging every focus and accessibility event from the activity's content
view, shows the same sequence on almost every press:

1. Down from a card on the bottom visible row. Compose's focus search places the next row and
   focuses its first card in the same frame, for example index 105, row 21, column 0.
2. Compose sends no `TYPE_VIEW_FOCUSED` for that card. It only announces a node that its last
   accessibility snapshot saw unfocused, which is the same rule behind the rail fix in
   design-system §6.3. TalkBack's cursor stays on the previous card.
3. The grid scrolls and sends `TYPE_VIEW_SCROLLED`. About 120–160ms later, with no key pressed,
   input focus moves to the last card of the new row (index 109, column 4). TalkBack for TV moves
   its cursor after a scroll and, on TV, moves input focus with it. Only this card gets a
   `TYPE_VIEW_FOCUSED`, followed by TalkBack's `TYPE_VIEW_ACCESSIBILITY_FOCUSED`.

After the first jump, every later Down starts from the last column, so the walk down the grid
runs through column 4 and ends on the final card.

What a fix has to do: make sure the card focus lands on already existed, unfocused, in an
earlier accessibility snapshot. Options considered on 2026-09-30:

- While a screen reader runs, the grid handles Up and Down itself. It scrolls the next row on
  screen, waits one accessibility batch, then focuses the card in the same column. This is the
  row-level version of `requestFocusAnnounced`.
- A layout in which the next row always shows at the edge of the viewport. At 960x540dp there is
  barely room for that.

`LazyLayoutCacheWindow` only precomposes items and doesn't place them, and it is experimental,
so it is not expected to help.

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
