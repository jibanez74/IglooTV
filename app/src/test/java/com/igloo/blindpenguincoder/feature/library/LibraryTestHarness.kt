package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy

/** One mocked route's answer. */
internal typealias LibraryRoute =
    suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

internal const val ERROR_BODY = """{"error":true,"message":"nope"}"""

/** Records what a library pane actually asked the backend for, so the paging can be asserted. */
internal class RoutedHttp {
    val paths = mutableListOf<String>()
    val libraryPages = mutableListOf<String>()
    val likedPages = mutableListOf<String>()

    /** `"genreId:page"` per request, so the path and the cursor assert together. */
    val genrePages = mutableListOf<String>()
    val perPages = mutableListOf<String>()
    val sorts = mutableListOf<String>()
    lateinit var test: TestHttp
}

/**
 * Routes one library's endpoints under `/api/[resource]` — stats, genres, the library, one
 * genre's list and, where [liked] is given, the liked list; any other request fails the test.
 *
 * The mock engine is put on the caller's test scheduler so a response and the view model share
 * one clock; on its production default a real thread hop escapes `runTest` and every assertion
 * would read state that has not been written yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.routedLibraryHttp(
    resource: String,
    stats: LibraryRoute,
    genres: LibraryRoute,
    library: LibraryRoute,
    genreList: LibraryRoute,
    liked: LibraryRoute?,
): RoutedHttp {
    val routed = RoutedHttp()
    val genreListPath = Regex("/api/$resource/genres/(\\d+)/$resource")
    fun recordListParams(request: HttpRequestData) {
        routed.perPages += request.url.parameters["per_page"].orEmpty()
        routed.sorts += request.url.parameters["sort"].orEmpty()
    }
    routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        val path = request.url.encodedPath
        routed.paths += path
        val genreId = genreListPath.matchEntire(path)?.groupValues?.get(1)
        when {
            path == "/api/$resource/stats" -> stats(request)
            path == "/api/$resource/genres" -> genres(request)
            path == "/api/$resource/library" -> {
                routed.libraryPages += request.page()
                recordListParams(request)
                library(request)
            }
            liked != null && path == "/api/$resource/liked" -> {
                routed.likedPages += request.page()
                recordListParams(request)
                liked(request)
            }
            genreId != null -> {
                routed.genrePages += "$genreId:${request.page()}"
                recordListParams(request)
                genreList(request)
            }
            else -> error("unexpected request to $path")
        }
    }
    return routed
}

/**
 * A tab taking focus and being stayed on: the switch, plus the debounce it waits out. Tests
 * about the debounce itself call `selectTab` and drive the clock themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.landOn(model: LibraryViewModel, tab: LibraryTab) {
    model.selectTab(tab)
    // Past the debounce and no further: `advanceUntilIdle` here would run the virtual clock
    // into the client's request timeout in the tests that deliberately hold a response open.
    advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
}

internal fun LibraryUiState.gridIds(): List<Long> =
    (grid as IglooRailState.Loaded).items.map { it.id }

internal fun HttpRequestData.page(): String = url.parameters["page"].orEmpty()
