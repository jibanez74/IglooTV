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
- Range is bounded by the 960×540dp viewport: the 236dp nav spine becomes 207 / 236 / 271dp,
  leaving ≥689dp of content pane. Beyond ~1.2 the shell stops fitting.

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
| type sizes and line heights | `focus.ringWidth` / `restWidth` — hairlines must stay hairlines |
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

15 semantic slots, implemented in `core/design/IglooColors.kt`. Tokens are paired (surface +
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

Notes:

- `aurora` / `auroraForeground` are **identical in both themes** — intentional.
- `ring` is the single focus color across the entire app. Do not introduce a second.
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
| `0.40f` | `primary` | Disabled control |
| `0.60f` | `mutedForeground` | Placeholder text |
| `0.10f` / `0.25f` | `destructive` | Inline error card fill / border |
| `0.16f` / `0.48f` | `aurora` | Badge fill / border |
| `0.20f` | `ring` | Focus glow (§6) |

**Focus vs selected is a real distinction**: `card @ 0.72` means "the remote is here right now";
`primary @ 0.18` means "this is the active destination". Both can be true at once, and the nav
spine renders them together.

### 3.2 Color over media

White-on-darkened-poster is a legitimate pattern that does **not** get tokens, because it must
not track the theme — a poster looks the same in light and dark mode. Use literals:

- Poster dim overlay on focus: `Color.Black.copy(alpha = 0.30f)` (video) / `0.40f` (album art)
- Title gradient over poster: `Brush.verticalGradient` to `Color.Black.copy(alpha = 0.90f)`
- Text over media: `Color.White`, with a shadow for legibility

---

## 4. Typography

Six styles, implemented in `core/design/IglooTypography.kt`. Family is the system sans
(`FontFamily.SansSerif`) except the pairing code, which is monospace.

**Standard** column is authored; Compact and Large are derived by multiplier and shown for
reference.

| Style | Size / line height | Weight | Compact | Large | Use |
|---|---|---|---|---|---|
| `displayCode` | 64 / 76sp, +10sp tracking, mono | Bold | 56 / 67 | 74 / 87 | Quick-connect pairing code only |
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
  tokens; adding a `TextStyle` in a feature package is exactly that. Two are anticipated and
  deliberately not added yet: `caption` (metadata chips) and a hero `display`. Add them when a
  feature actually needs one.
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
| Fill (focused) | `colors.card @ 0.72` | — |
| Scale | 1.05× | No |
| Glow | `colors.ring @ 0.20`, 16dp elevation | No |

Ring and rest widths are unscaled on purpose: a 2.6dp ring at Compact reads mushy, and a
hairline must stay a hairline at every scale.

Glow is applied as `Modifier.shadow(elevation, shape, spotColor = colors.ring.copy(alpha = 0.20f))`
— `spotColor` is available at `minSdk 28`.

### 6.2 Converting pointer patterns

| Pointer pattern | TV equivalent |
|---|---|
| `hover` overlay + centered Play reveal | Track `Modifier.onFocusChanged`; show dim overlay + Play affordance when the card or its focus group is focused |
| `hover` poster zoom | `animateFloatAsState` to 1.05×, driven by focus, via `iglooTween` |
| `hover` lift + colored glow | Focused elevation + glacier glow (§6.1) |
| Pointer focus ring | The one focus treatment above — no separate style |
| Action hidden until hover | **Never gate an action behind focus alone.** Show it always, or reveal on *row* focus so it is reachable before it is needed |
| Idle-hide chrome on pointer move | Show chrome on any d-pad or media-key event; auto-hide after an idle timeout |

### 6.3 Focus behavior

- **Restoration**: returning to a rail or grid restores the last-focused item, not the first.
  Back navigation restores focus to the element that led away.
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

Easings — two, deliberately:

```kotlin
standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)   // entering, settling
exit     = CubicBezierEasing(0.4f, 0f, 1f, 1f)   // leaving
```

### 7.1 The reduced-motion contract

```kotlin
@Composable
fun <T> iglooTween(durationMillis: Int, easing: Easing = IglooEasing.standard): FiniteAnimationSpec<T> =
    if (LocalIglooReducedMotion.current) snap() else tween(durationMillis, easing = easing)
```

**Rule: no bare `tween(...)` or raw duration literal in feature code. Always `iglooTween`.**
This is grep-enforceable and is the reason the helper exists rather than each call site checking
the flag and forgetting.

Reduced motion reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` and observes it live via a
`ContentObserver`, so toggling the system setting takes effect without an app restart.

### 7.2 What may animate

Focus transitions, overlay reveals, section enters, progress fills. **Not**: anything that moves
focus itself, anything that delays a user-initiated navigation, or anything looping in the
periphery while the user is trying to read.

---

## 8. Layout & navigation shell

### 8.1 The shell

A persistent **left nav spine** and a content pane, inside the safe area.

| Token | Standard |
|---|---|
| `navSpineWidth` | 236dp |
| `safeArea` | 48dp × 27dp *(unscaled)* |

At Standard on the 960dp reference viewport: 960 − 96 (safe area) − 236 (spine) = **628dp of
content pane**. That is the budget. Everything in §8.2 is sized against it.

The spine is the primary vertical d-pad target. It is always present — there is no drawer, no
hamburger, no overlay mode.

### 8.2 Media geometry

| Token | Standard | Notes |
|---|---|---|
| `posterWidth` | 148dp ⚑ | 628dp of pane ⇒ 4 posters + a peek, which cues scrollability |
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
| `IglooButton` | `heightIn(min = sizes.controlHeight)`, radius `lg`, focus per §6.1 |
| `IglooTextField` | `heightIn(min = sizes.fieldHeight)`, radius `lg`, placeholder at `mutedForeground @ 0.60` |
| `IglooInlineError` | `destructive @ 0.10` fill, `@ 0.25` border, radius `lg` |
| `FocusRing` | The single focus border; 3dp focused / 1dp at rest |
| `IglooQrCode` | Pairing-code QR |

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

---

## 10. UI states

Build these **once** as shared composables. Three states, one recipe each.

- **`IglooLoading`** — grid-matched skeletons. A movie grid skeleton renders the same column
  count and the same card aspect as the real grid, so **focus position does not jump** when
  content arrives. Shimmer goes through `iglooTween`; at reduced motion it is a static block.
  Route-level loading uses one app-wide pending screen.
- **`IglooEmpty`** — two variants. *Minimal*: large faded icon + one line ("No movies found in
  your library."). *Rich CTA*: icon orb, heading, description, and a focusable primary action.
  Use the rich variant only when there is a real action to offer.
- **`IglooError`** — inline card, `destructive @ 0.10` fill / `@ 0.25` border, a message, and a
  **focusable Retry** that re-runs the query. Retry must be reachable by d-pad without leaving
  the screen.

All three announce themselves to TalkBack when they replace content (§12).

Mutation/action failures do not render inline — they surface as a transient message and must
also be announced.

---

## 11. Screens & UX

Feature surfaces, in TV terms. Web routes are cited only as a reference for content and field
names, not for layout.

### 11.1 Auth boundary

Two top-level states: **unauthenticated** (full-bleed auth canvas, no shell) and
**authenticated** (nav spine + content pane). The auth canvas centers a single card on a
vertical gradient, scrolls internally if it does not fit, and never shows nav chrome.

A device token belongs to exactly one user and the backend has no "switch user" call, so
multiple people on one TV means **one stored token per person**. Every unauthenticated screen
below is a step toward getting or choosing one.

#### 11.1.1 Profile picker

"Who's watching?" on the auth canvas at 840dp — the width §11.1.3 already uses. A single
horizontal row of circular tiles, most recently used first, then an **Add profile** tile that
is always visible and never focus-gated (§6.2). Below the row, a ghost **Change server** row.

Tiles render from stored profiles alone, so the picker appears instantly and works with the
server unreachable. Cap the row at **6 profiles** so it never scrolls at any `UiScale`; past
that, "Add profile" explains itself instead of starting another pairing.

- Avatar: 96dp circle. A remote avatar is fetched only when the stored value is an absolute
  `http(s)` URL — `openapi.json` does not define how a relative avatar path resolves — and
  falls back to the initial on `primary`.
- Focus ring: the one treatment (§6.1) at `radius.pill`, which on a square box reads as the
  circle it wraps. Focused tile fills `card @ 0.72` and scales to 1.06 over `MICRO_MS`.
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
- Back returns to the picker with the profile still paired.

#### 11.1.3 Sign in

Text entry on a remote is painful. Quick-connect pairing is the primary path; email/password is
the fallback.

When this screen is reached by adding a user rather than by first-time setup, the "Change
server" slot becomes **Back to profiles** — changing the server wipes every stored profile, so
offering it mid-add is a trap.

### 11.2 Navigation spine

Six destinations, icon + label: **Home**, **Movies**, **TV Shows**, **Music**, **Photos**,
**Settings**. Brand tile + wordmark at the top, the active profile's name and two account
actions at the bottom: **Switch profile** above **Sign out**.

The two are deliberately separate. Switch profile returns to the picker with the token intact;
sign out revokes the device token server-side and drops the profile from this TV. One control
doing both would either strand a credential on a shared TV or force a re-pair to hand over the
remote.

Active destination uses `primary @ 0.18` fill plus a `sidebarPrimary` icon; the focused row uses
`card @ 0.72`. Both render simultaneously when the user is focused on the active destination
(§3.1).

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
| Type scale | Tailwind utility literals | Six authored styles (§4) |

Shared: the 15 color values, the three motion durations (150/200/300ms), the 1.05× focus scale,
and the 0.20 focus-glow alpha.

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
| Durations, easings, `iglooTween` (§7) | `core/design/IglooMotion.kt` |
| `IglooTheme` accessors, `iglooSafeArea()`, `Dp.scaled()` | `core/design/IglooTheme.kt` |
| `UiScale` persistence (§2.3) | `core/storage/UiPreferencesStore.kt` |

Unit tests in `app/src/test/java/.../core/design/` assert that the Standard values in §4 and §5
match the code exactly. **If you change a number in this document and the tests still pass, you
forgot to change the code.**

---

## Changelog

**2026-08-03 — Multi-profile auth.**

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
