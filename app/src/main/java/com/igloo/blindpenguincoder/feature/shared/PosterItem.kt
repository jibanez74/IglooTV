package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl

/** A library title ready to render: nullable wire fields resolved, poster path built into a URL. */
data class PosterItem(
    val id: Long,
    val title: String,
    val year: Long?,
    val posterUrl: String?,
)

/**
 * The one mapping every library listing shares: Home's rails and the Movies and TV Shows grids
 * render the same poster card from the same wire fields.
 */
fun posterItem(
    id: Long,
    title: String,
    posterPath: SqlNullString,
    year: SqlNullInt64,
    apiBaseUrl: String,
): PosterItem = PosterItem(
    id = id,
    title = title,
    year = year.orNull(),
    // w500 for a poster-sized card: crisp at TV densities, and the same cache entry the detail
    // screen wants when the card is opened.
    posterUrl = tmdbImageUrl(
        apiBaseUrl = apiBaseUrl,
        size = TmdbImageSize.W500,
        path = posterPath.orNull(),
    ),
)
