# Task — `feature/music-screen`

Working notes for the current branch: what was finished, how it was verified, and what has to
be true before it merges into `dev`.

**Last updated:** 2026-09-19
**Branch:** `feature/music-screen`
**Base:** `dev` at `b8dc4c2`

---

## Where the branch stands

The Music destination screen is built — see [`docs/music-screen-status.md`](docs/music-screen-status.md)
for the plain-language record and `docs/design-system.md` §11.5, §11.5.1, §11.5.2 and §11.8.2
for the as-built contracts. The branch is eight commits ahead of `dev`, in this order:

1. Data layer: retyped music list models, every Music endpoint wired and tested.
2. Shared control parameters and the session-scoped `TrackLikesViewModel`.
3. The music player generalized to a queue with a source and an endless-refill controller.
4. The shared three-action `TrackRow`, adopted by the album page.
5. The Music pane: Musicians, Albums and Tracks tabs with per-tab pages.
6. The musician detail overlay; the album's artist chips and "Go to artist".
7. Docs.

Decisions taken with the owner on 2026-09-19: **Playlists deferred** to its own branch (Liked
moves inside it, as on the web); **Shuffle in both halves** here; the **album rows converted**
to the shared row; a row's Play queues **the loaded list from that row** (web parity). One
decision taken in the pass and recorded for review: the details slot stays **single** — the
musician page and the album page replace each other rather than stack
(`docs/cleanup-backlog.md` §7.1).

## Verified

- `./gradlew :app:testDebugUnitTest` — 880 tests pass.
- `./gradlew :app:lintDebug` — clean (three `ModifierParameter` warnings on skeleton anchors,
  recorded in `cleanup-backlog.md` §7.6).
- `./gradlew :app:assembleDebug` — clean.
- Wire shapes checked against the main repository's Go row structs and the local dev server's
  envelopes (its library is empty).

## Not verified — run these first on the Shield

- `./gradlew :app:connectedDebugAndroidTest` — no device or AVD was available. The new suites
  (`MusicTabsBehaviorTest`, `TracksListFocusTest`, `TracksListAccessibilityTest`,
  `MusicianDetailsFocusTest`, `MusicianDetailsAccessibilityTest`, `MusicQueueControllerTest`'s
  instrumented siblings in `MusicPlayerScreenTest`) and the updated album and player suites
  compile but have not run.
- The end-to-end D-pad pass over ADB against a live library, with TalkBack on for one full pass
  of each tab: every tab, both overlays, playback from a row, Play all past fifty tracks,
  Shuffle all, and Back out of every layer.

## Merge checklist

- [x] JVM tests pass
- [ ] Connected tests pass on the Shield
- [x] `lintDebug` and `assembleDebug` clean
- [ ] Driven end-to-end over ADB, D-pad only, TalkBack on for one pass of each tab
- [x] `docs/design-system.md` §11.5 rewritten as built, with a changelog entry
- [x] Status docs updated; `docs/music-screen-status.md` written
- [x] No dead code, no commented-out implementations, no leftover diagnostics
- [x] New backlog items recorded in `docs/cleanup-backlog.md` §7
