package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.posterItem
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl

/** One row of a paged listing before its poster path is built into a URL. */
data class LibraryRow(
    val id: Long,
    val title: String,
    val posterPath: SqlNullString,
    val year: SqlNullInt64,
)

/** The row as a poster card; a search result is drawn by the same rule as a library page. */
fun LibraryRow.toPosterItem(apiBaseUrl: String): PosterItem = posterItem(id, title, posterPath, year, apiBaseUrl)

/** One page of a paged listing, with the counts the grid's tail is driven by. */
data class LibraryPage(
    val rows: List<LibraryRow>,
    val total: Long,
    val totalPages: Long,
)

/**
 * One chip of the genre picker, wire-agnostic: the source maps its own count column onto
 * [count].
 */
data class LibraryGenre(
    val id: Long,
    val tag: String,
    val count: Long,
    /** The picture on the genre's tile (section 11.4); null where the server found none. */
    val backdropUrl: String? = null,
)

/**
 * The endpoints one library index pages, bound to a repository by a factory per kind
 * (`movieLibrarySource`, `showLibrarySource`) so the view model never sees a wire type. The
 * three list fetchers take the same page-numbered, direction-sorted query.
 */
class LibrarySource(
    val kind: LibraryKind,
    val all: suspend (page: Long, perPage: Long, sort: SortOrder) -> ApiResult<LibraryPage>,
    val genre: suspend (
        genreId: Long,
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ) -> ApiResult<LibraryPage>,
    /** Null for a library the backend keeps no likes for; the Liked tab is then never offered. */
    val liked: (suspend (page: Long, perPage: Long, sort: SortOrder) -> ApiResult<LibraryPage>)?,
    /** The library-wide count behind the All view's header. */
    val total: suspend () -> ApiResult<Long>,
    /** Takes the API base URL its tiles' backdrops resolve against. */
    val genres: suspend (apiBaseUrl: String) -> ApiResult<List<LibraryGenre>>,
) {
    /** All · Genres, plus Liked only where [liked] can serve it. */
    val tabs: List<LibraryTab> =
        listOfNotNull(LibraryTab.All, LibraryTab.Genres, LibraryTab.Liked.takeIf { liked != null })
}

/**
 * A genre tile's picture. `w500`, as the web's tile takes it: the tile is a fraction of a
 * backdrop's width, and `w1280` would decode sixteen full backdrops for one row.
 */
internal fun genreBackdropUrl(apiBaseUrl: String, backdropPath: SqlNullString): String? =
    tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, backdropPath.orNull())
