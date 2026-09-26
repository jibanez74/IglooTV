package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString

/** One row of a paged listing before its poster path is built into a URL. */
data class LibraryRow(
    val id: Long,
    val title: String,
    val posterPath: SqlNullString,
    val year: SqlNullInt64,
)

/** One page of a paged listing, with the counts the grid's tail is driven by. */
data class LibraryPage(
    val rows: List<LibraryRow>,
    val total: Long,
    val totalPages: Long,
)

/**
 * The endpoints one library index pages, bound to a repository by a factory per kind
 * (`movieLibrarySource`, `showLibrarySource`) so the view model never sees a wire type. The
 * three list fetchers take the same page-numbered, direction-sorted query.
 */
class LibrarySource(
    val kind: LibraryKind,
    val all: suspend (page: Long, perPage: Long, sort: SortOrder) -> ApiResult<LibraryPage>,
    val genre: suspend (genreId: Long, page: Long, perPage: Long, sort: SortOrder) -> ApiResult<LibraryPage>,
    /** Null for a library the backend keeps no likes for; the Liked tab is then never offered. */
    val liked: (suspend (page: Long, perPage: Long, sort: SortOrder) -> ApiResult<LibraryPage>)?,
    /** The library-wide count behind the All view's header. */
    val total: suspend () -> ApiResult<Long>,
    val genres: suspend () -> ApiResult<List<LibraryGenre>>,
) {
    /** All · Genres, plus Liked only where [liked] can serve it. */
    val tabs: List<LibraryTab> =
        listOfNotNull(LibraryTab.All, LibraryTab.Genres, LibraryTab.Liked.takeIf { liked != null })
}
