# Igloo TV Design System

The design system for **Igloo TV**, the Android TV client (Kotlin + Jetpack Compose).

- **Audience**: whoever is building or reviewing a screen in this repo.
- **Status**: authoritative. `core/design/` implements this document; where they disagree,
  this document is the bug report and the code is what changes — or this document changes
  first, deliberately. `AGENTS.md` §Design system and §"do not invent theme tokens" both
  point here.
- **Palette origin**: the Igloo web client (`../Igloo/web`). Colors are shared so the two
  clients stay recognizably one product. **Everything else — type sizes, spacing, geometry,
  focus, motion — is authored for the 10-foot TV target and does not mirror the web.**
  See [Appendix A](#appendix-a--web-parity) for what came from where.
- **Last verified**: 2026-08-02, against web client `3b48f9a6`.

> **Changing a number here means changing `core/design/`.** Appendix B maps every token
> group to its file. A PR that changes one without the other is incomplete.

**Contents** — [1 Principles](#1-principles) · [2 Screen & scale model](#2-screen--scale-model) ·
[3 Color](#3-color) · [4 Typography](#4-typography) ·
[5 Spacing, radius, sizes, icons](#5-spacing-radius-sizes-icons) ·
[6 Focus & interaction](#6-focus--interaction) · [7 Motion](#7-motion) ·
[8 Layout & navigation shell](#8-layout--navigation-shell) · [9 Components](#9-components) ·
[10 UI states](#10-ui-states) · [11 Screens & UX](#11-screens--ux) ·
[12 Accessibility](#12-accessibility) · [Appendix A](#appendix-a--web-parity) ·
[Appendix B](#appendix-b--tokencode-index)

---

## 1. Principles

1. **Ten feet, not ten inches.** Body text is never smaller than 16sp. Nothing depends on
   reading fine detail or on precise pointing.
2. **The remote is the only input.** There is no hover, no cursor, no touch. Every reveal,
   emphasis, or affordance that a pointer UI would trigger on hover is triggered by **focus**.
3. **One focus treatment, everywhere.** A single glacier ring (§6). A user must never have to
   ask which thing is selected.
4. **Dark by default.** Light exists and must stay correct, but dark is what ships.
5. **Reduced motion is a hard rule, not a nicety.** Every animation goes through
   `iglooTween` (§7), which snaps when the system asks it to.
6. **TalkBack is a product requirement.** Not a later pass. Design the focus order and the
   spoken labels at the same time as the layout (§12).
7. **Scale is the user's call.** The app cannot know how big the TV is or how far away the
   viewer sits, so it does not guess (§2).

---

## 2. Screen & scale model

**This is the section most likely to be skipped and most likely to be needed.** Read it before
writing any dimension.

### 2.1 The core fact: TV size is not a layout variable

Android TV reports a **fixed density-independent viewport regardless of physical screen size**.
A 26" set and a 65" set, both 1080p, report exactly the same thing:

| Panel | Pixels | Density | Reported viewport |
|---|---|---|---|
| 1080p (26", 32", 55", 65" — all identical) | 1920×1080 | 320dpi (xhdpi) | **960×540dp** |
| 4K | 3840×2160 | 640dpi (xxxhdpi) | **960×540dp** |
| 720p | 1280×720 | 213dpi (tvdpi) | ~962×541dp |

So:

- **Physical inches are not detectable**, and there is no API that would tell you.
- **Inches are not what varies in dp.** A "65-inch breakpoint" is not a thing that can be
  written, and would not mean anything if it could.
- **4K buys you image resolution, not layout room.** Load higher-resolution posters on a 4K
  panel; do not lay out differently.

Design against **960×540dp** as the reference viewport. It is small, and it is the binding
constraint — the nav spine plus content pane must fit inside 960dp wide and 540dp tall.

### 2.2 What actually varies

| Variable | Detectable? | How we handle it |
|---|---|---|
| Viewing distance ÷ screen size | **No** | User-selectable `UiScale` (§2.3) |
| dp viewport | Yes | Reference 960×540dp + density-sanity guard (§2.4) |
| Overscan | Not reliably | Fixed safe-area inset (§2.5) |
| System font scale | Yes | Honored, with a bounded range (§2.6) |

The first row is the honest answer to "make it work on a 26-inch and a 65-inch TV". A 26" TV
viewed from 5 feet subtends the *same angle* as a 65" viewed from 12 feet — identical apparent
size, identical ideal type size. The variable that matters is **angular size**, which depends on
a distance the app cannot measure. Any attempt to infer it from hardware is guesswork.

**So the user declares it.** That is not a cop-out; it is the only correct model, and it also
serves the bedroom-TV-at-3-feet and the projector-at-20-feet cases that no heuristic would catch.

### 2.3 `UiScale`

```kotlin
enum class UiScale(val factor: Float) { Compact(0.875f), Standard(1.0f), Large(1.15f) }
```

- **Default `Standard`**, persisted in DataStore (`core/storage/UiPreferencesStore.kt`) under
  the existing `igloo_settings` store.
- Applied as a **multiplier** over one authored table of Standard values. There are not three
  hand-maintained scale sets — that would be ~120 numbers to keep in sync with this document,
  and it would drift.
- Range is bounded by the 960×540dp viewport: the collapsed nav rail is 118 / 128 / 140dp and
  the expanded rail 207 / 236 / 271dp — only the collapsed width costs pane layout (§8.1),
  leaving >700dp of content pane at every scale. Beyond ~1.2 the shell stops fitting.

⚑ *Android-originated: the web has no equivalent. The three factors were chosen to be
perceptible but non-destructive at 960×540dp.*

### 2.4 Density-sanity guard

Some TV sticks, cheap boxes, and misconfigured emulators report **density 1.0**, which turns a
1080p panel into a **1920×1080dp** viewport. A layout tuned for 960dp would render at half its
intended apparent size — everything tiny and unreadable at 10 feet.

```kotlin
/** Sticks/boxes reporting density 1.0 show a 1080p panel as 1920x1080dp. */
internal fun viewportFactor(widthDp: Float): Float =
    if (widthDp >= 1200f) (widthDp / 960f).coerceAtMost(2f) else 1f
```

**The two multipliers stay separate.** `UiScale` is the user's apparent-size preference;
`viewportFactor` corrects a misreporting device. Most tokens are multiplied by both, but the
safe area (§2.5) is multiplied by **only the viewport factor** — verified on hardware, see §2.5.

The 1200dp threshold sits well above any legitimate TV viewport and well below the 1920dp
failure case, so normal devices never trigger it. The 2.0 cap stops absurd values.

This is a **guard, not a breakpoint**. It corrects a misreporting device back to the reference
size; it does not lay anything out differently. Layout shape never changes with viewport — that
is what `UiScale` is for.

### 2.5 Safe area (overscan)

Older and some current TVs crop the edges of the signal. The convention is a **5% inset**:

```
safeArea = PaddingValues(horizontal = 48.dp, vertical = 27.dp)   // exactly 5% of 960x540dp
```

- Applied via `Modifier.iglooSafeArea()` at the **shell** and on full-screen non-shell surfaces
  (the auth canvas), **not** globally at the root.
- **Full-bleed content opts out**: backdrops, poster gradients, and the video player surface
  must be able to reach the physical edge. Only *chrome and text* need the inset.
- Rails pad their **content** with the safe area while letting the scroll surface bleed, so
  cards scroll off the edge instead of stopping short of it.
- **Ignores `UiScale`** — overscan is a property of the panel, not of the user's preference;
  shrinking it at `Compact` would push content off a real overscanning TV.
- **But it does track `viewportFactor`.** Verified on a Shield: at a corrected 1920dp viewport
  an unscaled 48dp inset is physically *half* the intended 5%, on precisely the devices whose
  density is already wrong. The inset is a fraction of the panel, so it must follow the
  correction — it just must not follow the user's preference.

`WindowInsets.safeDrawing` is deliberately *not* used: the app does not go edge-to-edge, TV
insets are ~0, and combining the two double-pads. It is the escalation path if a device ever
reports a real display cutout.

### 2.6 System font scale

Text is authored in `sp`, so the system font-scale setting applies automatically — and it
compounds with `UiScale`. Three mechanisms keep that from breaking layouts:

1. **Type stays in `sp`.** Never convert a text size to `dp` to "stabilize" it. That silently
   opts the user out of an accessibility setting.
2. **Fixed heights are minimums.** Use `Modifier.heightIn(min = …)`, never
   `Modifier.height(…)`, on anything that contains text. This is the real fix — it lets a
   control grow instead of clipping its label.
3. **Font scale is clamped to `[0.85, 1.30]`** by providing a modified `Density` at the theme.

Point 3 is a deliberate trade-off and is stated openly in §12 rather than buried in code. The
short version: `UiScale.Large` stacks on top of the clamp, so effective text still reaches
~1.5×, and it does so through a path the layout is designed for. Revisit this if point 2 alone
proves sufficient in practice.

### 2.7 What scales and what does not

| Scaled by `UiScale` | Not scaled by `UiScale` |
|---|---|
| spacing, radius | `layout.safeArea` — fraction of the panel; follows `viewportFactor` only |
| type sizes and line heights | `focus.ringWidth` / `restWidth` — hairlines must stay hairlines (they do grow 5% while focused, since the border is drawn inside the scaled layer: 3dp → 3.15dp) |
| component sizes (control/field/nav heights, icons) | `radius.pill` — a sentinel (999dp), not a dimension |
| layout widths (spine, poster, card) | `motion.*` durations — time, not size |
| | `layout.gridColumns` — an integer, and inverse (see below) |

`gridColumns` is the one place a discrete authored value is genuinely right: bigger UI means
*fewer* columns, and the relationship is not linear. **Compact 6 / Standard 5 / Large 4.** ⚑

### 2.8 Writing a dimension

- **Used in ≥2 files** → it is a token. Add it here and to `core/design/`.
- **Genuinely used once** → `42.dp.scaled()`. This keeps one-offs scaling correctly without
  a 40-entry token dump of values nobody else needs.
- **Never** a bare `.dp` literal on anything with a visual size. The only bare literals left
  should be inside `core/design/` itself.

---

## 3. Color

16 semantic slots, implemented in `core/design/IglooColors.kt`. Tokens are paired (surface +
foreground) so contrast is structural rather than per-call-site.

| Token | Dark (default) | Light | Role |
|---|---|---|---|
| `background` | `#0A1322` | `#F2F7FC` | App canvas |
| `foreground` | `#F8FAFC` | `#0A1322` | Primary text |
| `card` | `#15233A` | `#FFFFFF` | Raised surface |
| `cardForeground` | `#F8FAFC` | `#0A1322` | Text on cards |
| `primary` | `#38BDF8` (glacier) | `#0369A1` | Primary actions / brand |
| `primaryForeground` | `#08131F` | `#FFFFFF` | Text on primary |
| `muted` | `#0F1A2E` | `#E3EDF7` | Subtle surface |
| `mutedForeground` | `#8094AE` | `#475569` | Secondary text |
| `border` | `#2A3C57` | `#CBD9E8` | Borders |
| `ring` | `#38BDF8` | `#0EA5E9` | **The one focus color** |
| `aurora` | `#F59E0B` | `#F59E0B` | Warm accent, used sparingly |
| `auroraForeground` | `#08131F` | `#08131F` | Text on aurora |
| `sidebar` | `#0F1A2E` | `#E8F1FA` | Nav spine chrome |
| `sidebarPrimary` | `#38BDF8` | `#0369A1` | Active nav item |
| `destructive` | `#F87171` | `#DC2626` | Danger / delete |
| `destructiveForeground` | `#08131F` | `#FFFFFF` | Text on destructive |

Notes:

- `aurora` / `auroraForeground` are **identical in both themes** — intentional.
- `ring` is the single focus color across the entire app. Do not introduce a second.
- `destructiveForeground` is ported from web (`--destructive-foreground`), which authors both
  values *and* their contrast: **6.76:1 dark / 4.83:1 light**. That makes it the weakest paired
  token in the app — `primary`/`primaryForeground` is 8.72:1 — and it sits under §12's 7:1 body
  target. It is admissible because it only ever carries a **control label** (§9.1's `Destructive`
  button), never prose. Do not reach for it to color body text; a destructive *message* uses
  `cardForeground` on `card`, as §9.3 requires.
- **Not ported from web**: `success`, `accentTeal`, `popover`, `chart-1..5`. No feature needs
  them. Add the slot to `IglooColors` (and this table) when one does — do not reach for a
  near-miss token in the meantime.

### 3.1 Alpha conventions

Alpha is applied at the call site with `Color.copy(alpha = …)`. These values are already in use
and are the vocabulary — reuse them rather than inventing neighbors:

| Alpha | On | Meaning |
|---|---|---|
| `0.72f` | `card` | **Focused fill** — the standard focus background |
| `0.96f` | `card` | Focused fill on an already-elevated card |
| `0.18f` | `primary` | **Selected** (persistent state, distinct from focus) |
| `0.40f` | `primary` / `destructive` | Disabled control |
| `0.60f` | `mutedForeground` | Placeholder text |
| `0.60f` | `background` | Scrim — the rail over the content pane (§8.1) and the modal (§9.3) |
| `0.10f` / `0.25f` | `destructive` | Inline error card fill / border |
| `0.16f` / `0.48f` | `aurora` | Badge fill / border |
| `0.26f` dark / `0.16f` light | `primary` | Welcome backdrop core (§11.1.0) |
| `0.18f` dark / `0.10f` light | `aurora` | Welcome backdrop core (§11.1.0) |

The two backdrop rows are the only alphas that differ by theme, and they differ because the same
alpha does opposite things: dark `primary` (#38BDF8) *raises* luminance toward the text color,
while light `primary` (#0369A1) is a heavy navy that *drops* a bright canvas fast. Composited
luminance under text must stay ≤ 0.093 in dark and ≥ 0.345 in light — the §12 7:1 boundaries.

**Focus vs selected is a real distinction**: `card @ 0.72` means "the remote is here right now";
`primary @ 0.18` means "this is the active destination". Both can be true at once, and the nav
spine renders them together.

**Only one scrim renders at a time.** The rail's and the modal's are the same `background @ 0.60`,
and two of them composite to 0.84 — an alpha this table does not authorize, and dark enough that
the control the user just left stops being legible. One implementation enforces the alpha itself:
`core/ui/IglooScrim.kt`.

When a modal opens over the expanded rail, the modal owns the only scrim for its whole lifetime and
the host **unmounts the rail's scrim node** rather than animating it out — animating it would
composite a fading 0.60 layer under the modal's reveal for the length of the transition. The rail's
own animated value is deliberately left at its 0.60 target while hidden, so cancelling the modal
restores that scrim in the same frame instead of fading it back in under the focus ring the rail is
getting back (§9.3). `IglooApp.kt` does both halves; `IglooConfirmDialogMotionTest` pins the result
by sampling the pixel behind the card: one scrim before, none at reveal time-zero, one again once
the modal is fully revealed.

### 3.2 Color over media

White-on-darkened-poster is a legitimate pattern that does **not** get tokens, because it must
not track the theme — a poster looks the same in light and dark mode. Use literals:

- Poster dim overlay on focus: `Color.Black.copy(alpha = 0.30f)` (video) / `0.40f` (album art)
- Title gradient over poster: `Brush.verticalGradient` to `Color.Black.copy(alpha = 0.90f)`
- Text over media: `Color.White`, with a shadow for legibility

---

## 4. Typography

Seven styles, implemented in `core/design/IglooTypography.kt`. Family is the system sans
(`FontFamily.SansSerif`) except the pairing code, which is monospace.

**Standard** column is authored; Compact and Large are derived by multiplier and shown for
reference.

| Style | Size / line height | Weight | Compact | Large | Use |
|---|---|---|---|---|---|
| `displayCode` | 64 / 76sp, +10sp tracking, mono | Bold | 56 / 67 | 74 / 87 | Quick-connect pairing code only |
| `display` | 44 / 52sp | SemiBold | 39 / 46 | 51 / 60 | Hero headline on a full-bleed canvas |
| `titleLarge` | 34 / 40sp | SemiBold | 30 / 35 | 39 / 46 | Screen headings |
| `titleMedium` | 24 / 30sp | SemiBold | 21 / 26 | 28 / 35 | Section and card titles |
| `bodyLarge` | 18 / 24sp | Medium | 16 / 21 | 21 / 28 | Emphasized body, control labels |
| `bodyMedium` | 16 / 22sp | Normal | 14 / 19 | 18 / 25 | Body copy — **the floor** |
| `label` | 15 / 20sp | SemiBold | 13 / 18 | 17 / 23 | Nav items, chips, metadata |

Rules:

- **16sp is the hard minimum for body copy.** If something needs to be smaller to fit, the
  layout is wrong, not the type. `label` at 15sp is the single exception and is reserved for
  short non-prose strings (nav labels, chips) — never a sentence.
- **No new style without editing this table first.** `AGENTS.md` forbids inventing theme
  tokens; adding a `TextStyle` in a feature package is exactly that. One is anticipated and
  deliberately not added yet: `caption` (metadata chips). Add it when a feature actually needs it.
- **`display` is sized against the vertical budget, not by eye.** At `UiScale.Large` with the
  font scale at its 1.30 ceiling, a 52sp line height renders at ~78dp per line — 16% of the 486dp
  the safe area leaves on a 540dp panel. It is 1.29× `titleLarge`, which is a legible step without
  spending the screen. Reserve it for a canvas with no card.
- **Budget it at the number of lines it will actually take, not one.** §11.1.0's hero wraps to two
  in a half-width column at every scale, so it spends ~156dp — about a third of the panel. That
  fits, and `WelcomeScreenLayoutTest` is what proves it; the point is that "one line" is not a
  property of the style and must not be assumed when placing it. Measure the wrapped height in the
  column the text actually gets.
- Headings are SemiBold; controls and labels are Medium or SemiBold. Nothing is Light or Thin —
  thin strokes disintegrate at 10 feet.

### 4.1 Truncation

`IglooText` defaults to `TextOverflow.Ellipsis` with unbounded `maxLines`, so it only truncates
when *height*-constrained — which, given §2.6 point 2, should be rare. Where truncation is
intended, say so explicitly with `maxLines`:

- Card titles: `maxLines = 2`
- Nav labels, list rows: `maxLines = 1`
- Hero copy, the pairing code, error messages: **never truncate** — pass
  `overflow = TextOverflow.Visible` and let the container grow.

Truncation is a **visual** defect only — `BasicText` still exposes the full string to TalkBack.
It is still a defect.

---

## 5. Spacing, radius, sizes, icons

All in `core/design/IglooDimens.kt`. All scale with `UiScale` unless marked.

### 5.1 Spacing — a 4dp step

| Token | Standard |
|---|---|
| `xs` | 4dp |
| `sm` | 8dp |
| `md` | 16dp |
| `lg` | 24dp |
| `xl` | 32dp |
| `xxl` | 48dp |

Card interiors use `xl`; blocks within a card separate by `lg`; inline pairs by `md` or `sm`.

*Rounding note*: `4.dp × 0.875 = 3.5 → 4`, so `xs` is identical at Compact and Standard. That is
expected, not a bug.

### 5.2 Radius

| Token | Standard | Use |
|---|---|---|
| `sm` | 6dp | Small chips |
| `md` | 8dp | Dense inline elements |
| `lg` | 10dp | **Buttons, inputs, nav rows, brand tile** |
| `xl` | 14dp | Cards, panels, surfaces |
| `pill` | 999dp *(unscaled sentinel)* | Fully-rounded badges |

### 5.3 Component sizes

| Token | Standard | Use |
|---|---|---|
| `controlHeight` | 52dp | Buttons — as `heightIn(min=)` |
| `fieldHeight` | 56dp | Text fields — as `heightIn(min=)` |
| `navItemHeight` | 44dp | Nav spine rows — as `heightIn(min=)` |
| `brandTile` | 48dp | The "I" logo tile |
| `dot` | 10dp | Status / active indicator |

Every height here is a **minimum**, never a fixed height (§2.6).

### 5.4 Icons ⚑

| Token | Standard |
|---|---|
| `md` | 24dp |
| `lg` | 32dp |

⚑ *Android-originated. Only two sizes, deliberately: the app has no icon dependency yet, and
these exist so that the first feature to need one does not invent 26dp.*

---

## 6. Focus & interaction

**Focus is the whole interaction model.** The web client this palette came from leans on hover;
none of that exists here. Every hover affordance becomes a focus affordance.

### 6.1 The one focus treatment

| Property | Value | Scaled? |
|---|---|---|
| Ring width (focused) | 3dp, `colors.ring` | No |
| Border width (at rest) | 1dp, `colors.border` | No |
| Separator (focused) | 1dp gap between ring and fill, showing the surface behind | No |
| Fill (focused) | `colors.card @ 0.72` | — |
| Scale | 1.05×, over `standard` (§7) | No |
| Glow | `colors.ring`, 16dp elevation, over `standard` | No |

Ring and rest widths are unscaled on purpose: a 2.6dp ring at Compact reads mushy, and a
hairline must stay a hairline at every scale.

**All of it lives in one modifier**, `Modifier.focusRing` (`core/ui/FocusRing.kt`), because the
properties cannot be ordered independently: a shadow drawn inside a clip is discarded (the
platform projects a child's shadow into its parent render node, which *is* the clip), and a
border drawn outside one is painted over by the fill. So `focusRing` owns the clip, and
therefore the fill — **a call site passes `fill =` and does not clip.** The colour transition
runs at `micro` and the scale/elevation at `standard`, per §7.

The clip applies **at rest as well as on focus**, at inset zero. A treatment that only clipped
while focused would square off the corners of every control that had given up its own `clip` on
the strength of the sentence above, then snap them round the instant focus arrived. The strokes
are drawn after the content, so opaque edge-to-edge content — an avatar, a poster — cannot paint
over the ring or the resting border.

**Non-focusable chrome does not use `focusRing`.** A panel that is filled, clipped, and outlined
by the same hairline but can never take focus uses `Modifier.iglooSurface`
(`core/ui/IglooSurface.kt`) instead, so `focus.restWidth` stays the one hairline in the app while
a call site still says plainly whether the thing can be focused.

**The separator is what makes focus legible on a `Primary` button.** `ring` and `primary` are
the same value in dark (§3), so a ring drawn on a glacier fill is invisible — and worse than a
no-op, since at rest that edge carries a visible `border` slate. On focus the fill contracts by
`ringWidth + restWidth`, leaving a real gap that shows whatever is behind the control rather
than a guessed colour, so it is correct over `background`, `card`, `sidebar`, or a poster.
Ring against that gap is 8.68:1. Measured on a Shield the gap bottoms at **3.31:1** against the
fill after the panel's upscale — thin, but past the 3:1 non-text threshold.

**The glow is ambience, not the indicator, and the colours go in at full alpha.** The platform
multiplies shadow colours by the theme's `spotShadowAlpha` (0.19) and `ambientShadowAlpha`
(0.039) before rasterising. A `ring @ 0.20` spot colour — which this section specified until the
treatment was actually built — lands at 0.038 effective, **1.06:1 against `background`**, i.e.
nothing. At full alpha it reaches 1.53:1, the ceiling of the platform shadow pipeline; 3:1 would
need ~0.50. So the ring, the separator, and the scale carry focus, and the glow only softens the
silhouette. `ambientShadowColor` must also be set to the ring: left at its `Color.Black` default
it darkens the canvas and cancels part of the spot's blue.

Implemented as `graphicsLayer { shadowElevation; ambientShadowColor; spotShadowColor; shape }` —
not `Modifier.shadow`, which branches internally on `elevation > 0.dp` and would rebuild the
layer node on every focus transition. `spotColor` is available at `minSdk 28`.

*Known artifact*: `clipScrollableContainer` clips unconditionally, so the first and last row of
the nav spine's scroll column lose the outer edge of their glow. Fixing it costs 32dp of the
spine's vertical budget, which §11.2 already calls the tightest constraint in the app; not worth
it for a ≤1.5:1 effect.

### 6.2 Converting pointer patterns

| Pointer pattern | TV equivalent |
|---|---|
| `hover` overlay + centered Play reveal | Track `Modifier.onFocusChanged`; show dim overlay + Play affordance when the card or its focus group is focused |
| `hover` poster zoom | The 1.05× of the one treatment — `focusRing` applies it; call sites do not roll their own |
| `hover` lift + colored glow | Focused elevation + glacier glow (§6.1) |
| Pointer focus ring | The one focus treatment above — no separate style |
| Action hidden until hover | **Never gate an action behind focus alone.** Show it always, or reveal on *row* focus so it is reachable before it is needed |
| Idle-hide chrome on pointer move | Show chrome on any d-pad or media-key event; auto-hide after an idle timeout |

### 6.3 Focus behavior

- **Restoration**: returning to a rail or grid restores the last-focused item, not the first.
  Back navigation restores focus to the element that led away. The nav spine is the exception:
  re-entering it (d-pad left, or Back from content) lands on the **current destination's** row —
  the row whose selected state is announced — so Back-then-OK is an idempotent "stay here" (§11.2).
- **Spine ↔ content**: d-pad **right** from the spine enters content; **left** from the first
  content column returns to the spine. This boundary is hand-wired
  (`FocusRequester` in `feature/home/IglooApp.kt`) and must be preserved.
- **No traps.** Every focusable region has a reachable exit in every direction that looks like
  it should work.
- **Skeletons are grid-matched** (§10) so focus does not jump when content arrives.
- Order matters: `Modifier.clickable` and `Modifier.onFocusChanged` are order-sensitive. Put
  focus observation *outside* the clickable so it sees the same focus state the indication does.

---

## 7. Motion

| Token | Value | Use |
|---|---|---|
| `micro` | 150ms | Focus fill, color, small opacity changes |
| `standard` | 200ms | Scale, elevation, overlay reveal |
| `page` | 300ms | Screen and section enter/exit |
| `stagger` | 60ms | Delay between items of one staggered section enter |
| `ambient` | 24000ms | One full cycle of a decorative ambient loop (§7.2) |
| `splashHold` | 900ms | Minimum time the launch splash stays up before it may hand off (§11.1.-1) |

`splashHold` is a hold, not an animation: reduced motion stills the splash but does not shorten
it, or the brand moment degrades into a flash on a fast device.

Easings — two, deliberately:

```kotlin
standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)   // entering, settling
exit     = CubicBezierEasing(0.4f, 0f, 1f, 1f)   // leaving
```

### 7.1 The reduced-motion contract

```kotlin
@Composable
fun <T> iglooTween(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = IglooEasing.standard,
): FiniteAnimationSpec<T> =
    if (LocalIglooReducedMotion.current) snap() else tween(durationMillis, delayMillis, easing)

@Composable
fun rememberAmbientProgress(periodMillis: Int = IglooMotion.AMBIENT_MS): State<Float>
```

**Rule: no bare `tween(...)`, `infiniteRepeatable(...)`, `rememberInfiniteTransition(...)` or raw
duration literal in feature code. Always `iglooTween` or `rememberAmbientProgress`.** This is
grep-enforceable — `\btween\(|\binfiniteRepeatable\(|rememberInfiniteTransition\(` outside
`core/design/` must return nothing — and is the reason the helpers exist rather than each call
site checking the flag and forgetting.

`snap()` discards the delay, so reduced motion collapses a whole stagger to a single frame for
free. `rememberAmbientProgress` holds at `0f` under reduced motion and, critically, does not
build the transition at all in that branch — an ignored `rememberInfiniteTransition` still
requests a frame callback 60×/s forever.

Reduced motion reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` and observes it live via a
`ContentObserver`, so toggling the system setting takes effect without an app restart.

### 7.2 What may animate

Focus transitions, overlay reveals, section enters, progress fills. **Not**: anything that moves
focus itself, anything that delays a user-initiated navigation, or anything looping in the
periphery while the user is trying to read.

**The one loop exception.** A decorative backdrop may loop if it meets all four conditions: it
carries no information, its period is ≥20s, it never moves geometry under text (only the color
beneath the text changes), and it holds still under reduced motion. Nothing else loops. §11.1.0
is the only surface that currently qualifies. §11.1.-1 reuses that backdrop's geometry with a
constant progress, so it does not loop and does not extend the carve-out.

A staggered section enter animates `alpha` and `translationY` through
`Modifier.graphicsLayer { … }`, never `Modifier.offset` (layout-phase — it would move the focus
rect mid-animation, which the paragraph above forbids) and never `AnimatedVisibility` (a node
added late cannot be focused at frame 1, leaving the screen dead to the remote for the duration).
Every focusable is composed immediately; only its paint is delayed.

---

## 8. Layout & navigation shell

### 8.1 The shell

A persistent **left nav spine** and a content pane, inside the safe area.

| Token | Standard |
|---|---|
| `navRailCollapsedWidth` | 128dp *(48dp safe area — `viewportFactor` only — + 80dp scaled icon strip)* |
| `navRailExpandedWidth` | 236dp |
| `safeArea` | 48dp × 27dp *(unscaled)* |
| `authCardWidth` | 480dp *(the centered auth card for a form — §11.1.2, §11.1.3)* |
| `authCardWideWidth` | 840dp *(the centered auth card for a row — §11.1.1, quick connect)* |

At Standard on the 960dp reference viewport: 960 − 128 (collapsed rail, which absorbs the left
safe area) − 32 (content gutter, `spacing.xl`) − 48 (right safe area) = **752dp of content
pane**. That is the budget. Everything in §8.2 is sized against it — the expanded rail overlays
the pane and costs it nothing.

The spine is the primary vertical d-pad target and is always visible, resting as the collapsed
icon strip. It expands to `navRailExpandedWidth` exactly while d-pad focus is inside it,
overlaying the pane behind a `background @ 0.60` scrim (§3.1); the pane is padded by the
collapsed width only and never reflows. Labels fade in paint only (§7.2) — every row is composed
and focusable in both states, so the d-pad and TalkBack see the same tree open or shut. There is
no drawer, no hamburger, and no fully-hidden mode.

### 8.2 Media geometry

| Token | Standard | Notes |
|---|---|---|
| `posterWidth` | 148dp ⚑ | 752dp of pane ⇒ 4 posters + a ~96dp peek, which cues scrollability |
| `posterAspect` | 2:3 | `Modifier.aspectRatio(2f / 3f)` |
| `wideCardWidth` | 264dp | Backdrop / episode cards |
| `wideAspect` | 16:9 | |
| `gridColumns` | 6 / 5 / 4 ⚑ | Compact / Standard / Large — *unscaled, and inverse* |

Album art is square (`aspectRatio(1f)`); musician thumbnails are circular. Both use
`posterWidth` as their base width.

**A partially-visible next card is a feature.** It is the only affordance telling a remote user
the rail continues.

### 8.3 Rails and grids

> **Use `androidx.compose.foundation`'s `LazyRow` and `LazyColumn`.**
>
> `TvLazyRow` / `TvLazyColumn` **do not exist**. They were removed before
> `androidx.tv:tv-foundation` reached 1.0 — the pinned `1.0.0` artifact contains only
> `ExperimentalTvFoundationApi`, `TvImeOptionsKt`, and `TvKeyboardAlignment`. Older guidance
> and blog posts still reference them; **do not reintroduce them**, and do not add
> `tv-foundation` as a dependency for list purposes.

Grids use `LazyVerticalGrid` with `GridCells.Fixed(IglooTheme.layout.gridColumns)`.

Rails pad content with the safe area and let the scroll surface bleed past it (§2.5).

---

## 9. Components

### 9.1 In-repo primitives (`core/ui/`)

| Composable | Notes |
|---|---|
| `IglooText` | Wraps `BasicText`. Takes explicit `style` and `color` — there is no ambient text style, by design. |
| `IglooButton` | `heightIn(min = sizes.controlHeight)`, radius `lg`, focus per §6.1. Three variants: `Primary`, `Ghost`, `Destructive` (`destructive` fill / `destructiveForeground` label, §3). |
| `IglooTextField` | `heightIn(min = sizes.fieldHeight)`, radius `lg`, placeholder at `mutedForeground @ 0.60` |
| `IglooInlineError` | `destructive @ 0.10` fill, `@ 0.25` border, radius `lg` |
| `IglooNotice` | One announced line — `bodyMedium` / `mutedForeground`, `liveRegion = Polite`. For a message the user did not ask for and cannot act on: what a gate says after an action that already happened (§10, §11.1.1). Not an error card; no Retry. |
| `IglooScrim` | The paint-only dim: `background @ 0.60` by default (§3.1), no `clickable`/`focusable`/`semantics`, so it can never intercept the d-pad and TalkBack does not know it exists. Used by the rail (§8.1) and the modal (§9.3) — **never both at once**. |
| `IglooConfirmDialog` | The confirmation modal (§9.3) |
| `FocusRing` | The one focus treatment (§6.1) as one modifier: glow, scale, fill, clip, ring, separator. **Owns the fill; call sites pass `fill =` and must not clip.** |
| `IglooQrCode` | Pairing-code QR |
| `IglooBrandMark` | The "I" tile. Always radius `lg`; hidden from accessibility, since the glyph is not a word. Size and text style are the only parameters. |
| `IglooPosterCard` | The 2:3 media card (§8.2): poster at `layout.posterWidth` / `layout.posterAspect`, radius `lg`, focus per §6.1 on the artwork only — title (`bodyMedium`, 2 lines) and one context line (`label`) sit below it and keep still while the poster scales. One cleared semantics node ("Title, Year", `Role.Button`); a null or failed image falls back to the film glyph on `muted` with the text unchanged. Optional `PosterCardProgress`: a 4dp bar on the poster's bottom edge (`primary` fill on a `Black @ 0.40` track, §3.2) whose description joins the cleared node ("Title, Year, N min left", §12) so the bar can never render unannounced. |
| `IglooMediaRail` | The §8.3 rail: heading + foundation `LazyRow` of cards, grid-matched static skeletons, minimal `IglooEmpty`, and `IglooInlineError` with Retry. Owns per-rail focus memory (§6.3): the entry card is the last-focused one, and a rail rebuilt on re-entry is created scrolled so that card exists to take focus. **Every state keeps exactly one focus anchor** wired to the pane's entry requester and the spine, so the shell's focus model (§8.1, Back) always has somewhere to land — including while loading and when empty. |
| `IglooEmpty` | §10 empty state, minimal variant only: faded icon + one announced line. The rich-CTA variant is not built yet; the first screen with a real action to offer adds it. |

The app deliberately does **not** use Material theming. `IglooTheme` is the only source of
colors, type, and dimensions.

### 9.2 `androidx.tv.material3` (1.1.0)

Available and appropriate to adopt where it saves hand-rolling:

- `Surface` with `ClickableSurfaceScale` / `Glow` / `Border` — TV-aware focus indication that
  matches §6.1 if configured with our tokens
- `Carousel` — featured/hero rotator
- `NavigationDrawer` — an alternative spine implementation
- `ListItem`, `TabRow` / `Tab`, `Card`

Adopt these for *behavior*; always configure them with Igloo tokens, never their defaults.

### 9.3 The confirmation dialog

`IglooConfirmDialog` is the app's only modal. It exists for actions that destroy something the
user cannot get back with one press — today, sign out (§11.2).

**It is an in-tree overlay, not `androidx.compose.ui.window.Dialog`.** This is a deliberate
choice, not an oversight, and four things break if someone "fixes" it into a real dialog window:

1. **The font-scale clamp is silently lost.** A dialog gets its own `AndroidComposeView`, which
   re-provides `LocalDensity` / `LocalWindowInfo` / `LocalConfiguration` from its own window — so
   `IglooTheme`'s bounded density (§12.1) does not reach inside it. A clamp bug in a modal is the
   least visible place to have one.
2. **The scrim stops being ours.** A dialog window dims with the platform's own black, un-tokened
   and theme-blind, instead of `background @ 0.60` (§3.1). It looks wrong in light theme.
3. **Back becomes untestable.** The dialog window handles Back through its own callback, so
   neither `performKeyInput { pressKey(Key.Back) }` nor an activity-dispatcher `pressBack()` helper
   reaches it — and Back-cancels-the-dialog is mandatory behavior, so it must be testable without
   UiAutomator.
4. **A second Compose root.** `onRoot()` starts matching two nodes, which breaks any test that
   measures the shell.

Everything a dialog window would give for free — focus containment, accessibility scoping — is a
handful of modifiers we already use elsewhere.

**Geometry.** Card at `layout.authCardWidth` (reused, not a new token), `radius.xl`, `card` fill,
`spacing.xl` interior padding, `spacing.lg` between blocks, `spacing.md` between the buttons.
Centered in an `IglooScrim`. The card is the modal's only surface; it does not clip, so the confirm
button's focus glow survives (§6.1).

**Copy.** A title that asks a question (`titleMedium`, `cardForeground`, `heading()`), a body that
says what will be lost (`bodyMedium`, `cardForeground`), two actions. The body uses
`cardForeground`, **not** `mutedForeground`: at 5.08:1 on `card` the muted token is under §12's
7:1 body target, and this is prose the user must actually read to make a destructive decision — the
same rule §11.1.0 states for auth prose.

**Focus.**

- The **dismiss** action is focused when the dialog opens. The destructive one is never the default.
- Dismiss left, confirm right. The confirm uses `IglooButtonVariant.Destructive`.
- The trap is `FocusRequester.Cancel` pinned on every direction that would leave the card — focus
  search terminates there, so no geometric fallback can reach the rail or the pane behind the
  scrim. It is *not* `focusProperties { canFocus = false }` on the shell.
- **Restoring focus is the invoker's job**, in its own dismiss callback — the same shape as
  §6.3's "back navigation restores focus to the element that led away":

  ```kotlin
  onDismiss = { viewModel.dismiss(); signOutFocus.requestFocus() }
  ```

  Not a `LaunchedEffect` on the open flag. On the *success* path the flag clears in the same frame
  the whole screen is disposed, and a late effect would call `requestFocus()` on a detached
  requester and throw. A click or Back callback runs while the invoker is provably still composed.
- Back cancels. **The host must also gate its own `BackHandler`s while the dialog is open** —
  relying on registration order is not "deliberate Back behavior".

**Pending.** While the action is in flight the button row is replaced by a single **focusable**
status line (`liveRegion = Polite`, so TalkBack speaks it). Not disabled buttons:
`clickable(enabled = false)` removes the focus target, and in an in-tree modal that punches a hole
in the trap — the next d-pad press would land on a card behind the scrim. Back stays live, because
the request cannot be recalled anyway and a dead remote for the length of a network timeout is
worse; the action belongs to a ViewModel, so leaving does not abandon it (§10).

**Pending outlives the modal.** Because Back closes the dialog while the work continues, the
pending flag must *not* be cleared by dismissal — only by the action finishing. Otherwise the
control that opened the dialog can open it again and ask a question that is already answered: the
local half of a destructive action is committed before the network call, so the second
confirmation would offer a Cancel that undoes nothing, seconds before the screen changes under the
user. Dismiss stops *showing* the work; it does not recall it.

**TalkBack.** The card carries `paneTitle` and `isTraversalGroup`; the shell behind it carries
`hideFromAccessibility()` while the dialog is open. That hides it from *traversal* while keeping
the nodes in the semantics tree, so tests can still assert the rail is not focused.

**Motion.** One `standard` alpha reveal via `graphicsLayer` (§7.2), and **no exit animation** — the
overlay leaves at once so the restored focus ring is never drawn under a fading scrim. The focus
request does not wait on the reveal.

**A dialog never carries an inline error.** If the action fails, the dialog closes and the message
belongs to whatever screen the app lands on (§10) — the modal is gone by the time there is
anything to say.

---

## 10. UI states

Build these **once** as shared composables. Three states, one recipe each.

- **`IglooLoading`** — grid-matched skeletons. A movie grid skeleton renders the same column
  count and the same card aspect as the real grid, so **focus position does not jump** when
  content arrives. Skeletons are **static blocks** — a shimmer is a loop, and nothing in the
  product loops except §11.1.0's ambient drift (§7.2); that rule wins.
  Route-level loading uses one app-wide pending screen. It is deliberately *not* the launch splash
  (§11.1.-1): the splash is the boot moment and runs once. No route needs a pending screen yet, so
  none is built; the first one that does must add it rather than reach for the splash.
- **`IglooEmpty`** — two variants. *Minimal*: large faded icon + one line ("No movies found in
  your library."). *Rich CTA*: icon orb, heading, description, and a focusable primary action.
  Use the rich variant only when there is a real action to offer. (Only the minimal variant is
  built — §9.1.)
- **`IglooError`** — inline card, `destructive @ 0.10` fill / `@ 0.25` border, a message, and a
  **focusable Retry** that re-runs the query. Retry must be reachable by d-pad without leaving
  the screen. This recipe is `IglooInlineError` (§9.1) — there is no separate composable.

All three announce themselves to TalkBack when they replace content (§12).

**A pending mutation is shown on the control that started it** — a picker tile (§11.1.1), a submit
button, a dialog's action row (§9.3) — never as an app-wide overlay. The app-wide pending screen is
for *routes*. This keeps the rest of the screen readable and keeps focus where the user put it.

**Mutation/action failures do not render inline** as an `IglooError` card: there is nothing to
retry in place, because the thing that failed is over. They surface as an announced `IglooNotice`
on the screen the app lands on, and stay until the state changes. (Not a "transient" message —
nothing in the app implements one, and a self-dismissing toast is the wrong shape for a remote:
the user cannot scrub it back if they looked away.) A failure that *can* be retried in place is a
query failure, and that is `IglooError`.

---

## 11. Screens & UX

Feature surfaces, in TV terms. Web routes are cited only as a reference for content and field
names, not for layout.

### 11.1 Auth boundary

Two top-level states: **unauthenticated** (full-bleed auth canvas, no shell) and
**authenticated** (nav spine + content pane). Neither ever shows nav chrome on the auth side.

The auth canvas takes two forms. The **card form** (§11.1.1–11.1.3) centers a single card on a
vertical gradient and scrolls internally if it does not fit; every step of setup uses it. The
**hero form** (§11.1.0) is full-bleed with no card, and opens each fresh launch of initial setup
until the user has saved a valid server URL.

A device token belongs to exactly one user and the backend has no "switch user" call, so
multiple people on one TV means **one stored token per person**. Every unauthenticated screen
below is a step toward getting or choosing one.

#### 11.1.-1 Launch splash

Before any of it, the launch screen. It is not an auth step — it covers the interval between the
launcher and the app's first real screen, whichever that turns out to be.

**Two layers, one canvas.** `androidx.core:core-splashscreen` gives the window a themed splash
(`Theme.Igloo.Splash`: `background` fill, `@drawable/ic_igloo_mark`), and `SplashScreen` in
`feature/boot/` continues it in Compose with the §11.1.0 backdrop, the brand tile, and the
`Igloo` / `TV` lockup §11.2 uses in the spine. `postSplashScreenTheme` returns the window to
`Theme.Igloo`, and `installSplashScreen()` sets **no** keep-on-screen condition: the system layer
ends at the first Compose frame, which already draws the same mark on the same navy.

- **The brand tile does not move, resize, or animate in.** The system layer draws it at 120dp of
  the 960dp reference viewport, centred on the screen; the Compose layer must match all three, so
  the mark is centred on the *screen* rather than on the lockup and the wordmark is positioned
  below it instead of pushing it up. Only the wordmark takes the §7.2 stagger. Verified by
  measuring the tile across a screen recording of the hand-off: it holds size and centre from the
  system frame through the Compose frames.
- **That 120dp is the one dimension exempt from the §2 scale model** — it is written `120.dp`, not
  `120.dp.scaled()`. The system window splash is a static drawable with no knowledge of `UiScale`,
  so scaling the Compose side would put a 105dp tile (Compact) or 138dp tile (Large) against a
  fixed system tile and re-open the seam this whole section exists to close. Every *other*
  dimension on this screen still scales.
- **The backdrop does not loop here.** It is §11.1.0's two radial gradients driven by a constant
  `0f` instead of `rememberAmbientProgress()` — a 24s drift is invisible inside a one-second
  screen, and an infinite transition would request frames for movement nobody sees. §7.2's "one
  loop exception" therefore still names only §11.1.0.
- **Held for `splashHold`, then a `page` fade.** Session restore is DataStore reads and can finish
  in a few frames.
- **Rendered as an overlay above the destination, not a `Crossfade`.** The screen underneath
  composes and requests focus on its own schedule and only the pixels above it fade; a crossfade
  would delay that composition, which §7.2 forbids. The splash drops its `contentDescription` the
  moment the fade starts, so TalkBack does not read it over the screen that now holds focus.
- **Confirm keys are swallowed while the splash is up; arrows are not.** Because the screen
  beneath composes and takes focus behind an opaque overlay, an OK pressed during the brand moment
  would otherwise activate a control the user cannot see. `IglooRoot` consumes `DirectionCenter`,
  `Enter`, `NumPadEnter`, `Spacebar` and `ButtonA` via `onPreviewKeyEvent` for exactly as long as
  the splash is shown. Arrows are deliberately left alone: they only move focus, and the user sees
  where it landed the instant the splash lifts, whereas blocking everything would leave the remote
  dead for the whole hold — a worse fault than the one being prevented. The preview pass runs from
  the root down through the focused node's ancestors, so this needs no focusable of its own and
  does not disturb the focus the screen below has already claimed.
- **Gated on the boot, not on the state.** The splash is shown until the first non-`Loading` state
  has been seen, and never for less than `splashHold`. `AppAuthState.Loading` is today only the
  value before the first `restore()`, so the two coincide — but the gate is what keeps them
  coinciding. If a flow ever returns to `Loading`, it needs the §10 pending screen; showing a
  full-screen brand moment in the middle of signing in would be wrong.
- **The splash icon is masked to a circle by the system**, so `ic_igloo_mark` keeps the tile inside
  the canvas's inner circle (45dp of 108dp) rather than filling it. The same tile geometry drives
  `ic_launcher` and `igloo_banner`, so launcher → splash → app is one mark.
- **That 45dp is derived from the 120dp above, not picked by eye.** Both paths render the drawable
  into a 288dp icon box — Android 12+ by platform spec, and `core-splashscreen`'s compat layer by
  deliberately mirroring it — so the tile lands at 45/108 × 288 = 120dp. Getting this wrong is
  visible: at 46dp the system drew 122dp and stepped down to 120dp when Compose took over, which a
  frame-by-frame capture on API 30 shows plainly. If either number changes, re-derive the other.

#### 11.1.0 First-run welcome

The hero form. A greeting, two sentences on what Igloo is, a three-step preview of setup, and one
**Get started** button that hands off to §11.1.1's server prompt. It exists because the server
prompt otherwise arrives cold — a bare field asking for an address, with nothing having explained
that Igloo is a server the user runs themselves.

**Shown on each fresh launch while initial server setup is incomplete.** Saving a valid server URL
is the durable completion point. **Get started** dismisses the welcome only for the current
Activity's saved state; it does not write a persistent preference. If the user exits before saving
a server and launches the app again without restored Activity state, the welcome appears again.
This repetition is intentional because the device still has no configured server.

`SessionManager.restore()` sets `AppAuthState.NeedsServer.firstRun` when it finds no stored server
URL. The explicit flag still distinguishes that launch path from an invalid stored value, the auth
gate's fallback, and **Change server**; those paths go directly to the server prompt without a
welcome on that transition.

- **Two columns**, hero left and steps right, with the button in a fixed slot beneath both. A
  single column does not fit: it measures ~506dp at Standard against the 486dp the safe area
  leaves, and ~705dp at `UiScale.Large` with the font scale at 1.30.
- **No internal scroll**, unlike the card form. This screen has exactly one focusable, and a
  remote cannot scroll a container with nothing to move focus toward — clipped content would be
  unreachable rather than merely off-screen. The fit is guarded by copy-length unit tests instead.
- **Backdrop**: two radial gradients (`primary` upper-left, `aurora` lower-right) over an opaque
  `background` fill, at the §3.1 alphas. No image asset, so it is resolution-independent and
  theme-correct. Outer stops are `color.copy(alpha = 0f)`, never `Color.Transparent` — the latter
  is transparent *black* and Skia's unpremultiplied interpolation leaves a grey halo.
  `minSdk 28` rules out `Modifier.blur` (31) and AGSL (33), and both named hardware targets sit
  below that line anyway.
- **Motion**: the §7.2 ambient loop, and nothing else. The loop drifts the gradient centers via
  `translate` inside `onDrawBehind` over a cached `Brush`, so it invalidates the draw phase only —
  rebuilding a `Brush.radialGradient` per frame would allocate a native shader 60×/s per layer.
- **No entrance animation, deliberately.** This screen composes as soon as `restore()` resolves,
  which on a first run is a single DataStore read, while §11.1.-1's splash stays opaque for
  `splashHold`. A section enter would run `page + 4 × stagger` = 540ms and finish before the fade
  even starts — motion nobody can see. A screen that is already settled when the splash lifts is
  the correct result here, not a missing flourish.
- **Prose uses `foreground`, not `mutedForeground`.** `mutedForeground` on `background` is 6.0:1,
  under the §12 body target before any gradient and ~4.3:1 over the glacier band. It is reserved
  here for the step numerals, which are non-prose.
- Each step is one node reading `"Step N of 3. <title>. <body>."`; the steps are **not** focusable,
  since they are not actionable and three dead stops between the screen and its only control is
  hostile with a remote. Both columns are traversal groups, or geometric sort interleaves them.
- The third step does **not** promise the profile picker. §11.1.1 skips that screen for a single
  profile with no PIN, which is exactly the first-run case, so naming it would be false in the
  first thing a new user reads. It describes profiles as a capability instead.
- Back exits the app. That is correct for a launch screen and is inherited rather than handled.

#### 11.1.1 Profile picker

"Who's watching?" on the auth canvas at 840dp — the width §11.1.3 already uses. A single
horizontal row of circular tiles, most recently used first, then an **Add profile** tile that
is always visible and never focus-gated (§6.2). Below the row, a ghost **Change server** row.

Tiles render from stored profiles alone, so the picker appears instantly and works with the
server unreachable. The stored copy is only as fresh as that profile's last completed sign-in,
so it decides **appearance and nothing else** — the PIN badge below may lag by one sign-in, and
whether a PIN is actually asked for is settled by the server (§11.1.2). Cap the row at
**6 profiles** so it never scrolls at any `UiScale`; past
that, "Add profile" explains itself instead of starting another pairing — and it explains only
what the app can actually do. Only the *active* profile can be signed out (§11.2), so the full-TV
message points at that, and must not instruct the user to sign out a tile the picker gives them no
way to sign out.

This screen is also where a sign-out lands, so it carries the `IglooNotice` slot for anything the
gate has to say about the action that just happened (§10) — above the row, announced.

- Avatar: 96dp circle. A remote avatar is fetched only when the stored value is an absolute
  `http(s)` URL — `openapi.json` does not define how a relative avatar path resolves — and
  falls back to the initial on `primary`.
- Focus ring: the one treatment (§6.1) at `radius.pill`, which on a square box reads as the
  circle it wraps. The tile's own `card @ 0.72` fill sits on the caption column and **rounds
  without clipping** (`background(color, shape)`, not `clip` + `background`) — a clip there
  would cut the avatar's glow at the tile boundary.
- A PIN-protected profile carries a badge in the `aurora @ 0.16 / 0.48` pair (§3.1).
- Initial focus is the last-used tile, so resuming is one OK press. The row does **not** wrap
  at either end (§6.3). Returning up from "Change server" lands on whatever was left in the
  row, including "Add profile".
- Each tile is one node: `"$name"`, or `"$name, PIN required"`. The avatar and badge are not
  announced separately. While signing in, the tile becomes a polite live region.

**A single profile with no PIN skips this screen entirely** and signs straight in; a launch
that already worked must not grow a screen.

#### 11.1.2 PIN entry

A convenience gate over a token that is already authenticated — never a second factor. The
server verifies it (`POST /api/user/pin/verify`), and a wrong PIN comes back as a success with
`valid = false`, so only a genuinely dead token ends the session.

**Whether this screen opens at all is the server's answer, not the vault's.** Selecting a tile
activates its token and fetches the user; `has_pin` on that response decides between the keypad
and the library. The stored flag is refreshed only when a sign-in completes, so trusting it would
skip the PIN once for anyone who set one after this TV was paired, and would strand anyone who
removed one on a keypad the backend answers with a 400 "no PIN is set". The cost is one
round-trip before the keypad appears, which the tile already covers with its "Signing in as
{name}" live region; the benefit is that the gate cannot be one sign-in out of date in either
direction. It is asked for wherever a **stored token is being resumed** — the picker and launch —
and not where the user has just proved who they are: a password login, a fresh pairing, the
sign-in behind a PIN this moment verified, or the periodic revalidation of a session already
running. A PIN set while someone is watching must not eject them mid-session.

**Remotes have no number keys.** This is the constraint the screen is designed around: a 3×4
on-screen keypad (1–9, delete, 0) plus four masked indicator cells on the 480dp card. Hardware
digits are accepted where they exist. An `IglooTextField` is wrong here — it would summon the
IME, which §11.6 reserves for search and login.

- The entered digits are never rendered and **never enter the accessibility tree**. The
  indicator row is a single node reading "PIN, N of 4 digits entered" as a polite live region;
  the cells themselves are hidden. Keypad keys announce their own label, not the PIN.
- The fourth digit submits automatically.
- A wrong PIN clears the cells and shows an assertive `IglooInlineError`; **focus stays put** —
  yanking it back to "1" after every miss is hostile with a remote.
- A rate limit surfaces the backend's own message; the app invents no cooldown of its own.
- Back returns to the picker with the profile still paired, and is **handled by this screen** —
  unhandled it reaches the Activity and closes Igloo, which is right for a top-level gate and
  wrong for one sitting below the picker. It stays live while a verify is in flight: a request the
  user no longer wants is exactly when they reach for Back, and the profile survives either way.

#### 11.1.3 Sign in

Text entry on a remote is painful. Quick-connect pairing is the primary path; email/password is
the fallback.

When this screen is reached by adding a user rather than by first-time setup, the "Change
server" slot becomes **Back to profiles** — changing the server wipes every stored profile, so
offering it mid-add is a trap. **Back takes that same slot's action**, and only then: during
first-time setup, and after the last profile signs out, there is nothing behind this screen and
Back correctly exits the app.

The quick-connect card **fits 960×540 without scrolling in its resting state** at Standard and
font scale 1.0 — measured, with the header at 38dp. That matters because focus lands on a bottom
control the moment the screen composes, so overflow would immediately scroll the header out of
sight. `QuickConnectLayoutTest` guards it, and the card form's internal scroll (§11.1) is left for
the degraded states — an inline error present, a larger `UiScale`, or a raised font scale.

Signing out the **last** profile lands here rather than on the picker, so this screen carries the
same `IglooNotice` slot (§11.1.1), on both the quick-connect and password paths. A notice is one of
those degraded states, and may scroll; what must hold is that it is announced and every control
stays reachable.

### 11.2 Navigation spine

Seven destinations, icon + label: **Search**, **Home**, **Movies**, **TV Shows**, **Music**,
**Photos**, **Settings**. Brand tile + wordmark at the top, the active profile's name and two
account actions at the bottom: **Switch profile** above **Sign out**.

The two are deliberately separate. Switch profile returns to the picker with the token intact;
sign out revokes the device token server-side and drops the profile from this TV. One control
doing both would either strand a credential on a shared TV or force a re-pair to hand over the
remote.

**Sign out asks first** (§9.3) — it is destructive, one press away, and reachable by whoever is
holding the remote. Switch profile does not ask: it loses nothing.

**Switch profile is not cancellable either**, for the same reason sign-out is not. It drops the
in-memory credential before it publishes the picker, so a caller torn down in between — an
Activity recreated by a `UiScale` change while the transition waits its turn — would leave the app
authenticated with no token, and the next 401 would take that profile off the TV. It runs on the
application scope and only the *waiting* is cancellable.

Three rules the sign-out flow follows, all of them consequences of this being a *shared* TV:

- **It only ever affects the profile signing out.** Every other stored profile keeps its token and
  signs back in without re-pairing. One device token belongs to one person, so the backend revokes
  exactly the token the request carried, and locally only that one profile leaves the vault. The
  image caches go too — they are keyed by URL with nothing tying an entry back to a profile, so the
  whole cache is dropped rather than guessed at, and the remaining profiles re-fetch one avatar
  each. Switch profile does not clear them: nobody is being removed.
- **The local half is unconditional.** If the revoke cannot be delivered — the TV is offline, the
  server is down — the profile still leaves this TV, because the alternative is stranding a live
  credential on a shared device. The gate then carries an announced `IglooNotice` saying the server
  may still list this TV as signed in. Sign-out is never blocked on the network.
- **A 401 on the revoke is success**, not a lost session. The token it would have revoked is
  already gone, which is what the request wanted; the user must not be told their session
  "expired" after deliberately ending it.

Active destination uses `primary @ 0.18` fill plus a `sidebarPrimary` icon; the focused row uses
`card @ 0.72`. Both render simultaneously when the user is focused on the active destination
(§3.1).

The rail rests collapsed and expands on focus (§8.1). **Back is three-state**: Back from the
content pane opens the rail on the current destination's row (§6.3); Back again exits the app; a
rail entered by d-pad left instead returns focus to the content. Activating a destination hands
focus to the content pane — that focus move is also what collapses the rail.

TalkBack: the rail and the content pane are separate traversal groups. The brand lockup is
decorative and silent; the footer's avatar and name read as one node, "Signed in as {name}"; the
active destination announces through the `selected` semantics property, not a label suffix —
never announce text the collapsed rail has faded out.

The footer must remain visible at every `UiScale` — at 540dp tall this is the tightest
constraint in the app and the first thing to break. It now carries two rows rather than one,
so re-verify it at `UiScale.Large` after any spine change.

### 11.3 Home

Stacked horizontal rails — continue watching, latest movies, latest albums, watch rooms — over
an optional hero. This is the most TV-native layout in the product and the model for other
index screens. Vertical d-pad moves between rails; horizontal moves within one; focus is
restored per-rail on return.

### 11.4 Movies

- **Index** — heading, stats, tab control (All / Genres / Playlists), then a poster grid at
  `gridColumns` with A–Z sort and pagination. Numbered pagination, not infinite scroll: a
  remote user needs a bounded, predictable focus target.
- **Detail** — full-bleed backdrop with a `background` gradient scrim, content pulled up over
  it. Poster left; title, tagline, metadata chips, genres, and hero actions right. Hero actions:
  **Play**, **Watched** toggle, **Like**, **More**. Below: cast, chapters, extra details.
  Play must be the first focused element on entry.

### 11.5 Music

Four tabs: **Musicians** (circular cards), **Albums** (square cards), **Tracks** (flat list with
letter headers, plus Play all / Shuffle all), **Playlists**. Album and musician detail follow the
backdrop + hero + list pattern.

Track rows carry a play action, a like toggle, and an overflow menu — **all three focusable**,
none hidden until focus.

### 11.6 Search

Reached from the spine. A query prompt, then results across **All / Movies / Albums / Musicians
/ Tracks**. The All tab stacks up to four sections, each with a count and a "See all" into that
category. Paginated, 24 per page.

Prefer the on-screen keyboard and voice input; keep required text entry to search and login.

### 11.7 Settings

Tabbed child routes: General / Account / Libraries / Playback / Users (admin only).

**General must expose the `UiScale` picker (§2.3) and the light/dark toggle** — without the
picker, the scale model is inert.

Account owns PIN management (`PUT /api/user/pin` accepts a device token). Not built yet: the
TV can verify a PIN (§11.1.2) but cannot yet set or clear one.

### 11.8 Playback

Media3 / ExoPlayer. Direct play or backend-produced HLS; **never client-side transcoding**;
preserve audio passthrough.

Chrome is a top bar (title + back) and a bottom control bar that **auto-hide after idle** and
reappear on any d-pad or media-key event. Controls: seek bar, current/total time, rewind,
play/pause, fast-forward, quality chip, chapters, volume. A **Resume** dialog offers resume vs.
start over.

D-pad and media-key mapping:

| Input | Action |
|---|---|
| Center / Play-Pause | Play / pause |
| Left / Rewind | Seek back |
| Right / Fast-Forward | Seek forward |
| Up / Down | Show chrome, move between controls |
| Back | Exit (or dismiss chrome first) |

Progress saves to the backend every 15s, starting only after ~15s of real playback.

**Media3 does not go through the app's Ktor client**, so playback requests carry no
`Authorization` header and their 401s never reach the session state machine. When the player
lands it must inject the bearer from `DeviceCredentialSource` and bridge a 401 from
`HttpDataSource.InvalidResponseCodeException` into `AuthEventBus.signalUnauthorized`. Every
media route inherits the global security block; there is no signed-URL escape hatch.

### 11.9 Notifications

A badge on the spine when unread. At 10 feet a small anchored popover reads poorly — prefer a
**full side panel**. Every row is focusable, and a row's "mark read" and its dismiss action are
**two separate focus targets**. Unread rows are tinted `muted` with a glacier dot.

The unread count polls every 30s; the full list is fetched only while the panel is open.

---

## 12. Accessibility

Non-negotiable. `AGENTS.md` §Accessibility governs; this section covers the design-system side.

- **Every screen works with D-pad only and with TalkBack on.** Both, at TV viewing distance.
- **Focus visibility** is the accessibility feature on TV, more than any label. One treatment,
  always visible, never ambiguous (§6.1).
- **Labels**: every actionable element has a meaningful content description. Media cards
  announce what matters *in context* — a poster in a grid may need only its title, while a
  continue-watching card needs title, year, and progress. Decorative images are hidden from
  the accessibility tree.
- **No focus traps**, and no custom focus handling that breaks screen-reader traversal.
- **State changes are announced**: loading → loaded, empty results, errors, and action failures.
- **Contrast**: body text targets ≥7:1; all other foreground/surface pairs ≥4.5:1, in both
  themes. The paired token structure (§3) is what makes this hold.
- **Reduced motion** is honored globally through `iglooTween` (§7.1).

### 12.1 The font-scale clamp — stated openly

System font scale is clamped to **`[0.85, 1.30]`** (§2.6). This bounds a user-facing
accessibility setting, which deserves an explicit justification rather than silence:

- Android's nonlinear font scaling reaches 2.0×. Combined with `UiScale.Large` that is ~2.3×
  text inside containers sized for ~1.15× — text would win, and layouts would break in ways
  that *lose information* rather than enlarge it.
- The clamp is **1.30, not 1.0** — most of the range is preserved.
- `UiScale.Large` stacks on top, so effective text still reaches ~1.5×, through a path the
  layout is explicitly designed and tested for.
- The `heightIn(min=)` discipline (§2.6 point 2) is the primary defense; the clamp is
  belt-and-braces.

**This is the design-system decision most worth revisiting.** If the `heightIn` discipline holds
across the full app, the clamp should be loosened or removed.

---

## Appendix A — web parity

The Igloo web client (`../Igloo/web`) is the origin of the **palette only**. It is a pointer-and-
mouse product; its type scale, spacing, component sizes, and hover affordances do not transfer,
and this document does not mirror them.

| Concept | Web | Here |
|---|---|---|
| Tokens | CSS custom properties, OKLCH | `IglooColors` data class + `CompositionLocal` |
| Theme switch | `.dark` class on `<html>` | Palette selected from persisted mode, dark default |
| Alpha | `bg-primary/90` | `Color.copy(alpha = 0.90f)` |
| Radius | `--radius` + aliases | `IglooTheme.radius` |
| Reveal trigger | `hover` / `group-hover` | **Focus** (§6) |
| Reduced motion | `motion-reduce:` variant | `iglooTween` (§7.1) |
| Focus ring | `focus-visible:ring-[3px]` | The one focus treatment (§6.1) |
| Lists | CSS grid, responsive breakpoints | `LazyRow`/`LazyColumn`/`LazyVerticalGrid` + `gridColumns` |
| Type scale | Tailwind utility literals | Seven authored styles (§4) |

Shared: the 15 color values, the three motion durations (150/200/300ms), and the 1.05× focus
scale. The web's 0.20 focus-glow alpha did **not** transfer — see §6.1 for the platform reason.

**Web-side styling issues are tracked in the Igloo repo, not here.** That repo is a sibling
checkout and is out of scope for this one; do not modify it. The one parity risk worth knowing:
the web palette has no machine-readable token source — the hexes live in CSS comments — so a
web palette change will not announce itself. Re-verify §3 against `web/src/assets/styles.css`
when syncing, and update the "Last verified" stamp at the top of this document.

---

## Appendix B — token→code index

| Token group | Defined in |
|---|---|
| Colors (§3) | `core/design/IglooColors.kt` |
| `UiScale`, `viewportFactor`, `effectiveScale` (§2) | `core/design/UiScale.kt` |
| Spacing, radius, sizes, icons, focus, layout (§5, §6, §8) | `core/design/IglooDimens.kt` |
| Type styles (§4) | `core/design/IglooTypography.kt` |
| Durations, easings, `iglooTween`, `splashHold` (§7) | `core/design/IglooMotion.kt` |
| `iglooAuroraBackdrop`, `iglooEnterStagger` (§7.2, §11.1) | `core/ui/IglooBackdrop.kt`, `core/ui/IglooEnterStagger.kt` |
| The `background @ 0.60` scrim (§3.1, §9.3) | `core/ui/IglooScrim.kt` |
| `IglooTheme` accessors, `iglooSafeArea()`, `Dp.scaled()` | `core/design/IglooTheme.kt` |
| `UiScale` persistence (§2.3) | `core/storage/UiPreferencesStore.kt` |

Unit tests in `app/src/test/java/.../core/design/` assert that the Standard values in §4 and §5
match the code exactly. **If you change a number in this document and the tests still pass, you
forgot to change the code.**

---

## Changelog

**2026-08-10 — Home gets its first rail: Recently Added Movies.**

The §11.3 layout starts landing: the Home destination drops the placeholder hero and renders a
scrollable column of rails — one rail today, `GET /api/movies/latest` behind it. Other
destinations keep the placeholder pane until their screens land. New primitives in §9.1:
`IglooMediaRail`, `IglooPosterCard`, `IglooEmpty` (minimal only).

- **§10's shimmer sentence lost to §7.2's loop rule.** A shimmer is a loop; only §11.1.0's
  ambient drift may loop. Skeletons are static blocks at full motion too, not only under
  reduced motion. Revisit only by authoring an explicit carve-out in §7.2.
- **Loading and empty states are focusable anchors.** The content pane must always own exactly
  one focus target or the shell's initial focus, d-pad-right from the spine, and the Back model
  all break. So the skeleton's first cell and the empty state's frame take the §6.1 treatment
  and carry the pane's entry requester; the skeleton cell announces the load politely. When
  content replaces a focused skeleton, the rail re-requests focus onto the entry card — a
  disposed focused node otherwise drops focus on the floor.
- **§6.3's per-rail restore, implemented:** the remembered card is per rail, hoisted above the
  destination switch, and saved across process death. A rail rebuilt on re-entry is *created
  scrolled to* the remembered card, because a focus requester can only land on a composed node.
- **§8.3's bleed-past-the-safe-area is deferred**, and the rail's horizontal extremes trim the
  focus glow — the same accepted ≤1.5:1 artifact §6.1 records for the spine. The §8.2 peek
  affordance falls out of the pane arithmetic unchanged.
- **The poster proxy is authenticated, and the image loader now knows it.** `/api/tmdb/images/…`
  requires the device bearer like every other endpoint, so the app installs a Coil interceptor
  that attaches it — **scoped to the active server origin only**, because avatars are arbitrary
  absolute URLs and the token must never travel to a foreign host. Poster fetches bypass the
  Ktor client, so an image 401 renders the placeholder and can never sign a profile out.

**2026-08-09 — Switch profiles, hardened: the PIN gate stops trusting a cached flag.**

- **§11.1.2 gains "whether this screen opens at all is the server's answer".** The vault's `hasPin`
  is only refreshed when a sign-in completes, so it was a sign-in out of date in both directions: a
  PIN set elsewhere after pairing was skipped once, and a PIN removed elsewhere left the keypad up
  in front of a backend that answers that verify with a 400. `has_pin` now comes off the
  `GET /api/auth/user` response the sign-in already makes, which costs no extra request. Records
  where the gate applies (a resumed token) and where it must not (credentials just proved, and
  revalidation of a live session).
- **§11.1.1**: the stored copy of a profile decides appearance and nothing else, so the PIN badge
  is allowed to lag by one sign-in.
- **§11.2**: switch profile joins sign-out in being uncancellable — it drops the credential before
  it publishes the picker, and a caller disposed in between would strand the session.
- **§11.1.2 / §11.1.3 spell out who owns Back.** Found on the emulator: Back on the PIN gate closed
  Igloo instead of returning to the picker, and did the same on the add-a-profile sign-in. §11.1.2
  had always said Back returns to the picker; nothing handled it. Both screens sit *below* the
  picker and now handle their own, while every gate that is genuinely top-level still inherits the
  exit.

**2026-08-09 — Sign out, reviewed on hardware: what the modal owes a revoke it can no longer stop.**

Verified end-to-end on the Shield against a live server with two real profiles: signing one out
revoked exactly that device token server-side and left the other's usable without re-pairing.

- **§9.3 gains "pending outlives the modal".** Back closes the dialog while the work continues, so
  the pending flag must survive dismissal and only clear when the action finishes. Otherwise the
  control reopens a confirmation for something already committed locally, offering a Cancel that
  undoes nothing.
- **§3.1 rewritten to describe the mechanism it actually mandates.** "The rail yields its scrim"
  was true of the result but not of the code: the host *unmounts* the rail's scrim node for the
  modal's lifetime — animating it out would composite a fading 0.60 layer under the reveal — while
  leaving the rail's own animated value at its 0.60 target so cancelling restores it in one frame
  rather than fading it in under the focus ring the rail is getting back.
- **§11.2**: the "only the profile signing out" rule now covers the image caches, which are keyed
  by URL with nothing tying an entry to a profile. Switch profile leaves them alone.

**2026-08-09 — Sign out, finished: it asks first, and the revoke cannot be quietly lost.**

- **New §9.3 — the confirmation dialog**, the first modal in the app. An in-tree overlay rather
  than `androidx.compose.ui.window.Dialog`, and the four reasons why, starting with the fact that a
  dialog window re-provides `LocalDensity` and would silently drop §12.1's font-scale clamp.
  Records dismiss-focused-first, the `FocusRequester.Cancel` trap, focus restore through the
  invoker's own requester in its dismiss callback (not an effect — the success path disposes the
  screen in the same frame), the pending recipe, and that a dialog never carries an inline error.
- **§3** gains `destructiveForeground`, ported from web (`--destructive-foreground`): 6.76:1 dark /
  4.83:1 light — the weakest paired token in the app, admissible on a control label and never on
  prose. **§9.1**: `IglooButton` gains the `Destructive` variant that uses it, plus `IglooNotice`,
  `IglooScrim`, and `IglooConfirmDialog`.
- **§3.1**: the `background @ 0.60` row now covers the modal scrim too, and **only one scrim renders
  at a time** — two composite to 0.84, which this table does not authorize. `0.40f` disabled extends
  to `destructive`.
- **§10**: a pending mutation is shown on the control that started it, never app-wide. Action
  failures were specified as a "transient message" that nothing implemented; they are now an
  announced `IglooNotice` on the screen the app lands on, with the reason a self-dismissing toast is
  the wrong shape for a remote.
- **§11.2**: sign out asks first, and the three rules it follows on a shared TV — it affects only
  the profile signing out, the local half is unconditional (an undeliverable revoke still drops the
  profile, and says so), and a 401 on the revoke is success rather than an expired session.
- **§11.1.1 / §11.1.3**: both gates carry the notice slot, because signing out the last profile
  lands on sign-in rather than the picker. `AppAuthState.NeedsLogin` gained the `notice` field that
  made this possible — until now a revoked *last* profile lost its "session expired" message
  entirely, since `gate()` only passed a notice to the picker.

**2026-08-09 — Main navigation: the collapsible spine.**

- **§8.1: `navSpineWidth` split** into `navRailCollapsedWidth` (128dp = 48dp viewport-corrected
  safe area + 80dp scaled icon strip) and `navRailExpandedWidth` (236dp). The rail rests
  collapsed and expands over the pane behind a `background @ 0.60` scrim while d-pad focus is
  inside it; the pane is padded by the collapsed width only. §2.3 and §8.2's arithmetic redone
  against the 752dp pane.
- **§3.1** gains the `background @ 0.60` scrim row.
- **§11.2: seven destinations** — Search joins, first. Records the three-state Back model and
  the collapse/expand behavior.
- **§6.3**: the spine is carved out of last-focused restoration — re-entering it lands on the
  current destination's row.
- **A11y decisions recorded in §11.2**: decorative brand lockup, merged "Signed in as" footer
  node, and the `selected` semantics property instead of a ", selected" label suffix.

**2026-08-08 — The focus treatment is real.**

§6.1 had specified five properties since it was written; two were implemented. `IglooFocus.scale`,
`glowAlpha`, and `glowElevation` had no production consumers at all, and because `ring` and
`primary` are the same value in dark, a focused `Primary` button showed *no* visible indicator —
it was less delineated focused than at rest, on two of the three first-run screens.

- **`focusRing` now implements all of §6.1** and owns the clip, and therefore the fill. All seven
  call sites dropped their `.clip(shape).background(x)` and pass `fill =` instead. §9.1 updated.
- **Added the separator row to §6.1** — the focused fill contracts to leave a real gap, which is
  what makes a glacier ring legible on a glacier fill. Measured 3.31:1 on a Shield.
- **Corrected §6.1's glow.** The prescribed `spotColor = ring.copy(alpha = 0.20f)` yields 1.06:1
  and could never have worked: the platform applies its own 0.19/0.039 shadow alphas. Colours now
  go in at full alpha, `ambientShadowColor` included, and the glow is documented as ambience
  rather than as the indicator. **§3.1's `0.20f` ring row is gone**, as is the `glowAlpha` token —
  no pixel read it, and Appendix B's rule makes a test-guarded number with no pixel a defect.
- **§11.1.1's profile tile** no longer specifies its own 1.06× over `MICRO_MS`, which contradicted
  the 1.05f token, §6.1, §7's duration table, and a passing unit test. It takes the one treatment.
  Its column now rounds without clipping so the avatar's glow is not cut.
- **New `FocusTreatmentTest`** asserts pixels, not semantics — the whole existing suite asserts
  `assertIsFocused()`, which cannot tell "focused" from "invisible", which is why this survived.
  Verified to fail on the restored defect.
- `docs/focus-treatment-gap.md` (the bug report) is resolved and deleted; its arithmetic, and the
  correction to its recommended fix, are in §6.1.

**2026-08-04 — Launch splash.**

- **Added §11.1.-1 (launch splash)** — the system window splash and the Compose splash as one
  canvas, the 120dp screen-centred tile both layers must agree on, why the backdrop does not loop
  there, and why the splash is gated on the boot rather than on `AppAuthState.Loading`.
- **§7** gains `splashHold` (900ms), stated as a hold that reduced motion does not shorten.
- **§7.2** now says explicitly that the splash reuses the backdrop without extending the loop
  carve-out, which still names only §11.1.0.
- **§10** distinguishes the once-per-boot splash from the app-wide pending screen.
- `welcomeBackdrop` and the welcome's private `enterStagger` became `iglooAuroraBackdrop` and
  `iglooEnterStagger` in `core/ui/`, shared by §11.1.-1 and §11.1.0 (Appendix B).
- `ic_igloo_mark`, `ic_launcher`, and `igloo_banner` now draw the same brand tile, sized inside
  the circle the system masks splash icons to.
- **§11.1.3** records the quick-connect card's vertical budget, now measured rather than assumed,
  and `QuickConnectScreen` gained a view-model-free `QuickConnectContent` so it can be measured at
  a fixed phase.

**2026-08-04 — Clarified incomplete server setup relaunches.**

- **§11.1.0** now records that **Get started** is an Activity-scoped dismissal, not a persistent
  preference. Until a valid server URL is saved, a fresh launch intentionally shows the welcome
  again; reconnect and **Change server** transitions continue to skip it.

**2026-08-03 — Multi-profile auth, and the first-run welcome.**

- **Added §11.1.0 (first-run welcome)** and split §11.1's canvas into a card form and a hero form.
  Records the initial server-setup trigger, why the screen does not scroll, and why the third step
  does not name the profile picker.
- **§3.1** gains the two welcome-backdrop alpha rows — the only alphas in the system that differ
  by theme, with the reason stated.
- **§4** gains `display` (44/52sp), sized against the vertical budget rather than by eye. The
  anticipated-styles note now lists only `caption`.
- **§7** gains `stagger` (60ms) and `ambient` (24000ms). **§7.1**'s rule now names
  `infiniteRepeatable` and `rememberInfiniteTransition` too, and `iglooTween` gained `delayMillis`
  so a stagger cannot be written without it. **§7.2** gains a four-condition carve-out for a
  looping decorative backdrop, and states the `graphicsLayer`-not-`offset`,
  not-`AnimatedVisibility` rules for section enters.
- **§9.1** gains `IglooBrandMark`, which replaces three drifted inline copies of the "I" tile —
  one of which used a bare `48.dp` literal and another `radius.xl` against §5.2.
- **Split §11.1** into the profile picker (11.1.1), PIN entry (11.1.2), and sign in (11.1.3).
  Records why one device token per person forces a picker, and why the PIN keypad is on-screen
  rather than an IME field.
- **§11.2** — the spine footer gains **Switch profile** above **Sign out**, and the note about
  footer height at `UiScale.Large` is now load-bearing.
- **§11.7** — Account owns PIN management; the TV can verify a PIN but not yet set one.
- **§11.8** — added the note that ExoPlayer bypasses the Ktor client, so playback must inject
  the bearer token and report its own 401s.

**2026-08-02 — TV-first rewrite.**

- Restructured from a web→Android porting memo into a TV design system. Web material reduced to
  Appendix A.
- **Added §2 (Screen & scale model)** — previously absent. Records that Android TV reports
  ~960×540dp regardless of physical size, and introduces `UiScale`, the density-sanity guard,
  the safe area, and the font-scale policy.
- **Removed the claim that non-color scales are "theme-independent constants."** That statement
  was the reason `radius`/`spacing`/`typography` were compile-time constants that could not vary
  by user preference or viewport.
- **Added concrete TV numbers** for type, spacing, component sizes, focus, motion, and layout
  geometry. These previously existed only in Kotlin, making the code the de-facto source of
  truth in contradiction of `AGENTS.md`.
- **Corrected**: guidance to use `TvLazyColumn`/`TvLazyRow` from `androidx.tv.foundation`. Those
  APIs do not exist in the pinned 1.0.0 artifact (§8.3).
- Collapsed the former §4/§5 (web-repo issue list and suggestions) into the parity note in
  Appendix A.
