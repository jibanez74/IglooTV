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

/** Secondary text over media. */
internal val OVER_MEDIA_SECONDARY = Color.White.copy(alpha = 0.85f)

/** Tertiary text over media. */
internal val OVER_MEDIA_TERTIARY = Color.White.copy(alpha = 0.75f)

/** The ground of a thin progress track over media — the poster card's, the seek bar's. */
internal val OVER_MEDIA_TRACK = Color.Black.copy(alpha = 0.40f)

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
