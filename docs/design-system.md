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

- Applied via `Modifier.iglooSafeArea()` on full-screen non-shell surfaces (the auth canvas, the
  welcome screen), **not** globally at the root and **not** on the shell's content pane. The pane
  hands its sections a `contentInset` instead and each applies what it owes (§8.1) — a container
  that insets everything cannot let a backdrop through.
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
| `0.50f` | `muted` / `border` | Tab strip ground / hairline (§9.1) |
| `0.26f` dark / `0.16f` light | `primary` | Welcome backdrop core (§11.1.0) |
| `0.18f` dark / `0.10f` light | `aurora` | Welcome backdrop core (§11.1.0) |

The two backdrop rows are the only alphas that differ by theme, and they differ because the same
alpha does opposite things: dark `primary` (#38BDF8) *raises* luminance toward the text color,
while light `primary` (#0369A1) is a heavy navy that *drops* a bright canvas fast. Composited
luminance under text must stay ≤ 0.093 in dark and ≥ 0.345 in light — the §12 7:1 boundaries.

**Focus vs selected is a real distinction**: `card @ 0.72` means "the remote is here right now";
`primary @ 0.18` means "this is the active destination". Both can be true at once, and the nav
spine renders them together.

**Recession is a mix, not an alpha.** A `Primary` button whose row has focus elsewhere steps back
to `lerp(primary, background, 0.30f)` — `#2A8AB8` in dark, `#4B94BC` in light. It is deliberately
*not* expressible in this table, for two reasons. `primary @ 0.40` is already the disabled control
above and composites over the canvas to `#1C5778`, so recession written as an alpha would collide
with "you cannot press this" — and the mix has to stay measurably clear of it. And an alpha would
let a backdrop through the one control on the detail hero that must stay solid. The label follows
by measured contrast rather than by pairing: recession moves the fill toward the canvas, which in
light lightens it under a white `primaryForeground` and drops that pair to 3.35:1, so `foreground`
takes over there (5.55:1) while dark keeps `primaryForeground` (4.83:1). Both live in
`core/design/IglooColors.kt` as `recessedPrimary()` / `recessedPrimaryContent()`, and
`RecessedPrimaryTest` pins the values, the opacity, the distance from disabled, and both labels.
See §6.1 for when a control recedes.

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
- Hero side gradient (§11.3.1): `Brush.horizontalGradient`, `Color.Black.copy(alpha = 0.70f)` →
  `0.35f` → transparent, left to right — web parity with the detail hero's side scrim. The web's
  third, theme-tracking fade into the page background is deliberately **not** ported: it blends an
  unclipped hero into the canvas, and our hero is a clipped card — a theme-tracking brush over
  media would contradict this section.
- Text over media: `Color.White`, with a shadow for legibility. Secondary text over media —
  the detail hero's tagline, runtime and date line, and resume caption, and the theater card's
  year — steps to `Color.White.copy(alpha = 0.85f)`, and tertiary text (the detail hero's
  genres line) to `0.75f`, so the hierarchy the token ramp (`foreground` → `mutedForeground`)
  draws on canvas survives over media. Measured where the detail scrim is weakest under the
  text column (0.62–0.71), the dimmest of these still renders at 12.6:1 — these are vocabulary
  rows, not a contrast concession.
- Theater-card scrim (§11.3.2): `Brush.verticalGradient`, transparent → `Color.Black.copy(alpha
  = 0.50f)` → `0.90f`, over the poster's lower third.
- Rating badge (`core/ui/RatingBadge`), critic-score tiers: ≥ 7 `aurora` / `auroraForeground`;
  5–7 `aurora.copy(alpha = 0.80f)` / `auroraForeground`; < 5 `Color.Black.copy(alpha = 0.60f)` /
  `Color.White`. Aurora is licensed over media because §3.1 pins it identical in both themes;
  the web's `muted` low tier tracks the theme and is deliberately **not** ported — the black
  literal is its dark-mode equivalent. The badge paints its own ground, so it holds on the
  no-poster fallback too and is the one item here that survives it.
- Chip and control ground over media (§11.4): fill `Color.Black.copy(alpha = 0.45f)`, border
  `Color.White.copy(alpha = 0.25f)`, label `Color.White.copy(alpha = 0.90f)`. Used by the
  certification and media-info chips, and by the detail hero's `Ghost` buttons — a Ghost
  button's transparent ground and token label are licensed only on a token canvas, and its
  `card @ 0.72` focus fill tracks the theme, which over media this section forbids. While a
  control carries this ground it keeps it through focus: the ring, glow, and scale carry the
  focus signal instead. Off media, all of it falls back to `muted` / `border` / `foreground`.

These literals are licensed **only by media actually behind them**. A surface that would carry
them but has no image — a hero with no backdrop, a theater card with no poster or one that failed
to load — drops its scrim and falls back to token colors on its `card` or `muted` fill.

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
- Home hero (§11.3.1): title `maxLines = 2`, overview `maxLines = 3` — it is a card, so it takes
  card clamps
- Detail overview (§11.4.1): `maxLines = 6`, and it **fades** at the bottom edge instead of
  ellipsizing (`TextOverflow.Clip` plus a `background`-token gradient drawn only when the text
  actually overflows). An ellipsis mid-sentence reads as punctuation; the fade says plainly that
  the prose continues past what fits. The fade is for multi-line prose the user came to read —
  single-line metadata keeps the conventional end-of-line ellipsis
- Display-on-canvas hero copy (§11.1.0), the pairing code, error messages: **never truncate** —
  pass `overflow = TextOverflow.Visible` and let the container grow. The never-truncate rule is
  scoped to the full-bleed canvas, where the container can grow; a clamped card cannot.

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

**The separator makes focus legible on a `Primary` button; it does not make it the loudest thing
in a row.** Everything above reasons about one control in isolation, which is not the situation a
row of actions creates. Because `ring` and `primary` are the same value, a resting `Primary` fill
and a focused sibling's ring are the same colour — and the fill wins on area. Measured on the
detail hero with Watched focused, the *unfocused* Play carried **4.8x** the glacier pixels of the
focused control's ring (21 036 vs 4 377). At ten feet the eye goes to the solid block, and the
press marks the movie watched instead of playing it.

So a `Primary` button in a row of actions **recedes while a sibling holds focus** (§3.1's mix,
`IglooButton`'s `recessed` flag), and returns to full `primary` when it is focused itself or when
the row has no focus at all. It is presentation only: a recessed button is still enabled, still
clickable, and announces nothing about being recessed. This does not touch the treatment above —
the ring, separator, scale and glow are unchanged; the resting control gets quieter instead of the
focused one getting louder, which is what keeps the one treatment one treatment. After the change
the same measurement finds no glacier at all in Play's bounds, so the focused control is the only
glacier in the row.

The rule is scoped to a **row of peer actions**, not to every `Primary` on screen: elsewhere the
app puts exactly one `Primary` per screen (§9.1), where there is no sibling to lose the eye to.

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
| `dialogWidth` | 480dp *(the centered dialog and error card — §9.3, §10)* |

**The pane is the whole panel. The gutter is a property of what sits in it.** The content pane
applies no padding of its own; it hands its sections a `contentInset` and each applies the part it
owes. Chrome and text take it, a backdrop does not, and a rail's scroll surface carries it as
`contentPadding` (§8.3) so cards run off the physical edge instead of stopping short of it.

At Standard on the 960dp reference viewport that inset is 128 (collapsed rail, which absorbs the
left safe area) + 32 (content gutter, `spacing.xl`) on the start and 48 (right safe area) on the
end, leaving **752dp of inset content measure**. That is the budget everything in §8.2 is sized
against — but it is a text measure, not a clip. `IglooDimensTest` pins the arithmetic.

The rail **overlays**, it does not displace: it rests as a horizontal scrim (§11.2) over content
that reaches x = 0, and the expanded rail costs the pane nothing.

⚑ *This was a hard `padding()` on the pane container until 2026-08-19, which boxed every screen
into 752×486dp of a 960×540dp panel. See the revision entry — the failure mode is worth knowing.*

The spine is the primary vertical d-pad target and is always visible, resting as the collapsed
icon strip. It expands to `navRailExpandedWidth` exactly while d-pad focus is inside it,
overlaying the pane behind a `background @ 0.60` scrim (§3.1); the pane is padded by the
collapsed width only and never reflows. Labels fade in paint only (§7.2) — every row is composed
and focusable in both states, so the d-pad and TalkBack see the same tree open or shut. There is
no drawer, no hamburger, and no fully-hidden mode.

### 8.2 Media geometry

| Token | Standard | Notes |
|---|---|---|
| `posterWidth` | 148dp ⚑ | 752dp of inset measure ⇒ 4 posters + a peek, which cues scrollability |
| `posterAspect` | 2:3 | `Modifier.aspectRatio(2f / 3f)` |
| `wideCardWidth` | 264dp | Backdrop / episode / extra-video cards |
| `wideAspect` | 16:9 | |
| `albumAspect` | 1:1 | Album art is square |
| `gridColumns` | 6 / 5 / 4 ⚑ | Compact / Standard / Large — *unscaled, and inverse* |

Album art is square and musician thumbnails are circular. Both use `posterWidth` as their base
width, so an album card is a movie poster's width and a movie poster's width tall; the circle is
`IglooPosterCard` at `artworkRadius = radius.pill`, which `focusRing` clamps to a circle.

`IglooPosterCard` takes the aspect and width as parameters (`posterAspect` / `posterWidth` by
default; `albumAspect` for album art, `wideAspect` with `wideCardWidth` for video cards) rather
than owning them, and `IglooMediaRail` takes the same pair for its skeleton — §10's
grid-matching rule is only true if the placeholder is the shape of the card replacing it.

**A partially-visible next card is a feature.** It is the only affordance telling a remote user
the rail continues — and since §8.3 it is a card genuinely cut by the panel's edge, not one
parked short of a gutter, which reads as the end of the list rather than the middle of it.

### 8.3 Rails and grids

> **Use `androidx.compose.foundation`'s `LazyRow` and `LazyColumn`.**
>
> `TvLazyRow` / `TvLazyColumn` **do not exist**. They were removed before
> `androidx.tv:tv-foundation` reached 1.0 — the pinned `1.0.0` artifact contains only
> `ExperimentalTvFoundationApi`, `TvImeOptionsKt`, and `TvKeyboardAlignment`. Older guidance
> and blog posts still reference them; **do not reintroduce them**, and do not add
> `tv-foundation` as a dependency for list purposes.

Grids use `LazyVerticalGrid` with `GridCells.Fixed(IglooTheme.layout.gridColumns)`. A card in a
grid cell passes `width = Dp.Unspecified` so it fills the cell it was already given; the rails'
fixed `posterWidth` would leave ragged gutters.

**Paged grids append; they do not repaginate.** A `LazyVerticalGrid` backed by a paged endpoint
keys its real cells and its tail cells in one disjoint string space (`movie_$id` versus
`tail_skeleton_$i`), and the tail keys index *within the tail* so an append never renumbers them
— stable keys are the whole reason focus stays on a card while a page lands underneath it. The
prefetch trigger reads `LazyGridState.layoutInfo` **through `derivedStateOf`** and compares the
last visible index against the *real* item count, never `layoutInfo.totalItemsCount`, which
includes the tail and would drift as the tail changed shape. `layoutInfo` changes on every
scroll frame, and on a TV every d-pad press is a scroll frame, so reading it directly in
composition subscribes the entire grid to a per-frame value. Two things must be hoisted above
the pane's destination branch: the `LazyGridState`, because `ContentPane`'s `when` has no
`SaveableStateHolder` and a `rememberSaveable` inside the removed subtree is discarded on a
destination switch; and whatever records that a scroll-to-top has been handled, or re-entering
the pane replays it. The item list itself lives in the view model and never in `rememberSaveable`
— a five-thousand-item library would exceed the saved-state `Bundle` limit and crash on process
death.

**Rails pad their content and let the scroll surface bleed past it.** `IglooMediaRail` takes the
pane's `contentInset` and splits it by node:

| Node | Gets the inset as |
|---|---|
| The heading, the empty state, the error card | `padding` — they are text |
| The `LazyRow` | `contentPadding`, plus `fillMaxWidth()` |
| The loading skeleton | `padding(start)` only — its cells are meant to run off the end |

The `LazyRow` needs **both**: `contentPadding` puts the gutter inside the scroll surface so the
first card rests at the inset while the last scrolls past the panel edge, and `fillMaxWidth()`
because a lazy list otherwise sizes to its content and a short rail would end where its last card
does. **The parent must not pad horizontally**, or it re-clips the surface and the inset silently
becomes a gutter again — which is exactly how this went unimplemented from the TV-first rewrite
until 2026-08-19.

---

## 9. Components

### 9.1 In-repo primitives (`core/ui/`)

| Composable | Notes |
|---|---|
| `IglooText` | Wraps `BasicText`. Takes explicit `style` and `color` — there is no ambient text style, by design. |
| `IglooButton` | `heightIn(min = sizes.controlHeight)`, radius `lg`, focus per §6.1. Three variants: `Primary`, `Ghost`, `Destructive` (`destructive` fill / `destructiveForeground` label, §3). Optional leading `icon` at `icons.md`, `spacing.sm` from the label. A toggle passes `stateDescription` and `actionLabel` so TalkBack announces the state it is in and the action a press performs, not just a label (§12). `restingFill` / `contentColor` carry the §3.2 over-media ground where the button sits on a backdrop. `recessed` steps a `Primary` fill back to §3.1's mix while a sibling in the same row holds focus (§6.1); it is presentation only and never reaches the semantics. `labelVariants` lists every label a toggle can show so the button reserves the widest, making the flip a repaint instead of a relayout that shoves the row's siblings — the variants are laid out invisibly in the button's own style (a fixed width would drift under localisation) and never reach the semantics tree. |
| `IglooFilterChip` | One choice in a row of mutually exclusive filters (the Movies index's genre picker, §11.4): `heightIn(min = sizes.controlHeight)`, radius `lg`, `spacing.md` horizontal padding, `bodyMedium` label. Selection and focus compose rather than compete — selected is a `primary` fill that holds while unfocused, focus is the §6.1 ring/scale/glow over whatever fill the chip has; unselected rests as the ring's hairline border and takes the Ghost focus fill. Not an `IglooButton` variant: a selected-state fill on an unfocused node is outside the button contract. Built on the internal `SelectablePill`, which carries that body for both pills. One cleared node: `semanticLabel` replaces the drawn text ("Action · 26" speaks as "Action, 26 movies"), the selected chip announces "Selected" via state description, and `actionLabel` names the press. |
| `IglooTabRow` / `IglooTab` | A strip of mutually exclusive sections (the Movies index, §11.4) — the web client's tab list. The row is one bordered pill on `muted @ 0.50` with a `border @ 0.50` hairline at `focus.restWidth`, radius `lg`, `spacing.xs` padding and gap, sized to its content and never scrolling (more tabs than fit the panel is too many tabs). A tab is `controlHeight` minus the row's padding, radius `md`, `spacing.md` horizontal padding, `bodyMedium` label: `primary` fill / `primaryForeground` selected, `card @ 0.72` / `foreground` focused, transparent / `mutedForeground` at rest — selection and focus compose as on the chip, but **no focus scale**, since a lifted tab would overlap the row's border. Shares `IglooFilterChip`'s `SelectablePill` body; the five differences (radius, focus scale, height inset, resting label colour, semantics) are its parameters. **Selects on focus**: d-pad landing on a tab is the switch, the Android TV convention. `onPress` is the separate, deliberate signal — TalkBack's click action, and the way back after a failed switch reverted the selection out from under a focused tab — and defaults to `onSelect` for a caller that does not need to tell them apart; the Movies screen does, because the landing is debounced and a press must not wait behind it. The focused-but-unselected treatment exists for exactly that revert window and nowhere else. One cleared node with `Role.Tab`, `selected` set only when true (never false — TalkBack would say "not selected" on every other tab) and `actionLabel` on the press; the row is a `selectableGroup`. Hand-rolled on Foundation, not `androidx.tv.material3.TabRow` (§9.2), and the ground is written out rather than taken from `iglooSurface`, whose `clip` would cut the focused tab's glow at the row's bounds. |
| `RatingBadge` | The critic-score badge and its `ratingBadgeSpec` tiers (§3.2). The score is rounded once, and the tier read off the rounded value, so the colour can never disagree with the number shown. |
| `MediaFormatting` | Shared display formatting for media: `formatRuntime` ("2h 50m"), `formatReleaseDate`, `progressFraction`, compact `formatRemainingTime` ("2h 20m left"), spoken `formatSpokenRemainingTime` ("2 hours and 20 minutes remaining"), `formatTimecode` ("1:01:15"), sparse `formatSpokenTime` ("1 hour and 15 seconds"), and exact `formatSpokenTimeThroughSeconds` ("1 hour, 0 minutes, and 15 seconds"). Both remaining-time forms clamp overshoot and round partial minutes up; they use "Less than 1m left" / "Less than 1 minute remaining" below one minute and defensively fall back to "In progress" for an invalid duration. The exact resume form floors to the last completed second and includes every unit from the largest relevant one through seconds, never a leading zero hour. Called from view models, never from composables — with one exception: the trailer player (§11.8.1) has no view model, so its chrome formats in place. A screen with a view model has no excuse. |
| `IglooTextField` | `heightIn(min = sizes.fieldHeight)`, radius `lg`, placeholder at `mutedForeground @ 0.60` |
| `IglooInlineError` | `destructive @ 0.10` fill, `@ 0.25` border, radius `lg` |
| `IglooNotice` | One announced line — `bodyMedium` / `mutedForeground`, `liveRegion = Polite`. For a message the user did not ask for and cannot act on: what a gate says after an action that already happened (§10, §11.1.1). Not an error card; no Retry. |
| `IglooIconButton` | The square icon-only control for row ends (the detail hero's More trigger, a track row's three actions): `controlHeight` both ways, radius `lg`, focus per §6.1, glyph at `icons.md`. `semanticLabel` is mandatory — the glyph alone says nothing to TalkBack. With a null `onClick` it stays a focus target but announces no action, the inert-poster-card contract. Carries `restingFill` / `contentColor` for the §3.2 over-media ground like `IglooButton`, and `IglooButton`'s toggle semantics — `stateDescription`, `actionLabel` — plus `iconTint` for a glyph that changes weight while the ground stays. |
| `TrackRow` | §11.5's three-action row (`feature/shared`): Play, Like, More as `IglooIconButton`s, all focusable, none hidden until focus, the row itself never a target and painting `muted @ 0.50` at `radius.lg` while a child holds focus. Play is the entry column and speaks the row's one sentence with "Liked" as its state; Like carries the like state and an action naming the track; More names the track, or says "None available." while inert. `TrackRowFocus` answers up/down **per column** so vertical moves keep their column, a null answer leaving the direction to the spatial search (a lazy list wires only its edges); `riders` park a host's requesters on one control of one row. `TrackRowSkeleton` is the row-shaped placeholder; `TrackRowMenu` the row's `IglooMenu` ("Go to album" / "Go to artist"). |
| `IglooScrim` | The paint-only dim: `background @ 0.60` by default (§3.1), no `clickable`/`focusable`/`semantics`, so it can never intercept the d-pad and TalkBack does not know it exists. Used by the rail (§8.1) and the modal (§9.3) — **never both at once**. |
| `IglooConfirmDialog` | The confirmation modal (§9.3) |
| `IglooMenu` | The anchored menu: a `card` surface of focusable rows placed against the trigger's root-coordinate bounds — right-aligned, below it, flipping above when the bottom safe area would be breached. In-tree for §9.3's four reasons and hosted as the last child of the screen that owns the trigger; **unscrimmed**, unlike the modal — an anchored menu is local chrome, not a page-blocking decision, and §9.1 gives the scrim to the rail and the modal only. One `standard` alpha reveal, no exit animation. Focus is trapped (up/down walk the rows, everything else `Cancel`), the first row takes focus on reveal, the caller restores focus in `onDismiss` and gates its own Back (§9.3). `paneTitle` + one cleared Button node per row; a `destructive` row wears the destructive token pair, and `separatorBefore` draws a silent hairline. The covered screen leaves the semantics tree via an empty `clearAndSetSemantics { }` — the partial-overlay rule (§9.3), since an anchored card occludes nothing and a merely-hidden node keeps TalkBack's focus. |
| `IglooRadioRow` | One option row of a radio list on a card ground: the `IglooMenu` row recipe (`navItemHeight` minimum, `muted` focused fill, `spacing.md` padding, focus per §6.1) plus a drawn-only leading radio glyph — outer ring on `border` (`primary` when selected), `primary` dot when selected, unscaled 2dp stroke so the hairline stays a hairline. One cleared `RadioButton` node per row announcing label and selected state with a "Select" action. A null `onSelect` is the inert variant: still **focusable** — an unfocusable row mid-list punches a hole in a hand-wired up/down chain, the §9.3 pending-row argument — but announced disabled with no action; the label carries the reason it cannot be chosen. Optional `detail` is trailing muted text (a timecode), drawn-only; optional `semanticLabel` replaces the spoken label when the drawn one is not the sentence to read, the `IglooButton` contract. Focus wiring is the caller's, like the menu's rows. |
| `FocusRing` | The one focus treatment (§6.1) as one modifier: glow, scale, fill, clip, ring, separator. **Owns the fill; call sites pass `fill =` and must not clip.** |
| `IglooQrCode` | Pairing-code QR |
| `IglooBrandMark` | The "I" tile. Always radius `lg`; hidden from accessibility, since the glyph is not a word. Size and text style are the only parameters. |
| `IglooPosterCard` | The rail media card (§8.2): artwork at `layout.posterWidth` / `layout.posterAspect` by default, with both geometry values as parameters (`wideCardWidth` / `wideAspect` for video thumbnails), radius `lg` by default and `artworkRadius = radius.pill` for a musician's circle — §8.2's circle is a radius the focus ring clamps, not a second component — with `centerText` for the text under it and `semanticLabel` when "Title, Year" is not the sentence to read, focus per §6.1 on the artwork only — title (`bodyMedium`, 2 lines) and one context line (`label`) sit below it and keep still while the poster scales. One cleared semantics node ("Title, Year"); it takes `Role.Button` and an "Open …" action **only when given an `onClick`** — with none, the card is still focusable but announces no action it cannot perform. A null or failed image falls back to the film glyph on `muted` with the text unchanged. Optional `PosterCardProgress`: a 4dp bar on the poster's bottom edge (`primary` fill on a `Black @ 0.40` track, §3.2) whose fully spoken remaining-time description joins the cleared node ("Title, Year, 2 hours and 20 minutes remaining", §12) so the bar can never render unannounced. The description is semantic only; no numeric percentage or remaining-time caption renders on the card. |
| `IglooMediaRail` | The §8.3 rail: heading + foundation `LazyRow` of cards, grid-matched static skeletons (shaped by the caller's `cardAspect` and `cardWidth`, so a wide rail's placeholders match its cards), minimal `IglooEmpty`, and `IglooInlineError` with Retry. Owns per-rail focus memory (§6.3): the entry card is the last-focused one, and a rail rebuilt on re-entry is created scrolled so that card exists to take focus. **Every state keeps exactly one focus anchor** wired to the pane's entry requester and the spine, so the shell's focus model (§8.1, Back) always has somewhere to land — including while loading and when empty. An optional `returnRequester` rides that same anchor: an overlay opened from a card requests it on close, so Back lands on the card that led away (§6.3, §11.4). Takes the pane's `contentInset` and splits it by node per §8.3 — heading and non-scrolling states pad, the `LazyRow` carries it as `contentPadding` so cards bleed off the panel edge. |
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

Nothing has been adopted yet. The Movies tab strip (§9.1 `IglooTabRow`) deliberately stayed on
Foundation: every other control wears §6.1's `focusRing`, and a Material tab would have been the
one node in the app with a focus treatment of its own.

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

**Geometry.** Card at `layout.dialogWidth` (reused, not a new token), `radius.xl`, `card` fill,
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

**A partial overlay must go further: it clears the content it covers.** `hideFromAccessibility`
is only enough when the overlay is a full-screen semantics node, because what such a node covers
is dropped from the accessibility tree as *occluded* anyway — that is the case for the shell under
the details overlay and for the details screen under a player. An anchored menu occludes nothing.
There the flagged nodes stay in the tree, and TalkBack for TV leaves accessibility focus parked on
the one it was already sitting on — the trigger the user just pressed. Nothing moves it off: an
overlay's entry row is composed *already focused*, and Compose emits `TYPE_VIEW_FOCUSED` only for a
node it has previously seen unfocused, so no focus event is ever sent for it. Measured on a Shield,
the pane appearing does not move TalkBack either while the node it is focused on is still in the
tree. The reader goes silent, the remote looks dead, and Back is the only way out. So a partial overlay puts an empty
`clearAndSetSemantics { }` on the content it covers, taking those controls out of the tree
entirely — with the covered node's own `testTag` left *outside* the clear so it survives, the same
ordering rule the reading stops follow (§12).

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
  the screen. This recipe is `IglooInlineError` (§9.1) — there is no separate composable. Its
  live region is `Assertive` in a form, where the error is the only thing that changed, and
  `Polite` anywhere several can appear at once (the §11.3 rails fail independently), so the
  announcements queue instead of cutting each other off.

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

**There is no card.** Every auth screen is the same full-bleed canvas — §11.1.0's two radial
gradients over an opaque fill, reaching all four physical edges — with only chrome and text taking
the overscan inset (§2.5). `AuthSurface` lays it out in one of two shapes, chosen by what the
screen's content actually is:

| `AuthCanvas` | Shape | Used by |
|---|---|---|
| `Split` | Identity left, a `weight(1f)` column of controls right | §11.1.1's PIN, §11.1.3's password path, the server prompt |
| `Stacked` | Identity across the top, content spanning the full inset measure below | the profile picker, quick connect — both are *rows*, and neither fits a half-panel column |

`Split` at Standard on the reference viewport: 960 − 96 (safe area) − 48 (`spacing.xxl` gutter)
= 816, split 384/384. The two content extremes land on x = 48dp and x = 912dp — **both safe-area
edges**. That is the whole point of the section.

In `Split` the identity block stacks the brand mark above the words; in `Stacked` it sets the mark
*beside* them, because there the headline sits above content that already fills the panel and the
taller form costs ~80dp — enough to push quick connect's own header off a 540dp screen.

Both `Split` columns are traversal groups, or TalkBack's geometric sort interleaves the identity
block with the form rows sharing its y position (§12).

⚑ *Until 2026-08-19 this was a **card form**: a 480dp card centred on a 960dp panel, with 840dp
for the two row screens. Half the panel's width was empty background. It is the single clearest
example of a web/mobile idiom surviving a port unexamined — a centred card is right when the
viewport might be a phone, and wrong when it is definitionally a television.*

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

"Who's watching?" on the `Stacked` canvas (§11.1): six tiles at the cap need the full inset
measure, not half of it. A single
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

- Avatar: 96dp circle. The stored value is resolved against the server origin by
  `avatarImageUrl` before it reaches the composable: an absolute `http(s)` URL passes through,
  and an uploaded `/api/static/avatars/...` path gets the origin prepended so the image loader
  recognises it as ours and attaches the bearer that route requires. A missing or
  unresolvable value falls back to the initial on `primary`.
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

**A launch is a new Activity, and only that.** `SessionManager` is a process singleton, so its
state outlives the Activity: backing out of Igloo leaves the process alive with the session still
published, and the next launcher press composes a *new* Activity over it. That is a resumed token
and is gated. So `MainActivity.onCreate` drops the session back to `AppAuthState.Loading` whenever
`savedInstanceState` is null — synchronously, before `setContent`, because a `LaunchedEffect`
runs a composition too late and the shell would already have created its view models and fired
the §11.3 rails' user-scoped fetches. The splash then covers the whole re-gate (§11.1.-1), and
`SessionManager.restoreOnLaunch()` resolves it. A configuration-change recreation arrives with a
saved bundle instead — `uiMode`, `locale` and `fontScale` are deliberately left out of
`configChanges` — and keeps the session it had, as does a return to the foreground on an Activity
that was never destroyed. Neither is a resumed token; both are the mid-session case above.

**Remotes have no number keys.** This is the constraint the screen is designed around: a 3×4
on-screen keypad (1–9, delete, 0) plus four masked indicator cells, in the `Split` canvas's form
column (§11.1). Indicator, pad and footer share one bounded 320dp measure — the keys are
`weight(1f)` over a `controlHeight` minimum, so across the full column they stretch into squat
slabs, and at 320dp a key is about as wide as it is tall. The old 480dp card gave them that shape
by accident; the bound makes it deliberate. Hardware
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

Quick connect **fits 960×540 without scrolling in its resting state** at Standard and font scale
1.0 — measured. It gained room when the card went: no 32dp of card padding on each axis, and a
side-by-side headline instead of a stacked one. That matters because focus lands on a bottom
control the moment the screen composes, so overflow would immediately scroll the header out of
sight. `QuickConnectLayoutTest` guards it, and the canvas's internal scroll (§11.1) is left for the
degraded states — an inline error present, a larger `UiScale`, or a raised font scale.

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

**The resting rail is a scrim, not a fill.** The pane bleeds under it (§8.1), so the ground is a
horizontal gradient — `sidebar @ 0.90` at the panel edge fading to nothing across the collapsed
width — and art passes beneath the icon strip instead of being cut off by it. Not opaque even at
x = 0: a flat column of `sidebar` against `background` is two near-identical darks, which is
precisely what read as a black bar down the side of the screen. Legibility is not the scrim's job
— every rail row carries its own surface fill. Expanding restores the solid fill, driven by the
same `labelAlpha` as the labels so the two cannot disagree.

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

Stacked horizontal rails — continue watching, latest movies, latest albums, movies in theaters,
watch rooms — under the hero (§11.3.1). This is the most TV-native layout in the product and the model for other
index screens. Vertical d-pad moves between rails; horizontal moves within one; focus is
restored per-rail on return.

**There is no pane header.** Home opens on the hero's backdrop at the top edge of the panel; the
destination's name is carried by the rail's `selected` row visually and by the pane's `paneTitle`
for TalkBack (§12). A title block with supporting text above the content was a Material top app
bar in everything but name, and it was what made a full-bleed hero impossible. Non-Home
destinations still render a heading, inside their own content.

#### 11.3.1 The hero

The hero features the **single most recently added movie**: the first item of the Recently Added
data, enriched with `GET /api/movies/details/{id}` for its backdrop, overview, and metadata. The
two requests chain inside the rail's own load job, so the rail's Retry re-runs the hero and a
foreground refresh cancels both together. It does not rotate — §7.2 permits no looping surface,
and a hero carousel fails that rule twice (it carries information; it moves geometry under text).

**Geometry. The art is full-bleed; the words are not.** The backdrop reaches the top, left and
right physical edges — §2.5's opt-out is exactly what it exists for — and passes *under* the nav
rail, which rests as a scrim over it (§11.2). The text sits on a plate inset by the pane's
`contentInset`, so the title never lands under the icon strip. Art may go under chrome; words may
not.

`heightIn(min = 360dp)` (scaled; a one-off literal per §2.8 — it contains text, so §2.6 requires
min, not fixed). It was 280dp as a rounded card parked ~117dp down the screen under a pane header;
starting at the top edge instead, 360dp leaves **more** of the Continue Watching rail showing than
the card did (540 − 360 = 180dp against 540 − 397 = 143dp), so §8.2's scroll affordance
strengthens. The hero scrolls away with the rails; no pinning, no collapse choreography.

**The focus ring rides the text plate, not the bleeding surface.** This is a deliberate departure
from §6.1's "ring wraps the focusable" and must not be tidied back: a 3dp ring traced around a
surface that reaches the panel's edges sits in the overscan margin, the one place §2.5 says a TV
is allowed to crop — a focus indicator that a television may simply not show. The plate is
bounded, always inside the safe area, and carries §3.2's chip-and-control ground so the ring has a
surface to contract against. The node the ring *describes* is still the hero: one focus target,
one `clearAndSetSemantics`, every focus edge unchanged.

**Backdrop.** `w1280` via the TMDB proxy, `ContentScale.Crop`. Over it, two static gradients
(over-media literals, §3.2): the bottom title gradient, and the side gradient left-to-right so the
text column reads against busy art. When the movie has **no backdrop** (or the image fails), the
hero renders as an ordinary card surface — `card` fill, token text colors, **no gradients** —
because §3.2's white-on-black literals are licensed only by media behind them; keeping them over a
`muted` fill fails contrast in light mode.

**Text.** Bottom-left column, max width ~420dp (readable measure), `spacing.xl` inset. Over
media: `Color.White` with a shadow (§3.2). Title `titleLarge`, `maxLines = 2` — **not `display`**,
which §4 reserves for a canvas with no card. Metadata line in `label`: year · certification ·
runtime · rating, every field nullable, separator-joined, the whole line omitted when empty.
Overview `bodyMedium`, `maxLines = 3` (§4.1).

**Focus and semantics.** The hero is **one focus target** and owns the pane's entry anchor
(`contentStartRequester`) whenever it is visible; the Continue Watching rail takes the anchor back
when it is not — the two must never hold it in the same composition. `focusRing` with
`scaleOnFocus = false` (1.05× on a pane-width surface overflows the pane; ring, separator,
fill-contract, and glow still carry the signal). D-pad left exits to the spine, right is
cancelled, and down is hand-wired to the first rail's entry anchor — spatial resolution from a
pane-wide surface is heuristic, and the wire means the rail's focus memory applies, the same as
spine re-entry. Like the poster cards, the hero announces **no action** until the
details screen lands: one `clearAndSetSemantics` node reading "Featured", title, metadata, and the
full overview (the visual clamp is not an accessibility clamp).

**States.** Loading is a static geometry-matched skeleton (§10) that is focusable and carries the
entry anchor, with a polite "Loading featured movie". There is no hero error card: an empty
library, a failed latest fetch, or a failed details fetch **hides the hero** — the rail below
already owns the error and its Retry. A background refresh that fails keeps a previously loaded
hero, exactly the rails' rule; an empty library hides it unconditionally.

#### 11.3.2 The rails

Each rail is one `IglooMediaRail` over one endpoint, loaded by its own cancellable job so the
rails fail, retry, and refresh independently (§12's polite live regions depend on that).

| Rail | Endpoint | Card | Empty copy |
|---|---|---|---|
| Continue Watching | `GET /api/continue-watching` (movies and TV episodes; the client keeps only `kind: movie` until an episode has somewhere to open) | poster + progress bar; fully spoken remaining time in card semantics only | "Nothing in progress yet. Movies you start watching appear here." |
| Recently Added Movies | `GET /api/movies/latest` | poster, year below | "No movies in your library yet. Add a movies folder on the server and run a scan." |
| Recently Added Albums | `GET /api/music/albums/latest` | `albumAspect` cover, musician below, `Music` glyph fallback | "No albums in your library yet. Add a music folder on the server and run a scan." |
| Now Playing in Theaters | `GET /api/tmdb/movies/in-theaters` | 2:3 poster, title + year over a bottom scrim, rating badge top-right (§3.2) | "No movies are playing in theaters right now. Check back later." |

**Server order is the contract — for the library rails.** None of the three library routes takes
a sort, and `GET /api/music/albums/latest` takes no parameters at all — not even a limit; the
backend owns both the order and the cap of 12. Nothing is re-sorted or re-sliced client-side.
The theaters rail is the one exception: the TMDB route's order carries no meaning of its own, so
the client sorts by `release_date` descending — the same client-side sort the web client applies.

**The theaters rail is TMDB content, not library content**, which is why its card looks
deliberately different: the title and year sit *on* the poster over a bottom scrim, with a
critic-rating badge (star + one-decimal score) in the top-right corner. It renders last because
these are movies the user cannot play. Each card opens that movie's in-theaters detail page
(§11.4.2) and announces "Open {title}"; the whole card is one cleared semantics node reading
title, year, and "rated X.X out of 10". A rating of 0 is TMDB's "unrated" and drops the badge (and the
announcement fragment) rather than badging "0.0". Empty copy describes the successful-but-empty
case; a failed fetch shows the shared error card instead — the web's error-flavored empty copy is
deliberately not ported. On a server with no TMDB key the rail sits in its error state with
Retry, like any other failed rail.

**Album covers are used verbatim.** The music scanner stores an absolute Spotify image URL or
nothing, so a cover is not run through the TMDB proxy helper and there is no music proxy to
build. The image loader already withholds the bearer token from foreign origins, which is what
makes loading straight from the CDN correct rather than merely convenient.

Only the first section on the pane holds the entry anchor — the hero when it is visible,
Continue Watching otherwise — so every rail below passes `entryRequester = null`. Vertical d-pad
between rails resolves spatially in the scrolling column; only the hero hand-wires its `down`.

### 11.4 Movies

- **Index** — a heading, the current view's count, **Sort (A–Z ⇄ Z–A)** and **Refresh**
  actions, a **tab strip** (All Movies · Genres · Liked), the Genres tab's **genre picker** (one
  chip per genre, with counts), and a poster grid at `gridColumns`, **paged by infinite
  scroll**. The strip mirrors the web movies page's All Movies · Genres · Playlists, with Liked
  standing in for Playlists until playlists have a screen of their own.

  **This reverses the earlier "no sort control, no filters" rule** ("the grid is All Movies and
  nothing else") and the 2026-08-29 "still no tab control" rule that put All, Liked and every
  genre in one chip row. Sort is **direction only** — the backend orders by title and offers no
  field choice — so the control is a Ghost button beside Refresh with reserved label variants
  ("A–Z"/"Z–A", §9.1), never disabled for the same focus-tree reason as Refresh, announcing
  "Sort order" with an "A to Z"/"Z to A" state description.

  **The tab strip** sits between the header and the grid and renders in every grid state — an
  empty Liked view or a failed first page must still let the user switch sections. **Tabs
  select on focus** (§9.1): landing on a tab is the switch, so flipping sections is one press
  per tab with nothing to confirm. The *highlight* moves at once; the **fetch is held back
  300 ms**, because passing over Genres on the way from Liked to All lands on it, and a tab the
  d-pad is only crossing must not put a request on the wire, flip the header to "Refreshing…",
  or re-announce the count to TalkBack. The transient highlight is the platform's own tab
  behaviour and stays. A **press** skips the delay — it is deliberate, never a pass-over — and
  a switch that outruns the delay still cancels the page job and bumps the generation, so a
  superseded response can never land. The one cost worth naming: a switch that lands
  while the user is still on the tab must **not** re-anchor focus on the grid (the yank every
  other replacement performs), or the strip becomes impossible to traverse; the grid still
  scrolls to top so the next press down lands on its first card. Refresh and Sort wire their
  `down` to the *selected* tab for the same reason — a spatial search would land on whichever
  tab sits beneath the button and switch to it.

  **The genre picker** is the Genres tab's second row, composed only while that tab is selected
  and has a list to show. It is one horizontally scrollable plain `Row`, deliberately not lazy:
  genre lists are bounded (tens), and keeping every chip composed keeps every focus requester
  permanently attached, so the grid's wired `up` edge can never target a disposed node. Genres
  load with every refresh and **degrade silently**: a failed read keeps the last known list and
  never shows an error in the row. A **successful empty list is authoritative** and clears the
  remembered genre; only a failure leaves the last one standing.

  With no list to pick from, the tab draws one of **two** card-less surfaces, and they must not
  be confused: before the first genres request has settled it is the ordinary grid skeleton
  ("Loading genres" on the anchor, "Loading the genre list" on the count), and only once a
  request has settled — successfully empty, or failed — does it become the placeholder ("Genres
  aren't available right now. Refresh to try again.", counted as "Genres unavailable"). An empty
  list on its own cannot tell those apart, so the state carries whether a request has settled.
  Either surface carries the pane's focus anchor; nothing is fetched, and the last committed
  pages stay intact underneath. Refresh from the placeholder is the one press whose only request
  is the genres round trip, so **that** round trip carries the Refreshing label; Sort is a
  deliberate no-op there, because flipping the label with no list to sort would leave the header
  claiming an order the hidden committed grid is not in. The list landing later auto-selects the
  first genre and fetches it — but only when the resolved id actually changed, so the start
  effect's re-read on every lifecycle START does not re-page the grid. Entering the tab
  re-resolves the **remembered** genre against the current list — by id, so a renamed tag
  follows the list and a vanished genre falls back to the first. The selected chip wears the
  `primary` fill while
  focus stays the §6.1 ring (the two compose, not compete) and announces "Selected"; chips draw
  "Action · 26" and speak "Action, 26 movies". Chips keep select-on-press.

  **Focus contract:** the grid's first row goes up to the selected genre chip on the Genres
  tab, else to the selected tab; every chip goes up to the selected tab and down to the pane's
  content anchor; every tab goes up to Refresh and down to the picker when it is shown, else to
  the content anchor — entry card, skeleton anchor, error Retry, the empty box, or the genres
  placeholder, whichever the state drew. The first tab and the first chip exit left to the
  spine; the last tab's and the last chip's right edges are pinned. The header's left chain is
  Refresh → Sort → spine, and both buttons go down to the selected tab. In the states with no
  cards the anchor also carries the overlay-return requester: the details overlay can outlive
  the card that opened it (the Liked reconcile empties the grid underneath), and a detached
  return requester does not *fail* its focus request — it silently no-ops, the host's anchor
  fallback never runs, and the overlay's disposal hands focus to the platform fallback in the
  navigation rail. Back must always find a live node inside the pane.

  **Transitions.** A tab, genre or sort change is a wholesale replacement, exactly like
  Refresh: the tab or chip highlights at once, the loaded grid stays on screen while page one is
  in flight, and success scrolls to top and re-anchors focus on the first card — except under a
  focused tab, above. Failure keeps the grid, **reverts the tab and genre** to the view the grid
  still shows, and reports in the notice — a tab must never claim a list the grid isn't in; the
  reverted tab stays focused, and a press on it is the retry. The revert restores the committed
  genre only if the current list still has it: a genres response that dropped it while the page
  was in flight would otherwise strand the tab on a genre with no chip left to change it, so the
  placeholder takes over instead. Leaving a list also supersedes it — the page job is cancelled
  and the generation bumped **before** the endpoint check bails, so landing on the Genres tab
  with nothing to page cannot leave an earlier request alive to revert the tab underneath it. Counts belong to the active view: the
  library-wide stat backs All only, and filtered views read their own responses' `total`. Each
  view has its own empty copy ("No liked movies yet. Like a movie from its details page and it
  will appear here.", "No {genre} movies in your library."). A like toggle committed in the
  details overlay reconciles a shown Liked grid **silently** — no refreshing label, no notice,
  no generation bump — because the overlay is still composed above the grid and any scroll or
  focus side effect would land on a surface the user cannot see.

  **This reverses the earlier numbered-pagination rule.** The argument for numbered pages was
  that a remote user needs a bounded, predictable focus target — but a page strip is a *second*
  focus region below a grid the user is already inside, reached by pressing down through the
  last row and left again to get out, and every page turn is a full list replacement that drops
  focus. Infinite scroll is bounded differently and better: **the tail is always occupied.** Two
  rows of skeleton cells sit past the last loaded item whenever more pages exist, in the same
  place whether a request is in flight or not, so the grid's geometry never changes under a
  focused cell. Those skeletons are **not** focus targets — a node that vanishes when its page
  lands would drop focus on the floor (§10) — so the last row pins its own `down` and reaching
  the true end is a stable no-op rather than an escape into the navigation rail. A page that
  *fails* replaces the tail with a full-width inline error whose Retry **is** focusable, which
  is exactly where the user's focus is heading at that moment. Prefetch fires two rows out, one
  request at a time, at `per_page=48` — the contract's maximum.

  **Refresh is for everyone**, and it is a client-side re-read: it re-requests the count, the
  genre list, and page one **of the current view**, drops the accumulated pages, and returns to
  the top. It is not the admin scan
  endpoint and must never be described as one. The loaded grid **stays on screen while it runs**
  — blanking it would dispose the focused cell mid-request — and the button swaps its own label
  rather than showing a spinner (§7.2). It is never disabled: `IglooButton` is focusable only
  through its `clickable` branch, so disabling it would remove the node the user is standing on
  from the focus tree. A refresh that fails leaves the grid alone and reports in an
  `IglooNotice`, because the failure is over and Refresh is one press away.
- **Detail** — full-bleed backdrop with a `background` gradient scrim, content pulled up over
  it. Poster left; title, tagline, metadata chips, genres, and hero actions right. Hero actions:
  **Play**, **Watched** toggle, **Like**, and the icon-only **More** trigger, which opens the
  anchored menu (§11.4.1): Playback Settings, Watch Together, Technical Details, and — admin
  only, hidden rather than disabled — Identify Movie and a destructive Delete Movie behind a
  separator. Below: cast, chapters, extra details. Play must be the first focused element on
  entry.

#### 11.4.1 The detail screen, as built

**Shape.** A full-screen in-tree overlay above the shell, on an opaque `background` fill — the
§9.3 dialog's mechanics at screen scale, and for the same reasons. The shell stays composed
underneath so its rails keep their scroll and focus memory, and it leaves TalkBack traversal
via `hideFromAccessibility` while the overlay is up. The host owns Back and focus restoration;
the screen owns only its entry anchor.

**Hero.** The backdrop is full-bleed (§2.5) at `w1280`, `ContentScale.Crop`, fading in at
`page` once it decodes. Two scrims with two different jobs: the §3.2 black side gradient
licenses the white text column against busy art, and a **vertical fade to the `background`
token** blends the backdrop into the canvas the sections sit on. That token fade is the case
§3.2's home-hero note carves out — the detail backdrop is unclipped and really does meet the
page. Alpha-zero stops are written `color.copy(alpha = 0f)`, never `Color.Transparent`, which
is black at zero and greys the fade. Chrome and text keep the safe-area inset.

The side gradient runs its **own stops** here — `0.80` → `0.55` at `0.65` → `0` — not §3.2's
`0.70 → 0.35 at 0.5 → 0`. That ramp is written for the home hero, a *clipped card* about 752dp
wide; stretched across a full-bleed panel the same fractions have decayed to alpha 0.14 by the
time the metadata line ends, and the backdrop's highlights come back up through the text column.
Measured on a high-frequency backdrop, white-on-peak in the title band was **1.69:1** under the
home ramp and **3.72:1** under these stops, with the tagline and genre bands at 6.51:1 and 4.18:1
(3.74 / 6.62 / 4.59 on a Shield). The `.overMedia()` shadow mitigates the peaks but does not
remove them, so the ramp has to carry it. Both heroes still use the same *shape* — a black side
gradient, left to right — and neither is licensed off media.

Poster at `posterWidth` / `posterAspect`; title `titleLarge` at 2 lines (**not** `display`,
per §11.3.1); tagline `bodyLarge` italic in quotes; then the metadata row, genres joined by
`·`, the action row, and the resume strip. The §3.2 treatment is licensed by a **decoded**
backdrop, not by a non-null URL: until the decode lands — and permanently, with no backdrop or
one that fails — every §3.2 literal is dropped for token colors, exactly as the home hero
does. (A URL-gated treatment paints white text over the bare token canvas for the whole load
window, which on the light palette is white on white.) The title's 2-line clamp and the
single-line tagline, genres, and runtime/date lines ellipsize conventionally: end-of-line
ellipsis on metadata is an honest cut signal, and the fade treatment (§4.1) is reserved for
the overview — the one block of prose the user came to read.

**Metadata row.** Rating badge, certification, media-info chips, then runtime and release date
as plain `label` text. Chips are `label` on the §3.2 chip ground. Media chips are derived from
the probed streams, in this order: `4K`|`HD` → `HDR10`|`HLG` → `7.1`|`5.1`|`Surround` → `CC`.
Web parity on every threshold: 4K at `width ≥ 3200 || height ≥ 2100` and HD at `≥ 1800 ||
≥ 1000` (width first, so a scope master is not demoted by its height), HDR off `color_transfer`
alone, a named channel layout only when ffprobe reported one, and `CC` for any subtitle track.
The whole row is **one** cleared semantics node speaking a sentence the view model composes,
with the abbreviations spelled out — eight two-character stops would be noise (§12).

**Resume.** The Play button is always labelled "Play"; the resume decision belongs to the
player, not this screen. A partially watched movie shows a 4dp strip (`primary` on the §3.2
`Black @ 0.40` track) and a compact remaining-time caption such as "2h 20m left", from 30
seconds in until the position stops meaning anything — the server flips to watched at 95% — and
never once the movie is watched. Whole hours drop the empty minute part, sub-hour values use
minutes alone, partial minutes round up, and a sub-minute remainder reads "Less than 1m left".

Play's semantics carry both the exact resume point and an unabbreviated remaining time. With
progress, its content description is "Play", its `stateDescription` is "Resume from 1 hour, 3
minutes, and 17 seconds; 2 hours and 20 minutes remaining", and its click action label is
"Play". The resume position floors a fractional value to the last completed second and speaks
every unit from the largest relevant one through seconds, including zero intermediate units; the
remaining time rounds partial minutes up. Without progress, the content description remains "Play
{title}", the state is absent, and the click action label remains "Play".

The strip belongs to **Play**, and is laid out to say so: Play, the strip, and an invisible
"Less than 1m left" width reservation share an intrinsic-width column inside the action row.
Play and the strip fill that typography-derived width, so the strip is exactly as wide as the
button whose progress it reports and the longest caption neither wraps nor pushes toward Watch.
It sits `sm` below, which also clears Play's focus ring at its 1.05x scale. A width of its own —
the strip was once a sibling of the whole row capped at a fixed max — runs it out under Watched
and Like, where it reads as the row's progress rather than Play's; a fixed value would drift the
moment the label or typography is localised.

The strip's **slot and longest-caption width are reserved from first paint**, invisible and
silent while there is no progress: the progress request lands after the hero is on screen, and
marking a movie watched removes the strip, and either would reflow the bottom-anchored hero under
the user's eye if the slot came and went with it. The skeleton's Play stub reserves the same slot
so the loading→loaded swap does not move the anchor focus is sitting on.

**Focus.** Play takes entry focus, including through the loading→loaded swap, where the
skeleton's Play-slot stub holds the anchor. The vertical chain — actions → cast → extra videos
→ about — is **hand-wired end to end**, with each section skipped when empty and its neighbours
wired straight through, and every edge that would leave the screen is pinned to
`FocusRequester.Cancel`: the shell underneath is still composed, and an unpinned edge lets a
spatial search land on a card the user cannot see. While a spoken screen reader runs the same
chain gains the **reading stops** (§12): hero info above the actions (up from the row, where
the pin otherwise is), then Overview and Key Crew between the actions and the cast rail. Up
from the sections returns to the
**last-focused action**, not unconditionally to Play — the same focus memory the rail keeps for
its own cards, so Like → down into cast → up lands back on Like. Crossing between the rails
rides the same memory: up from the extras targets the cast rail's entry requester, which the
rail keeps parked on its last-focused card. Back closes the overlay and
restores focus to the card that opened it, via the rail's `returnRequester` (§6.3).

**More menu.** The row's fourth action is the icon-only `IglooIconButton` trigger ("More
options"), and pressing it opens an `IglooMenu` (§9.1) anchored to the trigger's reported
bounds. Playback Settings opens the dialog below; the remaining items fire stubbed callbacks
and close the menu until each feature lands. Items:
Playback Settings, Watch Together, Technical Details, then for admins (`AuthUser.is_admin`)
Identify Movie and, behind the silent separator, a destructive Delete Movie. Admin items are
**hidden, not disabled** — they are never composed for non-admins, so they exist in neither the
focus tree nor the semantics tree; a control a user can reach but never use only teaches them
the press is wasted. The split follows the overlay contract: the host owns the open flag (it
must gate its details-closing Back on it, §9.3) and restores focus to the trigger in the
dismiss callback; the screen owns the item list and the trigger's anchor bounds. While the menu
is up the details content behind it leaves the semantics tree via an empty
`clearAndSetSemantics { }` — the partial-overlay rule of §9.3, not the full-screen treatment — and
Back closes the menu — never the overlay under it.

**Playback Settings dialog.** The menu's first item opens `PlaybackSettingsDialog`
(`feature/movies/`): the §9.3 modal recipe — in-tree, scrimmed, one `standard` alpha reveal, no
exit animation, `dialogWidth` card, focus trapped, Back dismisses — holding three flat
`IglooRadioRow` (§9.1) lists in one scrollable column: **Playback** (all seven modes, always:
direct and remux say "Original quality" outright, the five transcode profiles carry their
height and intent — "1080p — best quality"), **Audio** (per-track "Language · layout" labels
through the shared web-parity formatters in `PlaybackSettingsMapping.kt`), and **Subtitles**
("None" first; image-based PGS/DVD/DVB tracks render as inert rows labelled "(image-based)" —
the backend can only serve text tracks as VTT). Flat lists, not expanding selects: a popup
inside a modal breaks the one-layer focus-trap recipe, and on a TV every visible option beats a
nested picker. Below the scroll, always visible, a plain-language explanation line
(`bodyMedium` on `cardForeground`, `liveRegion = Polite` — it changes only on a deliberate OK
press and says what the radio state alone cannot) describes what the chosen settings will do,
then a full-width Done.

OK selects without dismissing; entry focus lands on the *selected* mode row. Selections are
**session-only** — held in `MovieDetailsViewModel`, consumed when Play lands, reset when the
overlay closes; nothing is persisted or sent. Resolution is web parity: the default audio is
the file's `is_default` track (else the first), subtitles default off, and Direct plus any
non-first audio track resolves to Remux with the explanation saying why — direct play serves
the raw container, which always sounds its first track. The host wiring repeats the menu's
split once more: `IglooApp` owns the open flag, gates its details-closing Back on it, and
suppresses the menu's focus restore when a menu action just opened the dialog (the menu item
fires its action *before* dismissing — load-bearing order); dismissing the dialog restores
focus to the More trigger. While it is up, the details body leaves TalkBack traversal, the
overlay-stack treatment.

The trigger also re-pins the row's right edge: Right is `Cancel` on More alone now, Like points
Right at More, and Watched's hand-wired Right (the disabled-Like rule above) skips to More
instead of carrying the edge pin itself — the pin rests on the one right-end control that is
always present.

**Extra videos.** Between cast and About: the movie's TMDB extras (trailers, special features)
as a second always-Loaded rail, on the §8.2 wide geometry — `wideCardWidth` at `wideAspect`,
thumbnails through the authenticated `/api/youtube/thumbnails/{key}` proxy, cover-cropped so
hqdefault's letterbox bars never show. The view model filters to YouTube-hosted extras (the
backend proxies no other site's thumbnails, web parity) and re-sorts trailers → special
features → other with a case-insensitive title tie-break — the API's `ORDER BY type, title` is
alphabetical and puts trailers last. Each card is title over the type as its one context line
("Trailer", "Special feature" — bare, not the web's parenthesised form, which TalkBack would
read). The cards are **actionable**: one cleared node ("Official Trailer, Trailer"), the button
role, and the action label "Play {title}" — "Play", not the poster default "Open", because the
announced action must say what pressing does (§12) and pressing opens the trailer player
overlay (§11.8.1). The rail's `returnRequester` joins the host's focus-restore contract: it
rides the last-focused card, and closing the player lands focus back on the exact card that
launched it. A movie with no YouTube extras renders no section at all.

**Toggles.** Watched and Like flip optimistically and roll back if the server disagrees. Every
accepted press is an intent: Watched writes and Like writes each run through their own FIFO queue,
with no cancellation or coalescing, so rapid OK presses reach the server once each and in order.
The visible state is always the last queued intent over the last confirmed answer; an older
completion can therefore confirm the base without repainting over a newer press.

Three rules follow, and all are easy to get wrong. Leaving the screen cancels the screen's *reads*
but never a mutation — a press followed immediately by Back is still a change the user made. A
status read only applies its value while the same movie remains open and only if that movie's
mutation epoch has not moved since the read began, because a read issued mid-write can be answered
from before that write commits — but the epoch guards *only the toggle a write owns*. The rest of
the same payload is nobody's to stomp: the watch-progress read's resume position lands whether or
not a Watched press overtook it, or a press made mid-read would cost the user the strip. And Like
is disabled, with no click action in its semantics, until its status arrives: its POST toggles an
unknown server value, unlike Watched's PUT of an explicit value, so guessing "not liked" could
perform the opposite action. Watched stays available while unknown and its first press explicitly
sets `true`. A like-status read that *fails* therefore leaves Like inert until the next refresh —
the same "secondary requests degrade" contract the media badges and the progress strip already
follow, not a gap to code a retry around.

A disabled Like is not focusable, so while its status is unknown it leaves the focus tree and
takes the row's right-edge `Cancel` with it. Watched therefore carries the pin too, wiring Right
to Like only while Like is enabled: the rule that every direction out of the row is pinned has to
rest on controls that are always present, not on one that comes and goes.

A failed write restores the confirmed state after the remaining queue drains and re-reads the
matching status endpoint. The restored toggle is the retry path. The mapped backend reason appears
in the persistent, polite, non-focusable `IglooNotice` beside the actions; if Back has already
closed the overlay, the same notice appears below the shell header — one place at a time, never
both. A new mutation or opening a new movie clears it. There is no toast or inline Retry card (§10).

**Lifetime.** A movie's toggle state outlives its overlay, because a write settling after Back
still needs somewhere to land. It is retired instead when the screen moves on — opening another
movie, or closing the overlay — and only for movies with nothing queued, nothing owed, and nothing
left to announce. The movie being opened keeps its entry: reopening one paints the toggles it last
confirmed and lets the status read correct them, rather than flickering back through "unknown".

**Reachability.** Overview and Key Crew are prose between the hero and the cast rail, so moving
down scrolls them into view on the way; both share the same 620dp prose measure so adjacent
sections keep one right edge. The About block is a **focus target** even though it carries no
action: it sits below the last rail, and content a d-pad can never scroll to may as well not be
on the page. Its heading sits **above** the focusable panel, aligned with the other section
headings — inside the panel it would inherit the inner padding's indent and be erased by the
cleared semantics. The panel announces its rows as one node **with the heading folded in**
("About {title}. Production: …"): TV TalkBack follows input focus (§12), so the heading's own
text node above is never reached by a screen reader.

**Reading stops (screen reader only).** TalkBack for TV moves with **input focus** — it never
traverses plain text the way handset TalkBack's linear navigation does, so any prose that is not
a focus target simply does not exist for a TV screen reader. While
`rememberSpokenAccessibilityEnabled()` reports a spoken service (`core/ui/SpokenAccessibility.kt`,
live-updating), the page therefore adds three **reading stops** — focus targets in the About
panel's §6.1 treatment (surface highlight, no scale, no action) whose one cleared announcement
carries text the d-pad otherwise passes by:

- **Hero info**, reached by pressing up from the action row (whose top edge is pinned
  otherwise): title, tagline, the metadata sentence, and the genres with `·` spoken as commas —
  `MovieDetailsUi.heroInfoDescription`.
- **Overview** and **Key Crew**, joining the vertical chain between the actions and the cast
  rail, each folding its heading into the announcement.

Without a spoken service the stops are not composed as targets and the chain is exactly the
paragraph above — two extra presses between the actions and the cast rail would tax the most
common path for no sighted benefit. Two companions to the same rule: Play carries its exact
"Resume from …" position plus fully spoken remaining time as one `stateDescription` (the compact
caption's text node is unreachable), and the pane title becomes the movie's own title once Loaded
— a pane-title change is announced (§12), so the page names the film on arrival instead of the
generic "Movie details" the loading and error states keep.

Because it is reachable but not actionable, it wears the focus treatment as a **panel** rather
than a control: `radius.xl` — the §3 radius scale's step for cards, panels and surfaces — instead
of the `radius.lg` every button, input and nav row uses, and the §6.1 focused `card @ 0.72` fill
rather than a ring on bare canvas. Same one treatment, same ring and glow; what changes is that
the shape it draws is a surface highlight instead of a button outline, so focus arriving there
does not promise a press. It keeps `scaleOnFocus = false` — a full-width block that grows 5% reads
as the page lurching.

**Motion.** The header does **not** stagger. It holds the entry focus, and the rise moves the
focused button's visual bounds while the scroll container is bringing it into view — the column
ends up parked 12dp down, with the hero pushed into the overscan margin. The backdrop's fade
carries the entrance there; the sections below stagger normally, because nothing in them has
focus yet. This refines §7.2's "give the focused element index 0": inside a scroll container,
give it no stagger at all.

#### 11.4.2 The in-theaters detail screen

A card in the theaters rail (§11.3.2) opens the same detail screen for a **TMDB** movie the
library does not hold, from the one read `GET /api/tmdb/movies/{tmdb_id}`. Web parity: the web
client's `/movies/in-theaters/$id` route renders its library detail components over a TMDB
record, and this does the same over `MovieDetailsUi`.

**One screen, one render model.** `MovieDetailsScreen` is not forked. The two pages differ in
their hero actions and nowhere else, so the variant is carried by the actions bag —
`MovieDetailsActions.Library` or `.Theater` — and everything the source cannot fill is simply
absent in the shared model: no media badges (nothing was probed), no resume strip, no watched or
liked state. The two view models stay separate: this page is a single read with nothing to write,
and a TMDB id and a library id are both plain numbers, so one `openMovieId` could never tell them
apart. The host keeps **one overlay slot** and feeds it from whichever view model is open, which
is what keeps Back, the accessibility fence, and focus restoration single-path; opening either
closes the other.

**Hero.** Identical to §11.4.1 — full-bleed backdrop, poster, title, tagline, metadata row,
genres — with one action: **Play Trailer**, TMDB's first YouTube trailer in its own order, opening
the §11.8.1 player. The button is the entry anchor *and* the node the player restores focus to,
so a trailer started from the hero comes back to the hero rather than to the extras rail's card
(§6.3). A movie TMDB lists no trailer for renders **no action row at all** — a control that takes
focus and does nothing spends a press to teach the user it is empty (the same rule that deferred
More) — and the entry anchor passes to the first section below: the Overview reading stop while
a screen reader runs (§11.4.1 Reachability), else cast, else extras, else About. Up out of the
first section is then pinned — unless the reading stops are in, where it climbs to the hero
info stop: there is still content above to hear. A record
with no trailer, no cast, no extras *and* an empty About has nothing to anchor at all, which is
why the entry request is made through `requestFocusSafely` — the page is prose the user can read
and Back out of, not a crash. TMDB always sends `original_language` and `status`, so About carries
a row and this is not a state a real record reaches.

**Metadata.** The rating badge is the §3.2 star badge over TMDB's `vote_average`, the same value
and the same badge the theaters rail's card carries — not the web's separate "TMDB 7.9" outline
chip, which would be a second rating style for one number. 0 is TMDB's "unrated" and drops the
badge. The certification chip is TMDB's `release_dates`, resolved by the **backend scanner's own
rule** (`TmdbMovie.Certification`): the US rating when there is one, otherwise the first non-empty
rating from any country — so the chip agrees with the one the same movie would show on its library
page once it is scanned in. (The web page shows no certification here at all; matching the scanner
is worth the divergence.)

**Sections.** Overview, Key Crew, Cast, Extra Videos and About, all as §11.4.1 builds them — the
crew rule, the cast cap, the YouTube-only extras and their trailers-first sort are shared code, not
a second implementation. Two differences: TMDB's free-form video types ("Featurette", "Behind the
Scenes") are shown as TMDB spells them, since only the library's snake_case values need splitting;
and About gains a **Status** row ("Released") between Production and Original language, the one
field only a TMDB record carries (web parity). TMDB has no numeric id for a video, so the extras
rail keys off the payload's own order, assigned before the sort.

**Back** closes the overlay and restores focus to the theaters card that opened it, through that
rail's `returnRequester` like any other rail (§6.3).

### 11.5 Music

**Index, as built (2026-09-19).** The §11.4 shape with music's three sections: a heading with
the selected section's count and a Refresh action (no Sort — no music endpoint takes one), a
three-tab `IglooTabRow` — **Musicians · Albums · Tracks** — and one infinite-scrolling surface per
section. **Playlists is deferred** to its own pass: the web page's fourth tab is twelve endpoints
including collaborators and reordering, and when it lands the Liked-tracks view moves inside it,
as on the web; until then the like toggle has no list of its own, only the heart on every row.

**Each tab keeps its own pages.** Unlike §11.4 — three filters of one item type in one grid,
where replacement is the only option and a failed switch must revert — these are three item
types with three geometries, so each holds its own `PagedState` (content, tail, total,
generations), its own scroll state and its own focus memory, all hoisted in the pane and
retained for the session. A switch therefore **can never fail**: a tab with nothing yet shows
its skeleton, one whose page one failed shows its error card with Retry, and only a Refresh
replaces what a tab shows. The 300 ms tab debounce (§11.4) keeps its one purpose — a tab
merely crossed by the d-pad puts no request on the wire — and gates only the first load of an
empty tab; a press loads at once. Refresh re-reads the stats and page one of the selected tab
with its content staying on screen; the host's START effect re-reads the stats and fills only
the selected tab if it is empty.

**Paging.** Musicians and albums page by `page`/`per_page` at the contract's 48; the track list
by `limit`/`offset` at 50 with `has_more` as the cursor, an empty page stopping the walk
regardless (a library shrinking between requests). Prefetch fires two grid rows or six list rows
from the end; the tail is skeleton geometry matching the real cells (circles, squares, rows) on
Idle and Loading, a full-width Retry on failure, nothing at the end.

**Musicians** are `IglooPosterCard`s with `artworkRadius = radius.pill` — §8.2's circle is a
radius, not a component — the Person glyph as fallback, text centred beneath, and one sentence:
"The Beatles. 3 albums, 40 tracks." **Albums** are the §11.3 rail's square cards on the grid.
Both grids are the §11.4 grid verbatim: `gridColumns`, `Dp.Unspecified` cells, left column to
the spine, right edge and last row pinned, the entry cell carrying the pane's anchor and the
return requester.

**Tracks** is a `LazyColumn`: **letter headers** (A–Z, `#` for everything else, the server's
own first-character bucket with no trimming so the headers can never disagree with the sort)
as plain text the d-pad never lands on, and the **three-action row** below. Each bucket's first
row folds "Tracks starting with A." into its Play control's sentence — §11.5.1's disc rule at
row scale — computed at append time over the whole loaded list, so an append across a bucket
boundary keeps exactly one fold. Above a populated list sit **Play all** (Primary) and
**Shuffle all** (Ghost, label swapping to "Shuffling…" with a `Loading` state while its first
batch is out; never disabled, so the focused node survives the request; a second press cancels
the first). The first row's up returns to the **last-focused action button**; the buttons' down
lands on the entry row's Play.

**The row.** `TrackRow` (§9.1): Play, Like, More — all focusable, none hidden until focus, the
row itself never a target. Play is the entry column and carries the row's whole sentence with
"Liked" as its state; Like carries the like state ("Liked" / "Not liked" / "…, saving" while
pending / "Like status unavailable" and inert until the liked set is seeded) and an action that
names the track; More names the track and says "None available." when it has nowhere to go —
kept composed either way so the column geometry every row shares survives. Vertical moves
**keep their column**: the three controls are `controlHeight` squares at fixed x, so a lazy
list wires only its edges and lets the spatial search do the rows, while a plain column (the
two detail pages) wires every row outright. While any control holds focus the row paints
`muted @ 0.50` at `radius.lg` — the row's only visual role. Like state is one session-scoped set
(`TrackLikesViewModel`) every surface reads, seeded from `/music/tracks/liked-ids`, flipped
optimistically with per-track FIFO writes, and rolled back by one re-seed after a failure, whose
notice renders on the surface the user is looking at (§10).

**More** is an `IglooMenu` — "Go to album", "Go to artist", each present only when the row has
that id and the host can open that page — hosted as the pane's last child with the body cleared
while it is up; dismissal lands back on the More that opened it. On the Tracks tab the pane's
return requester rides the entry row's **remembered column**, so Back from the player lands on
Play and Back from an album opened through More lands on More.

**Playback launches** go through the view model's play-request flow into the host's one music
player (§11.8.2): a row's Play queues every track loaded so far starting at that row; Play all
queues the loaded rows and keeps paging the library as it plays; Shuffle all fetches the
server's first random batch and refills with exclusions. The host accepts a request only while
the pane is what the user is looking at, so a batch landing after they left never puts a player
over a surface that did not ask for one. Closing the player returns focus to the exact control
that launched it.

**Host.** `PaneBranch.Music`, `DetailsOrigin.Music` (restoring only while the destination is
Music, the `MoviesGrid` rule), `openAlbum` gains the origin parameter `openMovie` always had,
and the music player's launch site is recorded beside its request so the right overlay's close
tears it down and the right requester gets focus back.

#### 11.5.1 The album detail screen, as built

**Shape.** The §11.4.1 overlay contract, third occupant of the host's **one details slot**: a
full-screen in-tree overlay above the shell on the opaque `background` token, mutually exclusive
with both movie detail pages and the musician page (§11.5.2) because `IglooRoot`'s open callbacks
close the other view models before opening this one — which is what keeps Back, the
accessibility fence, and focus restoration single-path. The host owns Back and focus restore
through the same `DetailsOrigin` machinery: the Home rail's `Rail(LatestAlbums)`, the Music
pane's `Music`. A same-slot replacement — the album opening an artist, the artist opening an
album — leaves the origin untouched, so Back from the second page lands where the first was
opened from; the web's two-deep stack is a recorded backlog item, not this pass. `AlbumDetailsViewModel`
is the `TheaterMovieDetailsViewModel` shape — one read, nothing to write, its own
`errorOrKeep` — over `GET /music/albums/details/{id}`.

**Units.** Every duration on this wire is **milliseconds** — `Track.duration` and
`total_duration` both, like every other music endpoint. The mapping divides once, at the edge;
formatting is web parity (`"1h 2m"` / `"42m 10s"` for the album, `"m:ss"` for a track, blank
for a missing duration).

**Hero.** The web page's treatment: there is no separate music backdrop asset, so the **album
cover itself** is the full-bleed backdrop, cover-cropped, with §11.4.1's exact two scrims and
decode-gated over-media licensing. The cover is used **verbatim** (an absolute Spotify URL or
nothing — no proxy, no bearer, §11.3.2's album-rail rule). Beside a square cover
(`posterWidth` × `albumAspect`, Music-glyph fallback, decorative): title at `titleLarge`
(2 lines), the artist line, three §11.4.1 detail chips (release date — full date, else bare
year — track count, total duration) cleared into one sentence, the genres line, the popularity
meter, and the action row.

**Spotify popularity.** The meter is web parity: glyph + "Spotify popularity" + bold score over
a 4dp fill bar at `score/100`. Glyph and fill wear **Spotify's brand green** (`#1DB954`) — the
one deliberate brand color in the app, because the number is Spotify's and painting it `primary`
would claim it as ours. The meter is silent and unfocusable; its score rides the hero reading
stop's sentence and the facts panel's row.

**Actions.** Play Album (Primary, the entry anchor, held through the loading→loaded swap by the
skeleton's geometry-matched stub) and Shuffle (Ghost, over-media resting fill per §3.2). Both
are live: Play Album maps the loaded details into the §11.8.2 music player synchronously (the
tracks are already on screen; no deferred-play flow), and **Shuffle** maps the same queue
through a Fisher-Yates permutation of a copy (`docs/music-shuffle.md`'s finite-queue model),
starting at the top. The player's return requester is **parked on whichever control launched
it** — Play Album, Shuffle, or one row's Play — saved across recreation, so its close lands
back there. An album with **no tracks composes no action row at all** (web parity, and the
inert-control rule); the facts panel takes the entry anchor, requested safely. Row edges pinned;
up reaches the hero reading stop only while a spoken reader runs.

**Track list.** §11.5's **three-action row** (`TrackRow`), which superseded the one-stop rows
the page shipped with before playback existed: index gutter, then Play — carrying the row's one
sentence, "Disc 1. Track 1. Yesterday. Rock, Pop. 2 minutes and 5 seconds." — title and genre
line, duration, Like, More. A row's Play starts the album queue **at that row**. More offers
"Go to artist" only (the row already sits on its album). Multi-disc albums get plain-text
"Disc N" headers, and — since TV TalkBack never reaches plain text — each disc's **first row
folds "Disc N." into its own sentence**, the §11.4.1 heading-folding rule at row scale. The
vertical chain is hand-wired end to end and **column-stable** (actions → artist chips → every
row in disc/index order → facts panel, each control knowing the row above and below in its own
column), horizontal edges pinned per row; up from the first row returns to the **last-focused
chip or action**, and up from the facts panel lands on the **last row's Play** — nearest-edge
re-entry, the deliberate list contract (a list, unlike a rail, keeps no `lastFocusedKey`;
position is the memory).

**Artists.** Under the hero, in web order. With a musician screen to open (§11.5.2) and a real
id to open it on, each credited artist is a **Ghost button** ("Open The Beatles") chained left
to right with the row's edges pinned, sitting between the actions and the rows in the vertical
chain. Where there is nothing to open — an album whose only name is its own `musician` column,
which carries no id — the chips stay display-only prose, semantics cleared, the names reaching a
screen reader through the hero stop's sentence and the facts panel's Artist row.

**Facts panel.** "Album Details", the §11.4.1 About treatment verbatim: heading outside the
panel, `radius.xl` surface focus target, one cleared announcement folding the heading in. Rows —
Release Date (full date only), Total Tracks, Total Duration, Artist, Genres, Discs (multi-disc
only), Audio Quality, Spotify popularity ("73 / 100") — each dropped when absent. Audio Quality
is the web derivation exactly: dominant codec by track count (first past the post), peak
bitrate in kbps, channel layout only when uniform across the album, `·`-joined, absent parts
dropped, no summary without a codec.

**Shared with movies.** `SectionHeading` and `Modifier.readingStopTarget` moved from
`feature/movies/MovieDetailsSections.kt` to `feature/shared/DetailsReadingStops.kt` (the stop
grew a `radius` parameter for row-shaped targets); everything else — `AboutSection`,
`DetailsRailSection` — stays movie-private until a second caller earns the move. The track row
never uses `readingStopTarget`: a cleared row would erase its three controls, so each control
clears its own subtree and the row's text nodes are cleared into Play's sentence.

#### 11.5.2 The musician detail screen, as built

**Shape.** §11.5.1's overlay contract, the one details slot's **fourth occupant**, over
`GET /music/musicians/{id}` through `MusicianDetailsViewModel` (the album view model's shape).
Opened from a Musicians-tab card (`DetailsOrigin.Music`), a track row's "Go to artist", or an
album page's artist chip — the last two replacing the overlay that was up, origin untouched.

**Hero.** The artist's **thumbnail** blown up as the full-bleed backdrop (verbatim URL, no
proxy) with §11.4.1's two scrims and decode-gated over-media licensing; beside a circular thumb
(`posterWidth` at `radius.pill`, Person-glyph fallback, decorative): name at `titleLarge`,
three chips cleared into one sentence (album count, track count, total duration), the genres
line, the §11.5.1 Spotify popularity meter, and the action row — **Play all** (Primary, the
entry anchor) and **Shuffle** (Ghost), both live over the musician queue, the return requester
parked on whichever launched the player. With no tracks the action row is not composed and the
entry anchor moves to the discography rail, or to the facts panel with no albums either. The
hero reading stop exists only under a spoken reader.

**Discography.** An `IglooMediaRail` of the artist's albums as square cards ("Help!, 1965"),
newest release first, each opening the album (a same-slot replacement). The rail keeps its own
last-focused card, so up from the first row re-enters it there.

**All Tracks.** Every track across the discography as §11.5's rows — the album title as the
subtitle and More's one destination ("Go to album"), inert where the wire has none — with a
row's Play starting the musician queue at that row. Chain: actions → rail → rows (column-stable)
→ facts panel; edges pinned.

**Facts panel.** "Artist Details", the §11.4.1 About treatment: Albums, Tracks, Total duration,
Genres, Spotify popularity, Spotify followers, About (the summary) — each dropped when absent.

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

The movie picture is Media3 1.11's Compose `ContentFrame` over a `SurfaceView`, with
`ContentScale.Fit`. The source aspect ratio is preserved: scope movies letterbox, 4:3 movies
pillarbox, and anamorphic/non-square-pixel sources follow Media3's reported display aspect.
Igloo's Compose chrome remains separate, with the system-styled `SubtitleView` layered over the
fitted picture. `SurfaceView` is required for TV-quality timing, power use, full-resolution output,
and HDR paths; do not replace it with a hand-attached view or a texture surface for convenience.

Chrome is a top bar (title + back) and a bottom control bar that **auto-hide after idle** and
reappear on any d-pad or media-key event. Controls: seek bar, current/total time, rewind,
play/pause, fast-forward, quality chip, chapters, volume. A **Resume** dialog offers resume vs.
start over.

**Chapters** is a text-word button (§5.4) between Forward and the Audio/Subtitles pair — a
chapter jump is a seek, so it sits with the seek controls. It appears only with two or more
chapters (one chapter spans the whole movie; a one-destination menu is a choice with no
alternatives) and is request-driven, present before the engine reports anything. The menu is the
§9.3 recipe like the track menus: `IglooRadioRow`s labelled by title with "Chapter N" standing
in for the blank titles real file metadata produces, a trailing start timecode, spoken labels in
time words. The `selected` mark tracks the playhead live and entry focus lands on the current
chapter. **Selection is dismissal** — the one deliberate departure from the track menus'
stay-open rule, because a jump's result is the picture hidden behind the scrim, not something to
keep adjusting; focus returns to the Chapters button.

D-pad and media-key mapping:

| Input | Action |
|---|---|
| Center / Play-Pause | Play / pause |
| Play | Play (never pauses) |
| Pause | Pause (never plays) |
| Left / Rewind | Seek back |
| Right / Fast-Forward | Seek forward |
| Up / Down | Show chrome, move between controls |
| Back | Exit (or dismiss chrome first) |

Play is an intent, not a synonym for `isPlaying`: buffering can report no rendered playback while
autoplay is still pending. The icon and toggle use `playWhenReady`, so Pause during initial load or
rebuffering cancels pending autoplay and the ready transition cannot restart behind the user's
back.

The in-player **Quality** menu always lists the same seven modes as Playback Settings, in enum
order: Direct, Remux, 2160p at 16 Mbps, 1080p at 8 Mbps, 1080p at 6 Mbps, 1080p at 4 Mbps, and
720p at 3 Mbps. Source height and the current audio route do not remove rows. A requested mode
remains the request even when the backend reports a different effective profile; the selected
mark reports that effective profile without silently rewriting the user's choice. Selecting the
active row while another switch is pending cancels the pending switch and restores the active
source with the latest play/pause intent.

Host lifecycle is a strict resource boundary. `ON_PAUSE` silences playback and clears pending
autoplay; transport commands are ignored until the host resumes. A non-configuration `ON_STOP`
fully releases MediaSession, ExoPlayer, loading, callbacks, keepalive, and the backend HLS
session. Returning keeps the player overlay, reconstructs a fresh engine at the last reported
position and latest requested quality, and starts paused. Playback resumes only after an explicit
Play press. Configuration recreation still replaces and releases the engine through composition
disposal, while saveable screen state preserves its position and explicit transport choice.

The details page does not launch from partial preparation. Technical details and watch progress
each resolve to pending, successful (including empty tracks or nullable progress), or failed. One
Play intent waits for both successful responses regardless of arrival order; failed reads are
retried by Play, and the capability gate and resume prompt never consume missing or failed data.

Progress saves to the backend every 15s, starting only after ~15s of real playback. Like the web,
a pause, a trip to the background, and the exit or end of the movie each write at once, with no
played-time minimum: any position past 30s, or at 95% of the runtime, is eligible, so resuming
near the end and leaving seconds later still marks the movie watched, and so does seeking to the
end and leaving. A pause and the background stop that follows it write once, not twice. The exit
write is never cancelled by the client, so backing out of the app right after the player cannot
lose the end of a movie. Save failures never pause playback. They hold the chrome open with a polite,
D-pad-reachable inline Retry and follow the user back to movie details if exit finishes first.
Retry keeps the original save session id and takes a higher sequence. A retry or later cadence
success clears the error, safely restores focus if Retry held it, and refreshes movie details and
Continue Watching.

**Media3 does not go through the app's Ktor client**, so playback requests carry no
`Authorization` header and their 401s never reach the session state machine. When the player
lands it must inject the bearer from `DeviceCredentialSource` and bridge a 401 from
`HttpDataSource.InvalidResponseCodeException` into `AuthEventBus.signalUnauthorized`. Every
media route inherits the global security block; there is no signed-URL escape hatch.

#### 11.8.1 Trailer player (YouTube embed)

The extras rail's player, and deliberately **not** §11.8's: extras exist only as YouTube video
keys — the backend proxies thumbnails, never video — so playback is a full-screen in-tree
overlay (the third layer: shell → details → player) hosting a WebView with the official YouTube
IFrame Player API, the same mechanism the web client uses. Media3, the bearer-injection
paragraph above, progress saves, resume, chapters, and the quality chip do not apply: **trailers
don't report progress**. The surface is full-bleed (§2.5); the chrome keeps the safe-area inset.

The page is loaded with the **Igloo server's origin** as its base URL — the same real,
attributable origin the web client's trailer page has. Device-verified 2026-08-16: a borrowed
`https://www.youtube.com` base URL is rejected by the embed with error 152, the server origin
plays. Device-verified 2026-08-29: the engine's surface is the WebView inside a plain
FrameLayout, and the WebView carries a WebChromeClient — with the WebView as the direct child
of Compose's `AndroidView` holder, Chromium decodes the embed's audio but composites no video
(a black picture over playing sound), while the wrapped WebView renders. Every control lives in Compose: the WebView is **never focusable** and never in the
TalkBack tree — all input belongs to the chrome. Chrome is a §11.8-shaped reduction: top bar
(Back, title, type label), bottom transport (rewind 10s, play/pause, forward 10s) over a 4dp
seek track with current/total timecodes (one cleared, non-focusable summary node — no live
region; a narrating timer is §12 noise). No volume control — TV remotes drive device volume.
Entry focus is Play/Pause. Chrome auto-hides after **4s** of no input, and only while playing —
a paused frame with no UI reads as a hang. It stays composed while hidden (alpha only), so
focus and traversal never reshuffle.

| Input | Chrome hidden | Chrome visible |
|---|---|---|
| Center / Play-Pause | Toggle play/pause, show chrome | Activates the focused control |
| Left / Right | Seek ∓10s, show chrome | Move between controls (media keys still seek) |
| Up / Down | Show chrome, focus Play/Pause | Move between top bar and transport |
| Back | Exit player | Hide chrome; second Back exits |

While hidden, the chrome swallows every handled key — an invisible control must not activate.
The host owns close and focus restore (to the launching card, via the rail's `returnRequester`);
the details overlay underneath stays composed but leaves TalkBack traversal, exactly as the
shell does under it. **Ended auto-closes** through the same path (web parity). Errors resolve to
the details screen's error recipe — one pinned Retry, Assertive — with the embed's codes mapped
to plain sentences; 101/150 say outright that YouTube doesn't allow the video outside
youtube.com. Two watchdogs back the embed, and the inner one must fire first, because the first
error is sticky and a guard that reports second is a guard nobody ever sees: an in-page **8s**
guard on the IFrame API script, which names the narrower cause, inside a **12s** Kotlin guard on
player-ready that catches everything else that stalls. Activity recreation restarts the trailer
at 0:00 — a WebView cannot be parceled, an accepted trade for trailers.

#### 11.8.2 Music player, as built

The one music player layer — the movie player's sibling in every host contract (existence, Back
gating, focus restore all live in the host) — and a §11.8 reduction the way the trailer player
is: no resume prompt, no track/quality menus, no progress saves, no chapters. What it keeps and
what it changes:

**A queue with a source.** `MusicPlayRequest` is `(source, startIndex, tracks)`. Three sources
are finite and fully known at launch — **Album** (Play Album, Shuffle, or a row's Play, §11.5.1),
**Musician** (§11.5.2), **TrackList** (a Tracks-tab row: every track loaded so far, starting at
that row) — and two are **endless**: **LibraryInOrder** (Play all: the loaded rows, then pages
of `GET /music/tracks` appended in order until the library's total) and **LibraryShuffle**
(Shuffle all: `GET /music/tracks/shuffle` batches). Display metadata rides **per track**
(artist, album, cover), because a library queue crosses albums; an album queue repeats its own
on every entry. The source names the top bar, the session id (`music-album-<id>-<n>`,
`music-musician-<id>-<n>`, `music-tracks-<n>`, `music-library-<n>`, `music-shuffle-<n>`) and
the position line: "Track N of M" for a finite queue, "Track N of <library total>" for Play all,
"Track N" for a shuffle with no end to count to, then `· artist`, then `· album` unless the
source is the album.

**Refill.** An endless source refills through a pure-Kotlin `MusicQueueController` the screen
owns, on `docs/music-shuffle.md`'s rules: within ten tracks of the end one batch of fifty is
fetched, deduplicated within itself and against the whole queue, and appended to the engine
(`appendTracks`, `addMediaItems`; nothing already in the playlist moves). Exactly one fetch is
ever in flight — the loop is sequential — and leaving the screen cancels it, which is the
generation guard. Shuffle excludes the newest 200 queued ids. An empty shuffle response latches
exhaustion with one notice ("That's every track in the library."); the in-order queue latches
silently at `has_more = false`; a batch of only known tracks appends nothing and does not latch,
the next track change retrying. A failed refill keeps the queue and shows "Couldn't load more
tracks. The queue will play out." — an `IglooNotice` in the bottom block, polite, never a focus
stop, cleared by the next success (§10). **Nothing is trimmed from the queue's head** — a
deliberate deviation from the spec's 50-track history cap: trimming shifts ExoPlayer indices
under queued `TrackChanged` events, the queue lives only while the overlay is up, and stable
indices are what keep the reducer and the player agreeing. `MAX_QUEUE_TRACKS = 500` bounds it
instead (refills stop there), which also keeps the saved request bounded: the host keeps the
request current as the queue grows, so a recreation restores every appended track, and a queue
past the ceiling is not saved at all rather than truncated under its saved position.

**One playlist, not one item.** The whole queue is a single ExoPlayer playlist in queue order,
so auto-advance and skip semantics live **below the engine seam**;
the chrome only learns "the queue moved" through a `TrackChanged` event, the one licensed
reset of the duration-never-shrinks rule (each track's timeline is genuinely new; the wire
duration bridges the gap until the container is parsed). The licence is **an index that
actually changed**: setting a playlist is itself an item transition, so a replacement engine
reports the queue's own start index before a frame plays, and reading that as an advance would
zero the very playhead a Retry or a background return is resuming from — with no position tick
to repair it when the track never reaches READY. A report naming the current index only
refreshes the duration. Previous is the platform's standard: restart past ~3s, cross to the
prior track under it.

**No surface on the seam.** The screen draws the current track's cover itself (`AsyncImage`,
cover-fill over the over-media control fill, Music-glyph fallback, decorative); the engine
interface is pure Kotlin. Covers are verbatim absolute Spotify URLs — the session's bitmap
loader is deliberately **not** the movie's bearer-authed one, while the track streams do use
the bearer data-source factory.

**Chrome never hides.** §11.8 licenses resting chrome only over a moving picture, and a
static cover is not one — so there is no auto-hide clock, no reveal step, and **Back always
means leave** (one press, unlike the movie's dismiss-then-close). Layout: top bar (Back +
the source's title), centered cover, bottom block — the refill notice when there is one, track
title over the position line (one cleared semantics node), seek bar, and a five-button
transport: previous, rewind 10s,
play/pause (the entry anchor), forward 10s, next. Row edges are pinned. Without a spoken
accessibility service, Up and Down connect the transport directly to Back. While a spoken
service runs, the metadata pair becomes an actionless **reading stop** in that route:
transport Up → metadata → Back, and Back Down → metadata → transport. It speaks title, track
position, and artist as one sentence and wears the §6.1 panel treatment with no scale; the
extra press never enters the sighted path.

**The key map grows a music pre-handler.** `MediaNext`/`MediaPrevious` and
`MediaSkipForward`/`MediaSkipBackward` mean **tracks**, intercepted before the shared
`handlePlayerKey` would spend the Skip keys on ±10s seeks; `MediaRewind`/`MediaFastForward`
(and d-pad on the transport) keep the in-track seek. The §11.8 intent rule carries over:
the play/pause icon follows `playWhenReady`, and on the error surface every media key is
swallowed without acting.

**Session and lifecycle.** A per-queue MediaSession (id per source, above) carries per-track
`MediaMetadata` (title/artist/album/artwork, `MEDIA_TYPE_MUSIC`), so the system's now-playing
surface tracks auto-advance — and a library queue crossing albums — for free. For the overlay's complete mounted lifetime —
playing, paused, loading, buffering, or error — the Compose host view keeps the display awake,
preventing inactivity-driven Ambient Mode from interrupting the visit. Disposal restores the
view's exact prior keep-awake value. This does not authorize background playback: the §11.8
lifecycle contract remains verbatim. `ON_PAUSE` silences, a non-configuration `ON_STOP` fully
releases player and session
(**stop-on-exit: no background playback**, a deliberate scope decision), and returning
rebuilds a fresh engine **paused** at the saved track index and position. A polite 1dp live
region narrates play state with the track title riding in the sentence, so an auto-advance —
same phase, new track — still announces.

**Ends and errors.** The queue finishing closes the player through the host (focus restores
to the control that launched it, like every overlay: Play Album, Shuffle or a row's Play on
either detail page, Play all, Shuffle all or a row's Play on the Music pane). Errors are the movie recipe minus HLS recovery: sticky
first error, pinned Retry that rebuilds the engine at this visit's own playhead with a fresh
play intent, Close instead when the session is revoked. Entering the terminal boundary detaches
the ExoPlayer listener before `stop()` or playlist clearing and emits the intentional paused
transport state plus the error directly; teardown therefore cannot publish a false
`TrackChanged(0)` or replace the later-track playhead Retry must preserve. Deliberately not
duplicated from the movie engine: HLS controller/preflight/recovery,
quality/audio/subtitle selection, timeline offsets, progress saves. Play-stats reporting
(`POST /api/music/user-stats/play`) is a named follow-up — and without it there is no ViewModel
at all: screen + engine + reducer + the queue controller, which is pure Kotlin and would move
into that ViewModel unchanged.

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
  continue-watching card needs title, year, and fully spoken remaining time even though it stays
  visually bar-only. Decorative images are hidden from the accessibility tree.
- **No focus traps**, and no custom focus handling that breaks screen-reader traversal.
- **TalkBack for TV follows input focus.** It does not linearly traverse non-focusable text the
  way handset TalkBack does — a plain text node is unreachable, and unspoken, on a TV. Text a
  screen-reader user must hear either rides a focusable node's semantics (the metadata sentence,
  Play's resume state) or becomes a **reading stop**: a focus target with no action, composed
  only while `rememberSpokenAccessibilityEnabled()` reports a spoken service, announcing the
  prose in one cleared node with its section heading folded in (§11.4.1 Reachability is the
  as-built example). Heading, live-region and traversal-order semantics still matter for the
  platform's other surfaces, but none of them make text reachable by d-pad.
- **State changes are announced**: loading → loaded, empty results, errors, and action failures.
- **A pane whose title is not drawn as text announces through `paneTitle`, not through a live
  region on a hidden node.** Swapping the shell's destination moves no focus and, since §11.3
  removed the pane header, changes no text — so without this the change is silent. `paneTitle` is
  the platform's own mechanism for it (`CONTENT_CHANGE_TYPE_PANE_TITLE`), it is what the menu,
  both dialogs and both overlays already use, and unlike a 1dp live-region node it does not put a
  second copy of the destination's name into the tree to collide with the rail's own row.
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

**2026-09-19 — The Music screen: three tabs, the three-action row, the musician page, and a
queue-shaped player (§11.5, §11.5.1, §11.5.2, §11.8.2, §9.1, §8.2).**

- **§11.5 is as built.** Musicians · Albums · Tracks on the §11.4 shape, each tab retaining its
  own pages, scroll and focus memory, so a switch can never fail and only Refresh replaces —
  a deliberate departure from §11.4's replace-and-revert, which three item types with three
  geometries do not need. **Playlists is deferred** with Liked inside it, as on the web.
- **§11.5's three-action row exists** (`TrackRow`, §9.1) and the album page adopts it, closing
  the deferral §11.5.1 recorded on 2026-08-31: Play speaks the row once, Like carries the state,
  More is an `IglooMenu`, vertical moves keep their column. Like state is one session-scoped set
  (`TrackLikesViewModel`) with optimistic FIFO writes and a re-seed as rollback.
- **§11.5.1:** Shuffle is live (client Fisher-Yates on a copy), a row's Play starts the album at
  that row, the player's return requester parks on whichever control launched it, and the
  artist chips become buttons that open the artist. The one details slot stays single: a page
  opening the other **replaces** it with the Back origin untouched (backlog: the web's two-deep
  stack).
- **§11.5.2** records the musician page: thumb-as-backdrop hero, Play all / Shuffle over the
  musician queue, a discography rail, every track as a row, a facts panel.
- **§11.8.2:** the player takes a queue with a source — Album, Musician, TrackList finite;
  LibraryInOrder and LibraryShuffle endless, refilled by a pure-Kotlin `MusicQueueController` on
  `docs/music-shuffle.md`'s rules — with per-track metadata, per-source session ids and position
  lines, a polite refill notice, and **no head trimming** (a recorded deviation; a 500-track
  ceiling bounds the queue and the saved request instead).
- **§9.1 / §8.2:** `IglooPosterCard` gains `artworkRadius`, `centerText`, `semanticLabel` — the
  musician circle is a radius, not a component; `IglooIconButton` gains `stateDescription`,
  `actionLabel`, `iconTint`. The Movies append state moves to `feature/shared/AppendState`.
- **Wire:** `TrackListItem` and `SimpleMusician` had drifted past decoding after the `b8dc4c2`
  sync (`docs/known-issues.md`, instances nine and ten); `MusicianDetailsData` is typed.

**2026-09-02 — Movies tab review: the debounce, the genres wait, and one pill body (§11.4,
§9.1, §3.1).**

- **Fix (§11.4):** `loadFirstPage` cancelled the in-flight page and bumped its generation *after*
  the endpoint check bailed, so landing on the Genres tab with nothing to page left an earlier
  request alive — and its failure reverted the tab out from under the user. Both now happen
  before the bail, and the bail clears `refreshing` since nothing is outstanding.
- **Fix (§11.4):** the Genres tab reported "Genres aren't available right now" while the genre
  list was still in flight. An empty list cannot tell "not asked yet" from "there are none", so
  the state now records whether a request has settled and the tab draws a skeleton until it has.
- **Fix (§11.4):** Refresh from the placeholder acknowledged nothing — page one had no request to
  attach the label to — so the genres round trip carries it. Sort there flipped the visible
  direction against a hidden grid it never re-read, and is now a deliberate no-op.
- **Fix (§11.4):** a failed page reverted to the committed genre without re-checking it against
  the current list, reviving a genre a refreshed list had dropped and leaving no chip to change
  it.
- **§11.4 tabs now debounce.** The highlight still moves on focus; the fetch waits 300 ms, so a
  slide across the strip issues one request instead of one per tab crossed, and the header stops
  flashing "Refreshing…" on the way past. `IglooTab` gains `onPress`, the deliberate signal that
  skips the delay (§9.1).
- **§9.1** factors `IglooFilterChip` and `IglooTab` onto a shared internal `SelectablePill`;
  **§3.1** gains the tab strip's `0.50f` row, which §9.1 was describing without it.

**2026-09-02 — Movies index: a tab control (§11.4, §9.1, §9.2).**

- **§11.4 reverses the 2026-08-29 "still no tab control" rule.** The single chip row (All ·
  Liked · every genre) becomes a fixed **tab strip** — All Movies · Genres · Liked, the web
  page's shape with Liked standing in for Playlists — and the genres move into a **picker row**
  under the Genres tab, still `IglooFilterChip`s. Tabs **select on focus**; the section records
  why the superseded pass-over fetch is harmless, why a switch landing under a focused tab must
  not re-anchor focus, why Refresh and Sort wire `down` to the selected tab, the no-genres
  placeholder, the remembered genre and its by-id re-resolution, and the tab + genre revert on
  failure.
- **§9.1** gains `IglooTabRow` / `IglooTab`; **§9.2** records that tv-material's `TabRow` was
  deliberately not adopted.
- **Genre-list rules the section had not recorded:** a *successful* empty response is
  authoritative and clears the remembered genre (only a failure leaves the last one standing),
  and an active Genres tab re-fetches page one only when a landing list changes the resolved
  id — so the start effect's re-read on every lifecycle START does not re-page the grid.
- `MoviesUiState.filter` is now derived from `tab` + `genre` (nullable for the placeholder);
  `MoviesActions.onSelectFilter` splits into `onSelectTab` and `onSelectGenre`.

**2026-09-01 — Music-player review: the startup queue report, and shared player chrome.**

- **Fix (§11.8.2):** a `TrackChanged` naming the index already current no longer resets the
  playhead. Setting the playlist is itself an item transition, so the real engine reports the
  start index before a frame plays; both the reducer and the screen were treating that as an
  advance, so a Retry or a background return could restart the track from 0:00 whenever no
  position tick arrived in between (a stream that fails before READY never produces one). The
  fake engine now emits that startup report too, so the screen suite exercises the real event
  order rather than a friendlier one.
- Both overlay request savers moved to `feature/home/PlayerRequestSavers.kt` and now restore
  defensively: a slot an older build wrote that will not parse comes back as "no overlay"
  instead of throwing inside saved-state restoration, which crashes the relaunch.
- The engine refuses an empty queue with a named error rather than preparing nothing and
  reporting a silent `Ended` that reads to the host as "the album finished".
- Every entry-focus request across the three players now goes through `requestFocusSafely`.
- **Shared (§11.8):** `PlayerChromeCommon` gains `PlayerTopBar`, `playerTopScrim`/
  `playerBottomScrim`, `transportFocus`, `PoliteAnnouncement`, and `PlayerFailureSurface`;
  the movie/music/trailer screens had copies of each. `PlayerHostLifecycle` now owns the
  ON_PAUSE/ON_STOP/ON_RESUME contract and the "a lifecycle silence is not transport intent"
  rule that the movie and music screens had duplicated line for line. In `playback/media3`,
  `PlaybackTicker` and `IntentRoutingPlayer` replace each engine's own copy, and
  `buildIglooMediaSession` holds the per-instance id and launch-activity wiring both session
  builders had. `playerErrorEvent` became `playerFailure` returning a neutral
  `PlaybackFailure`, so the music stack no longer constructs a `MoviePlayerEvent`.
- The three reducers' `clampToPlayable`/`keptDuration` arithmetic moved to
  `playback/model/PlayheadArithmetic.kt`. **The reducers themselves stay separate** —
  different phase sets (`AwaitingResume`), three well-covered state-machine suites, and after
  the arithmetic moved there is nothing left to merge but ceremony.

**2026-09-01 — Music-player review hardening (§11.8.2).**

- The full player-overlay lifetime now owns the Compose host view's keep-awake flag and restores
  its exact previous value on disposal; the real-background stop/release contract is unchanged.
- Track metadata becomes a conditional TalkBack reading stop between the transport and Back,
  while the sighted focus route remains direct.
- A terminal stream failure detaches the ExoPlayer listener before playlist teardown and emits
  its paused intent and error explicitly, preventing a teardown `TrackChanged(0)` from corrupting
  later-track Retry state.

**2026-09-01 — Music player: Play Album is live (§11.8.2, §11.5.1).**

- New **§11.8.2**: the music player as built — the album as one ExoPlayer playlist (auto-advance
  and skip semantics below the engine seam, `TrackChanged` as the one duration reset), a
  pure-Kotlin seam with no surface member (the screen draws the cover itself), always-visible
  chrome with one-press Back (nothing to reveal over a static cover), the music key pre-handler
  (Skip keys mean tracks, Rewind/FastForward stay ±10s), a per-album MediaSession with
  per-track metadata, and the verbatim §11.8 lifecycle contract with **stop-on-exit — no
  background playback** recorded as a deliberate scope decision. No ViewModel: play-stats
  reporting is a named follow-up, and without it the player is screen + engine + reducer.
- **§11.5.1** flips its Actions paragraph: Play Album now maps the loaded details into the
  player synchronously (no deferred-play flow); Shuffle stays the honest stub for its own pass.
- §5.4's icon set gains `SkipPrevious`/`SkipNext` in the hand-authored 24dp style;
  `findHostActivity()` hoisted to `PlayerChromeCommon` (third caller);
  `playerErrorEvent` gains a `mediaNoun` so one mapping speaks for movies and albums.

- New **§11.5.1**: the album detail overlay as built — cover-as-backdrop with §11.4.1's scrims,
  square cover geometry, the Spotify popularity meter (brand green `#1DB954`, the app's one
  deliberate brand color, silent and unfocusable), stubbed Play Album / Shuffle
  (host-owned no-ops until playback lands, the More-menu precedent; no action row at all on a
  trackless album), one-stop track rows with disc headers folded into each disc's first spoken
  row, the About-treatment facts panel with the web's audio-quality derivation, and the
  hand-wired chain with nearest-edge list re-entry. **Explicitly defers §11.5's three-action
  track row** to the playback pass — a row announcing "Play" that does nothing teaches the user
  the press is wasted.
- **Milliseconds on this wire**: `/music/albums/details/{id}` durations are ms where the tracks
  list speaks seconds; the mapping divides once at the edge and the section says so.
- The LatestAlbums rail gains the `returnRequester` it never had, and its cards their long-
  deferred `onClick` — the "album detail has no destination yet" carve-out is retired, and the
  rail-behavior suite's no-action assertion moved to the null-callback case like every other
  card.
- `SectionHeading` and `readingStopTarget` promoted to `feature/shared/DetailsReadingStops.kt`
  (with a `radius` parameter for row-shaped stops); `AlbumDetailsData` and friends are now
  typed wire models (`Album`, `AlbumTrack`, `AlbumArtist`, `TrackGenre`) instead of raw
  `JsonObject`s.

**2026-08-29 — Movies index: sort, genre filters, and Liked (§11.4, §9.1).**

- **§11.4 reverses its own "no sort control, no filters" rule**: the index gains a
  direction-only Sort toggle beside Refresh (title is the backend's only sort field) and a
  filter chip row — All · Liked · genres with counts — between the header and the grid. Still
  no tabs: filters are one chip press in a single always-rendered row. The section records the
  chip row's plain-`Row` rationale, its silent genre degradation, the full focus contract
  (grid → selected chip → Refresh; first-chip left to the spine, last-chip right pinned), and
  the transition rules — wholesale replacement on switch, **selection reverts on failure**,
  per-view counts and empty copy, and the silent Liked reconcile after a like committed in the
  details overlay.
- **§9.1** gains `IglooFilterChip`: selected `primary` fill composing with (never competing
  against) the §6.1 focus ring, one cleared node speaking name, count, and "Selected".
- `MovieDetailsViewModel`'s committed-write listener grows a Like twin
  (`onLikeStateCommitted`), wired to the movies view model so a shown Liked grid re-reads
  itself when a like toggle commits.
- The pane's card-less anchors (skeleton, error Retry, empty box) now also carry the
  overlay-return requester. Found on-device: unliking the only Liked movie and pressing Back
  landed focus on the navigation rail's first row, because a return requester whose card was
  disposed reports its focus request as successful while moving nothing, so the host's anchor
  fallback never ran. Pinned by `backAfterTheGridEmptiedUnderTheOverlayLandsOnTheEmptyState`.

**2026-08-28 — Movies index: an infinite-scroll library grid.**

- **§11.4** replaces numbered pagination with infinite scroll and records why: an always-occupied,
  unfocusable tail plus a pinned last-row edge is a stronger bound for a remote than a second
  focus region below the grid. Adds the all-users Refresh and states plainly that it is a
  client-side re-read, not the admin scan.
- **§8.3** gains the paged-grid rules — disjoint tail keys, `derivedStateOf` over `layoutInfo`,
  the hoisted grid state and handled-generation, and the list living in the view model.
- `SkeletonCell` moved out of `IglooMediaRail` to `core/ui/IglooSkeletonCell.kt` so the rails and
  the grid share one placeholder; `IglooPosterCard` gained the `Dp.Unspecified` fill-the-cell
  width. `IglooDestination.Movies.supportingText` no longer renders — Movies has a real pane —
  and `paneBranchIsHome` became the three-valued `paneBranchOf`.

**2026-08-25 — Playback lifecycle and quality are one strict contract.**

- **§11.8** defines the seven always-visible in-player quality rows, requested-versus-effective
  mode behavior, pending-switch cancellation, and pause → release → paused reconstruction across
  a real host background trip.
- HLS session generations now rotate synchronously on stop so delayed cleanup cannot target a
  later session, and terminal player/preflight failures stop transport and network work before
  surfacing the error.

**2026-08-24 — In-player chapter menu.**

- **§11.8** gains the Chapters button and menu: between Forward and the track pair, gated at two
  or more chapters, request-driven. Selection is dismissal — the recorded departure from the
  track menus' stay-open rule — and the current chapter carries the live `selected` mark.
- **§9.1** — `IglooRadioRow` gains optional `detail` (trailing muted drawn-only text) and
  `semanticLabel` (spoken sentence overriding the drawn label, the `IglooButton` contract).

**2026-08-19 — The app stops looking like a phone app: full-bleed everywhere.**

Verified on a Shield that the window was never the problem — `mFrame=[0,0][1920,1080]`,
`mWindowingMode=fullscreen`, SurfaceFlinger `viewport=[0 0 1920 1080] destinationClip=[0 0 3840
2160]`, no letterbox anywhere. Every bar on that screen was drawn by us.

- **§11.1: the auth card is deleted.** One full-bleed canvas in two shapes — `Split` (identity
  left, controls right) and `Stacked` (identity above full-width content). `authCardWidth` becomes
  **`dialogWidth`**: 480dp was never the auth card's width, it was the measure of one column of
  controls, which is why the modal and both error cards had already borrowed it. `authCardWideWidth`
  (840dp) is deleted — `Stacked` is panel-driven, and 840 was always narrower than the panel it
  was centred on.
- **§8.1/§8.3: the pane applies no gutter.** It hands sections a `contentInset` and each applies
  what it owes. That is what finally makes §8.3's "rails pad content and let the scroll surface
  bleed" true rather than aspirational — it had been written since the TV-first rewrite and never
  built, because the pane's own `padding(end = safeArea)` re-clipped every rail. The `LazyRow`
  needs `fillMaxWidth()` as well as `contentPadding`: a lazy list sizes to its content, so a short
  rail ended where its last card did.
- **§11.3.1: the hero is full-bleed on all four edges** at `heightIn(min = 360dp)`, which leaves
  *more* rail visible than the old 280dp card did, because it no longer sits under 117dp of
  chrome. Its focus ring moved onto the bounded text plate — a ring traced around a bleeding
  surface sits in the overscan margin, the one place §2.5 says a TV may crop.
- **§11.2: the rail is a scrim, not a strip**, so art passes under the icon column. Deliberately
  not opaque even at x = 0: `sidebar` against `background` is two near-identical darks, and a flat
  column of it is the black bar this entry is about.
- **§11.3/§12: Home has no pane header**, and the destination announcement moved to `paneTitle`.
  The header's title had carried the only live region that could tell TalkBack the destination
  changed. A hidden 1dp live-region node was tried first and was wrong twice over: it put a second
  "Home" into the semantics tree, which made `onNodeWithContentDescription("Home")` ambiguous
  against the rail's own row.
- **The ambient backdrop had a latent seam.** `iglooAuroraBackdrop` drew each gradient at exactly
  `size` and then `translate`d it, so the drift walked the rect's trailing edge inside the viewport
  and left a band of bare `background` — 18dp across the top of a Shield, a hard horizontal line.
  Invisible while every screen using it happened to be background-coloured at the top; not
  invisible over a full-bleed canvas. The rects are now drawn one drift-amplitude oversized.
- **Manifest and theme.** `android.software.leanback` is `required="true"` — this is a TV client
  and installing on a phone is not a scenario. `Theme.Igloo` drops the phone Material parent for
  its `.Fullscreen` variant and sets `windowDrawsSystemBarBackgrounds=false`, which is
  load-bearing: the platform was painting a status-bar rectangle over the top of the window.
  `statusBarColor` and `navigationBarColor` are gone — they name things a TV does not have.
  `resizeableActivity="false"` was considered and **rejected**: that flag opts an activity into
  size-compat mode, which letterboxes the window. It manufactures the defect this entry removes.
- **The missing guard.** `QuickConnectLayoutTest`'s `assertFullyOnscreen` checked top and bottom
  only — which is how a surface occupying half the panel's *width* shipped. New `ShellBleedTest`
  and `AuthSurfaceLayoutTest` assert bounds against the viewport's edges, and a new
  `IglooDimensTest` case pins §8.2's four-posters-and-a-peek arithmetic, which had only ever lived
  in prose.

**2026-08-18 — Playback Settings dialog (§11.4.1, §9.1).**

The More menu's first item is display-only no longer: it opens the new `PlaybackSettingsDialog`
— the §9.3 modal recipe holding three flat radio lists (quality/mode, audio track, subtitles)
over a live plain-language explanation line and a Done button. Selections are session-only in
`MovieDetailsViewModel`, waiting for Play to land; labels, language names, and the direct-play
audio rule are ported from the web client's in-player dialog (`PlaybackSettingsMapping.kt`, web
parity — except direct and remux now say "Original quality" outright).

- **`IglooRadioRow` joins §9.1**: the menu-row recipe plus a drawn radio glyph, one cleared
  `RadioButton` node per row; the inert variant stays focusable but announces disabled.
- **`MovieDetailsActions.Library` reshapes**: `onPlaybackSettings` is gone — opening a
  host-owned overlay is host business, the `onPlayVideo` precedent — replaced by the three
  selection callbacks (`onSelectPlaybackMode`, `onSelectAudioTrack`, `onSelectSubtitle`).
- The host's Back priority gains a layer: trailer → playback dialog → More menu → details
  close → rail; the menu's focus restore is suppressed when its action just opened the dialog.

**2026-08-17 — More lands with its menu (§11.4.1, §9.1).**

The 2026-08-16 deferral is closed on its own terms: the trigger waited until it had a menu to
open, and now it does. The library hero's fourth action is the icon-only More trigger, opening
the new `IglooMenu` — the in-tree anchored menu (§9.1) — with Playback Settings, Watch
Together, Technical Details, and for admins Identify Movie and a destructive Delete Movie
behind a separator. Display only: items fire stubbed callbacks and close the menu.

- **`IglooIconButton` returns from `bc88a9c`**, restored with the caller it was deleted for;
  its §9.1 row is back. `IglooIcons` gains `MoreVertical`.
- **Admin items are hidden, not disabled** — never composed for non-admins, absent from focus
  and semantics alike. Admin comes from the session's `AuthUser.is_admin`; it is not persisted.
- **The overlay contract stretches to a third layer shape**: host-owned open flag and
  dismiss-callback focus restore (§9.3), screen-owned item list and anchor, unscrimmed because
  §9.1's scrim belongs to the rail and the modal.
- **The row's right-edge pin moved onto More**, the always-present control the disabled-Like
  rule was waiting for; Watched's hand-wired Right now skips a disabled Like instead of
  carrying the pin.

**2026-08-17 — The theaters rail gets a destination: the in-theaters detail screen (§11.4.2).**

`GET /api/tmdb/movies/{id}` behind the same detail screen, so a card in the Now Playing in
Theaters rail opens a real page instead of being a focusable that announces nothing. §11.3.2's
"no TMDB detail screen yet" is retired.

- **The screen was parameterized, not forked.** `MovieDetailsActions` became a sealed pair —
  `Library` (Play + the toggles) and `Theater` (Play Trailer, or no row) — and the mapping rules
  both sources share (key crew, cast cap, YouTube extras and their sort, the metadata sentence,
  the genres and production lines) moved to `MovieDetailsMapping.kt`. The one state rule they
  share, `errorOrKeep`, sits with `MovieDetailsState` instead. Everything TMDB cannot fill is
  absent in the one render model, exactly as the library's own secondary reads are until they
  land.
- **Two view models, one overlay slot.** `TheaterMovieDetailsViewModel` is a single read with no
  mutation machinery; the host picks whichever is open, so Back, the TalkBack fence and focus
  restore stay single-path. Merging them was rejected on the id collision alone: a TMDB id and a
  library id are both plain numbers.
- **The player's focus restore learned where it was launched from.** `VideoLaunchSite` rides the
  saved trailer request, so a trailer started from the hero returns to the hero button and one
  started from the rail returns to its card (§6.3) — and the theaters rail finally carries the
  `returnRequester` every rail that opens something needs.
- **Certification follows the backend scanner's rule**, not the web page (which shows none): US
  first, then any country — so the chip matches what the library page would show for the same
  movie later. The rating badge stays the §3.2 star badge over `vote_average`, matching the rail's
  own card rather than the web's separate TMDB chip.
- **A hero can now have no actions at all**, so the entry anchor falls to the first section and
  `MovieDetailsScreen` requests entry focus safely; `requestFocusSafely` and `withRequester` moved
  to `core/ui/FocusRequesters.kt`, where the host and the rail already needed them both.

**2026-08-16 — The detail screen stops moving under the user.**

The refinement pass closing `movie-detail-next-steps.md`'s backlog. The through-line is layout
and focus stability: nothing on the screen may shift while the eye — or the focus ring — is
committed to it.

- **`IglooButton` gains `labelVariants` (§9.1).** A toggle reserves the widest label it can
  show, so Watch→Watched and Like→Liked repaint in place instead of shoving the row's siblings —
  under the user's own press, and when the status request lands after first paint.
- **The resume strip's slot is reserved from first paint (§11.4.1).** The strip arrives with a
  late request and leaves when a movie is marked watched; either reflowed the bottom-anchored
  hero. The empty slot is invisible, silent to TalkBack, and matched by the skeleton's Play
  stub.
- **More is deferred and `IglooIconButton` deleted (§11.4).** A control that takes focus and
  does nothing on press spends the press to teach the user it is empty; the row is three
  actions until the menu exists, and the composable went with its only caller. (Its §9.1 row is
  gone; this entry and the 2026-08-15 promotion are the record.)
- **Up from the sections restores the last-focused action (§11.4.1)** — the same focus memory
  the rail keeps for its own cards, replacing the unconditional return to Play.
- **The overview clamp fades instead of ellipsizing (§4.1).** Drawn only when the text actually
  overflows; the semantics tree keeps the full string. Single-line metadata keeps its ellipsis
  deliberately.
- **The About heading moves above its panel (§11.4.1)** — aligned with the other section
  headings and back in TalkBack's heading navigation, which the panel's cleared semantics had
  been erasing.
- **§3.2 licenses `White @ 0.85` and `@ 0.75`** as the secondary and tertiary text tiers over
  media, with the measured 12.6:1 floor — vocabulary rows, not a contrast concession.
- **`overMedia` is licensed by a decoded backdrop (§11.4.1)**, not a non-null URL, and the
  branch gains its first instrumented coverage via `coil-test` (approved test-only dependency)
  serving real decoded and failing images — the fixtures' null-URL convention stands everywhere
  else.
- **Small alignments**: the rating badge takes the chips' vertical padding on the metadata row;
  Key Crew shares the overview's 620dp prose measure; the always-Loaded cast rail stops passing
  state parameters that can never render.
- **Mutations preserve every remote press (§11.4.1).** Watched and Like now have independent FIFO
  write queues, per-movie confirmed state and read epochs, and failure reconciliation. Unknown Like
  is disabled because its endpoint is a toggle; Watched remains available because its PUT carries
  the desired value. A persistent `IglooNotice` follows a late failure back to the shell, and a
  drained successful Watched queue refreshes only Home's Continue Watching rail.
- **Follow-up pass on the above.** The read epoch guards only the toggle a write owns, so a press
  landing mid-read no longer costs the resume strip its position. Toggle state is retired once the
  screen moves on instead of accumulating for the session. The failure notice renders in one place
  at a time. Watched carries the row's right-edge pin, since a disabled Like leaves the focus tree.

**2026-08-15 — The movie detail screen lands, and the cards it opens stop being inert.**

§11.4's one-paragraph Detail spec is now built, and §11.4.1 records it as a recipe. Supplying
`onMovieSelected` is all it took to give every poster card and the home hero their `Role.Button`
and "Open …" action — they had been focusable-but-silent by design, waiting for this screen.

- **The overlay is the §9.3 dialog at screen scale.** In-tree above the shell, opaque, with the
  shell left composed underneath so its rails keep scroll and focus memory, hidden from TalkBack
  while it is up, and its Back gated explicitly rather than by registration order. Back restores
  focus to the card that led away through `IglooMediaRail`'s new `returnRequester`, which rides
  the anchor the rail already keeps in every state.
- **Every focus edge is pinned.** The screen's vertical chain is hand-wired end to end and its
  outward edges are `FocusRequester.Cancel`. A composed-but-invisible shell is exactly the case
  where spatial focus search will find a card nobody can see. The About block is focusable for
  the same family of reason as §10's empty rail: content below the last rail that a d-pad cannot
  scroll to is content that does not exist.
- **The focused element must not move.** §7.2 said to give it stagger index 0; inside a scroll
  container that is not enough. The 12dp rise moves the focused button's visual bounds while the
  container brings it into view, and the column stays parked 12dp down with the hero in the
  overscan margin. The header now carries no stagger at all.
- **§3.2 gains a chip-and-control ground** — `Black @ 0.45` under `White @ 0.25`, held through
  focus — because a `Ghost` button's transparent ground and its theme-tracking focus fill are
  licensed only on a token canvas, and the detail hero's actions sit on a backdrop.
- **Promotions to §9.1**: `RatingBadge` (second consumer, as its 2026-08-13 note anticipated),
  `IglooIconButton`, `MediaFormatting`, plus `IglooButton`'s icon slot and toggle semantics.
  Five icons authored: `Play`, `Check`, `Heart`, `HeartFilled`, `MoreVertical`, `Person`.
- **A drift trap worth naming**: the technical-details payload sends `chapters[].movie_id` as a
  plain number where `openapi.json` promises a `SqlNullInt64` object, and the mismatch failed the
  *whole* payload — silently, because media badges are a degrade-gracefully section. The field is
  unused, so it is now simply unmapped, which tolerates either shape. Verify decoding against a
  live response, not the spec, before trusting a screen that hides its own failures.

**2026-08-14 — Uploaded avatars actually render.**

§11.1.1's avatar rule mandated behaviour that rejected every avatar the server produces. Two
faults, and either one alone kept the initials fallback on screen.

- **The wire shape was wrong, and it broke sign-in outright.** `AuthUser.avatar` was modelled as
  a Go `sql.NullString` object on the strength of a backend function that in fact unwraps the
  value before writing it. The field is a plain string or `null`, exactly as `openapi.json`
  says. Users *without* an avatar decoded fine — `explicitNulls = false` covered the null — so
  the fault stayed invisible until an account had one, and then the whole authenticated session
  failed to decode. A contract-shaped fixture now pins the string case.
- **Relative paths are resolved, not discarded.** Uploads are stored as
  `/api/static/avatars/…`, which the old absolute-URL-only guard dropped. `avatarImageUrl`
  now prepends the server origin — the web client's same-origin helper returns the path
  unchanged, which is exactly what the TV app must not do — and that is also what makes the
  loader's origin scope recognise the URL and attach the bearer `/api/static` requires.
  `PUT /users/avatar` still accepts an arbitrary absolute URL, so both shapes pass.
- **The composable stopped owning URL construction.** `IglooAvatar` takes a resolved URL; its
  absolute-URL check is now a backstop against an unresolved path reaching Coil, not the rule.
  `AppAuthState.Authenticated` gained its `ServerAddress` — it was the only auth state without
  one — so the rail can resolve without reaching for a provider from composition.

**2026-08-13 — Home gets its fourth rail: Now Playing in Theaters.**

§11.3's rail order reaches the theaters entry (`GET /api/tmdb/movies/in-theaters`), rendered
last because it is TMDB content the user cannot play. §11.3.2 records the rail, its distinctive
card, and the sort exception; §3.2 gains the theater-card scrim and rating-badge literals.

- **A new card shape, feature-local.** `InTheatersCard` overlays title and year on the poster
  and badges the critic score — structurally unlike `IglooPosterCard`'s text-below layout, so it
  is its own composable in `feature/home/` rather than an overlay slot bolted onto the shared
  card. Promote it to §9 only if a second screen needs it. The shared skeleton's below-poster
  text stubs don't match this card's geometry — accepted; the poster cell itself matches.
- **"Server order is the contract" is now scoped to the library rails.** The TMDB route's order
  carries no meaning, so the client sorts by `release_date` descending, matching the web client.
- **The rating badge's tiers ride on `aurora`**, licensed over media because §3.1 pins it
  theme-invariant; the < 5 tier swaps the web's theme-tracking `muted` for the equivalent black
  literal. A zero score is TMDB's "unrated" and drops the badge entirely.
- `IglooIcons` gains `Star` for the badge glyph.

**2026-08-11 — Home gets its third rail: Recently Added Albums.**

§11.3's rail order reaches the albums entry (`GET /api/music/albums/latest`), and §11.3.2 now
records all three rails' endpoints and empty copy in one table instead of leaving them to the code.

- **§8.2 gains `albumAspect` (1:1).** The square-album sentence had no token behind it, so both
  the card and its skeleton would have carried a bare `1f`. `IglooPosterCard` now takes the
  aspect and the fallback glyph as parameters instead of hard-coding the 2:3 poster and the film
  icon, and `IglooMediaRail` takes the same aspect for its skeleton — §10's grid-matching rule is
  only true if the placeholder is the shape of the card that replaces it.
- **Album covers bypass the image helpers.** The scanner stores an absolute Spotify URL or
  nothing; there is no music image proxy, so the cover is used verbatim and the loader's
  existing origin check is what keeps the bearer token off a foreign CDN.
- Album cards follow the poster cards' precedent: focusable, announcing title and musician, with
  **no** action until album detail lands.

**2026-08-10 — Home gets its hero.**

§11.3.1 lands: a featured banner above the rails showing the most recently added movie, enriched
via `GET /api/movies/details/{id}` chained inside the Recently Added load. A pane-width rounded
card (not full-bleed), `w1280` backdrop under the §3.2 bottom + side gradients, one focus target
with `scaleOnFocus = false`, no action until the details screen lands.

- **The pane's entry anchor moves from the Continue Watching rail to the hero** whenever the hero
  is visible; the rail keeps it when the hero is hidden. Test tag: `home_hero`.
- **The hero hides rather than erroring**: empty library, failed latest fetch, or failed details
  fetch all hide it — the rail's error card owns Retry for this data. Background-refresh failures
  keep a loaded hero (the rails' rule).
- §3.2 gains the hero side gradient literal; §4.1 scopes "never truncate" to the display-on-canvas
  hero and records the Home hero's clamps (title 2 / overview 3).

**2026-08-10 — Home gets its rails: Continue Watching over Recently Added Movies.**

The §11.3 layout starts landing: the Home destination drops the placeholder hero and renders a
scrollable column of rails — Continue Watching first (`GET /api/movies/continue-watching`),
Recently Added Movies below it (`GET /api/movies/latest`). Other destinations keep the
placeholder pane until their screens land. New primitives in §9.1: `IglooMediaRail`,
`IglooPosterCard`, `IglooEmpty` (minimal only).

- **§10's shimmer sentence lost to §7.2's loop rule.** A shimmer is a loop; only §11.1.0's
  ambient drift may loop. Skeletons are static blocks at full motion too, not only under
  reduced motion. Revisit only by authoring an explicit carve-out in §7.2.
- **A card announces an action only when one exists.** `IglooPosterCard` carries `Role.Button`
  and "Open <title>" only when it is given something to open; until the details screen lands
  the home cards are focusable and announce title, year, and progress with **no** action.
  Announcing an action nothing implements is worse for a screen reader than announcing none —
  it stays a focus target because §8.1's focus model needs every card to be a landing site.
- **Rails refresh on foreground, not once per session.** A TV sits on this screen for days, so
  the host's start effect re-reads every rail alongside the session revalidation. A background
  refresh **keeps the loaded rail on screen** until its replacement arrives: the rail re-anchors
  focus on every state swap, so dropping back to the skeleton would flash and pull focus off
  the card the user is sitting on. Only Retry, which has nothing to preserve, re-enters loading.
- **§10's error recipe is form-shaped; rails needed it rail-shaped.** Rails fail independently,
  so `IglooInlineError` gained a live-region mode: `Assertive` still for a form, where the error
  is the only thing that changed, but `Polite` in a rail so two failed rails queue instead of
  cutting each other (and the §11.3 heading) off. The rail also bounds the card to roughly
  three cards' width, so a failure reads as *this rail's* rather than the whole pane's.
- **Loading and empty states are focusable anchors.** The content pane must always own exactly
  one focus target or the shell's initial focus, d-pad-right from the spine, and the Back model
  all break. So the skeleton's first cell and the empty state's frame take the §6.1 treatment
  and carry the pane's entry requester; the skeleton cell announces the load politely. When
  content replaces a focused skeleton, the rail re-requests focus onto the entry card — a
  disposed focused node otherwise drops focus on the floor.
- **§6.3's per-rail restore, implemented:** the remembered card is per rail, hoisted above the
  destination switch, and saved across process death. A rail rebuilt on re-entry is *created
  scrolled to* the remembered card, because a focus requester can only land on a composed node.
  The memory is snapshot state **on purpose**: the recomposition each focus move triggers is
  what walks the pane's entry requester onto the remembered card, which is how spine re-entry
  lands there without the rail being rebuilt. Making it non-observable to save that
  recomposition breaks restore outright — it was tried and reverted.
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
