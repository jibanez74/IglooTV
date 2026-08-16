# Movie detail screen — next steps

What is left on the detail screen after the refinement pass of 2026-08-16, ordered by what
should land first. Same contract as `known-issues.md`: enough detail to act on without
rediscovering it, and delete an entry when it lands.

That pass closed the previous list's items 1, 3 and 4 (toggle width reservation via
`IglooButton.labelVariants`, the overview's clamp fade, the §3.2 white-alpha rows) and the More
half of item 2 (dropped from the row, with `IglooIconButton` deleted alongside it), plus a set
of fresh findings: the resume strip's reserved slot, the About heading moved above its panel,
up-from-cast focus memory, rating-badge/chip height harmonization, `overMedia` gated on the
decode (with the first over-media instrumented coverage via `coil-test`), the Key Crew width
cap, and the dead cast-rail parameters. All are documented in `design-system.md` sections 3.2,
4.1, 9.1 and 11.4.1 and are not repeated here.

---

## 1. The focused ghost button's own glow bleeds through its translucent fill

**Found:** 2026-08-16, during the refinement pass's on-device check. Pre-existing — an A/B
against `bc88a9c` shows the identical band, so no change in this pass caused it.
**Status:** open, cosmetic, over-media only. The only item actionable today.
**Files:** `core/ui/FocusRing.kt` (the 16dp focus glow), `core/ui/IglooButton.kt` (Ghost +
`restingFill`)

On the detail hero, a focused Watch/Like shows a dark band along the button's lower inside
edge. The §3.2 ghost ground (`Black @ 0.45`) holds through focus and is translucent, so the
focused button's own 16dp elevation shadow — invisible under every opaque fill in the app —
shows through from behind, bottom-weighted where the spot shadow falls. Unfocused buttons carry
no shadow, so the band appears and disappears with focus.

### What the fix looks like

Either draw the glow outside-only (clip the shadow layer to exclude the shape's interior), or
composite the translucent fill over an opaque ground so nothing behind the button can read
through. Both touch `focusRing`, which every focusable in the app wears — measure the §6.1
treatment on token canvas before and after, since the fix must not dim the glow that carries
the focus signal there.

---

## 2. Cast cards are focus targets with no action

**Found:** 2026-08-16. **Updated:** 2026-08-16 — the extras half landed: extra-video cards now
open the trailer player (§11.8.1) and announce "Play {title}".
**Status:** open for cast only, deliberate, waiting on a person detail screen — the
*semantics* are correct.
**Files:** `MovieDetailsSections.kt` (cast cards `onClick = null`)

By the poster-card convention a null `onClick` keeps the card focusable and announces no
action, so TalkBack users are told the truth; sighted D-pad users see a pressable-looking card
that does nothing. The cards stay focusable on purpose — focus is what scrolls the rail, and
cards past the right edge would otherwise be unreachable — but a face is the most
tappable-looking thing on the screen. A person detail screen is the destination; until it
exists, this is the accepted trade.

---

## Not defects — gaps waiting on features

- **Play does not play.** Trailers now play (the §11.8.1 YouTube-embed player, landed with the
  extras work), but the *movie* player is still missing: Media3 is declared in
  `app/build.gradle.kts` and referenced by zero Kotlin sources, so the screen's primary action
  is a stub. `known-issues.md` carries the watch-progress rules that player will have to own —
  one UUID per session, a strictly increasing sequence, first save around 30 seconds — and
  those are prerequisites, not follow-ups. The trailer player deliberately shares none of that:
  trailers don't report progress.
- **Missing sections.** Section 11.4 specifies "cast, chapters, extra details"; the screen has
  cast, Extra Videos (landed 2026-08-16, §11.4.1) and About. No chapters row and no
  similar-movies rail yet.
- **More is deferred, not gone.** Section 11.4 keeps it specified as the fourth hero action;
  it returns with its menu. `IglooIconButton` was deleted with its only caller and lives in git
  history (`bc88a9c` and earlier).

---

## Checked and rejected

Recorded so the next pass does not re-flag them.

- **Hero text contrast over the backdrop.** Raised on the strength of backdrop *peaks* measured
  in glyph-free bands to the right of the text. Under the text itself the scrim is far
  stronger, and every line measures 12.6:1 or better — the alphas and the measurement are now
  §3.2 rows. The peaks are real and are why the detail hero got its own stops; the text was
  never the casualty.
- **The title breaching the vertical safe area.** A near-white pixel sits at y=51 on a 1080p
  capture, above the 54px five-percent line. It is backdrop, not glyph: the header applies
  `padding(top = safeAreaVertical)` and `safeAreaVertical` resolves to exactly 54px at this
  viewport, so the text box cannot start above it. A brightness-threshold scan over a bright
  backdrop cannot tell the two apart — measure against a dark backdrop, or read the layout
  bounds.
- **Single-line metadata ellipsis.** The title (2 lines), tagline, genres, and runtime/date
  lines ellipsize with no affordance. Accepted and recorded in §11.4.1: end-of-line ellipsis on
  metadata is the TV convention and an honest cut signal; the fade is reserved for the overview,
  the one block of prose the user came to read.
