package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.api.SearchApi
import com.igloo.blindpenguincoder.data.api.SearchRoute
import com.igloo.blindpenguincoder.data.model.MovieLibraryItem
import com.igloo.blindpenguincoder.data.model.SearchAllData
import com.igloo.blindpenguincoder.data.model.SearchPageData
import com.igloo.blindpenguincoder.data.model.ShowLibraryItem
import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import com.igloo.blindpenguincoder.data.model.SimpleMusician
import com.igloo.blindpenguincoder.data.model.TrackListItem

class SearchRepository(
    private val api: SearchApi,
) {
    suspend fun searchAll(query: String): ApiResult<SearchAllData> =
        envelopeData("search") { api.searchAll(query) }

    suspend fun searchMovies(query: String, page: Long, perPage: Long): ApiResult<SearchPageData<MovieLibraryItem>> =
        envelopeData("movie search") { api.search(SearchRoute.Movies, query, page, perPage) }

    suspend fun searchShows(query: String, page: Long, perPage: Long): ApiResult<SearchPageData<ShowLibraryItem>> =
        envelopeData("show search") { api.search(SearchRoute.Shows, query, page, perPage) }

    suspend fun searchAlbums(query: String, page: Long, perPage: Long): ApiResult<SearchPageData<SimpleAlbum>> =
        envelopeData("album search") { api.search(SearchRoute.Albums, query, page, perPage) }

    suspend fun searchMusicians(query: String, page: Long, perPage: Long): ApiResult<SearchPageData<SimpleMusician>> =
        envelopeData("musician search") { api.search(SearchRoute.Musicians, query, page, perPage) }

    suspend fun searchTracks(query: String, page: Long, perPage: Long): ApiResult<SearchPageData<TrackListItem>> =
        envelopeData("track search") { api.search(SearchRoute.Tracks, query, page, perPage) }
}
