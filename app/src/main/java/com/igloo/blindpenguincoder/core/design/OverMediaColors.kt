package com.igloo.blindpenguincoder.core.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * The section 3.2 over-media literals. They are deliberately not theme tokens — a backdrop or a
 * video looks the same in light and dark — and they are licensed only by media actually behind
 * them. Named so every surface over media speaks the same vocabulary.
 */

/** The ground of a chip or control over media, held through focus. */
internal val OVER_MEDIA_CONTROL_FILL = Color.Black.copy(alpha = 0.45f)

/** The hairline around a chip or control over media. */
internal val OVER_MEDIA_HAIRLINE = Color.White.copy(alpha = 0.25f)

/** Secondary text over media. */
internal val OVER_MEDIA_SECONDARY = Color.White.copy(alpha = 0.85f)

/** Tertiary text over media. */
internal val OVER_MEDIA_TERTIARY = Color.White.copy(alpha = 0.75f)

/** The ground of a thin progress track over media — the poster card's, the seek bar's. */
internal val OVER_MEDIA_TRACK = Color.Black.copy(alpha = 0.40f)

/**
 * A removal's label over media (`OutlineDestructive`, section 9.1). Fixed, because the ground
 * under it is the section 3.2 black in both themes, and one step lighter than the dark palette's
 * `destructive`: that one reaches only 3.8:1 where the control ground sits over the far stop of
 * a hero's side scrim on white art, and this one 5.5:1.
 */
internal val OVER_MEDIA_DESTRUCTIVE = Color(0xFFFCA5A5)

/**
 * The tile under over-media chrome whose media is missing: the same navy in both themes, like
 * the app icon's canvas, so a scrim and white text read on it exactly as they do on a poster. A
 * `muted` box is near-white in light and turns them into a grey smear. Only where over-media
 * text sits on the box — a placeholder with token text beside it keeps `muted`.
 */
internal val MEDIA_FALLBACK_TILE = Color(0xFF0F1A2E)

/** The glyph on [MEDIA_FALLBACK_TILE]. */
internal val MEDIA_FALLBACK_GLYPH = Color(0xFF8094AE)

/**
 * The side scrim a full-bleed hero carries its text column on: heavy at the start edge, where
 * prose sits over busy art, and gone by the far edge so the art still reads. The Home hero and
 * the details heroes share it so the two surfaces read alike.
 */
internal fun overMediaSideScrim(): Brush = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.80f),
    0.65f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0f),
)
