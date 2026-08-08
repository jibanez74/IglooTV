# Focus treatment gap — a Primary button has no visible focus indicator

**Status:** open, not fixed. Found during on-device verification of the launch-splash branch;
entirely pre-existing and unrelated to it, so it was deliberately left for its own branch.

**Severity:** high for a TV app. Focus is the only cursor — §6 opens with "Focus is the whole
interaction model." Two of the three affected screens are in the first-run setup flow, so this is
in front of a new user before anything else.

---

## The defect

`IglooDarkColors` gives `primary` and `ring` the same value:

```kotlin
// core/design/IglooColors.kt
val IglooDarkColors = IglooColors(
    primary = Color(0xFF38BDF8),
    border  = Color(0xFF2A3C57),
    ring    = Color(0xFF38BDF8),   // identical to primary
)
```

`IglooButton` fills a `Primary` button with `colors.primary`, then draws the focus border in
`colors.ring` (`core/ui/IglooButton.kt:58-59` → `core/ui/FocusRing.kt`). Same colour, 1:1
contrast: **the ring is invisible on the variant that most needs it.**

It is worse than a no-op. At rest the border is `colors.border` — a slate that reads clearly
against the glacier fill. On focus it becomes glacier. So *gaining* focus removes the only visible
outline and the button looks less delineated focused than at rest.

Light theme differs (`primary = 0xFF0369A1`, `ring = 0xFF0EA5E9`) so a faint ring survives there,
but dark is the default per `CLAUDE.md`.

## It is not only the colour collision

§6.1 specifies five properties for "the one focus treatment". Only two are implemented anywhere:

| §6.1 property | Token | Implemented? |
|---|---|---|
| Ring width (focused) 3dp `colors.ring` | `IglooFocus.ringWidth` | Yes — but invisible on Primary |
| Border at rest 1dp `colors.border` | `IglooFocus.restWidth` | Yes |
| Fill (focused) `card @ 0.72` | — | Ghost variant only; Primary's fill is fixed |
| Scale 1.05× | `IglooFocus.scale` | **No — token never read** |
| Glow `ring @ 0.20`, 16dp elevation | `IglooFocus.glowAlpha`, `glowElevation` | **No — tokens never read** |

`grep -rn "\.shadow(\|spotColor" app/src/main/java` returns nothing, and `IglooFocus.scale` /
`glowAlpha` / `glowElevation` have no consumers. So on a Primary button **every** focus signal the
design system promises is either absent or invisible. Had the glow been implemented, the colour
collision would have been survivable.

## Blast radius

Three `Primary` call sites (the default variant; the other seven pass `Ghost` explicitly):

| Screen | Button | Other focusables on screen? |
|---|---|---|
| `feature/auth/ServerSetupScreen.kt:70` | "Connect" | Yes — address field. **Bites.** |
| `feature/auth/LoginScreen.kt:86` | "Sign in" | Yes — email, password, ghost buttons. **Bites.** |
| `feature/auth/WelcomeScreen.kt:98` | "Get started" | No — sole focusable, so harmless there |

Other `focusRing` users (nav spine, profile tiles, PIN keypad, text fields, hero card) draw the
ring over `card`/`background` surfaces and are unaffected. The bug is specific to a ring drawn on
top of a `primary` fill.

Why this was not caught: `WelcomeGateTest` and friends assert `assertIsFocused()`, which reads
Compose semantics — semantically correct and visually invisible are indistinguishable to those
tests. Nothing asserts a focus *pixel* difference.

## Fix options

**Recommended: implement the glow, and keep the ring glacier.** Add `shadow(elevation, shape,
spotColor = colors.ring.copy(alpha = glowAlpha))` to `focusRing` so §6.1's glow row becomes real.
This makes focus legible on any fill without touching the palette, closes three dead tokens at
once, and is the treatment §6.1 already specifies — so it needs no new design decision. The
glacier-on-glacier ring stays invisible on Primary, but the glow around the silhouette carries the
signal. `spotColor` needs `minSdk 28`, which §11.1.0 already notes is satisfied.

Alternatives, if the glow proves too subtle on a real panel:

- **Inset the ring.** Draw the focused border inside a `primaryForeground` gap so there is always a
  contrasting separation. Costs a padding change on every focusable.
- **Give Primary a focused fill.** Lighten or darken the fill on focus the way `Ghost` already
  does. Cheapest, but adds a sixth property to a treatment §6.1 calls "the one focus treatment".
- **Re-token `ring`.** Changing `ring` away from `0xFF38BDF8` fixes Primary and changes focus
  everywhere else. Not recommended — §6 wants focus glacier throughout, and this trades a bug on
  three buttons for a palette change across every screen.

Whichever is chosen, §6.1 and this file should be updated together, and `IglooFocus` should not
keep carrying tokens nothing reads.

## Verification for that branch

1. Add a test that fails today: render a `Primary` `IglooButton` focused and unfocused, and assert
   the rendered pixels differ. `captureToImage()` on the button node, compare bitmaps. This is the
   guard that was missing.
2. `./gradlew build` and the full instrumented suite — `focusRing` is used on every screen, so this
   is a wide change even if the diff is small. Note `QuickConnectGateTest` has two pre-existing
   failures unrelated to any of this.
3. On device (Shield is API 30, the emulator `Google_TV_API_34_1080p` is the known-good target per
   `.claude/skills/verify`): D-pad through server setup and the login form and confirm focus is
   obvious at 10 feet, in both dark and light theme, at `UiScale.Compact` and `Large`.
4. Reduced motion: if a focused scale is added, it must go through `iglooTween` per §7.2.
