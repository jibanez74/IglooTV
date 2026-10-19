# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

Smaller things — duplication, organisation, coverage gaps and polish — live in
[`cleanup-backlog.md`](cleanup-backlog.md).

---

## Movie hero: the poster is clipped at the top of the screen

**Found:** 2026-10-19, Shield at 4K, during the show details UI review.
**Status:** open. Not fixed on `feature/show-details`; it is a movie-page symptom and the branch
was already under review.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/feature/shared/DetailsHero.kt`
(`heroHeaderModifier`, the bottom-anchored hero), `feature/movies/MovieDetailsHeader.kt` (the
in-theaters action block).

Open "Animals" (an in-theaters title) from Home's Playing in Theaters rail: the poster's top edge
sits above the screen — no rounded top corners are visible — while the title, tagline and
"Play Trailer" sit where the show page's do. The show hero does not show this: it keeps the
movie's resume-strip footprint under its action in every state (§11.4.3), so its content block is
taller and the bottom-anchored hero leaves the poster room. A movie whose action row is a single
button with no strip is shorter than its poster, so the poster overhangs the hero's top.

Likely fix: let the hero's height follow the taller of poster and content (or reserve the strip's
footprint on the movie page too, as the show page does) rather than anchoring the content to the
bottom and letting the poster overhang. Check a library movie with a resume strip and a
Play + Trailer row before changing anything, and confirm on `dev` first: the shared hero pieces
moved on `feature/show-details`, so make sure this is not a regression from that move.

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
