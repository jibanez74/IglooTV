package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.Serializable

/*
 * Rows the movie and show details routes share. The spec names the genre and company rows per
 * route (`MovieGenre`/`ShowGenre`, `MovieProductionCompany`/`ShowProductionCompany`) but they are
 * one shape, and `ExtraVideo` is already one schema; OpenApiContractTest checks each against the
 * route that decodes it, so one model per shape is enough.
 */

/** A genre on a details page; its `id` is left to `ignoreUnknownKeys`. */
@Serializable
data class Genre(
    val tag: String,
)

/** A production company on a details page; its `id` is left to `ignoreUnknownKeys`. */
@Serializable
data class ProductionCompany(
    val name: String,
)

/** A trailer, featurette or clip attached to a movie or show. */
@Serializable
data class ExtraVideo(
    val id: Long,
    val title: String,
    val key: String,
    val type: String,
    val site: String,
)
