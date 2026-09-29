package com.igloo.blindpenguincoder.core.design

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
