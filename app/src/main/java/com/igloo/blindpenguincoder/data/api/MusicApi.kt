package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse

class MusicApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /**
     * Newest additions to the music library. The route takes no parameters at all — the backend
     * owns both the order and the cap of 12, so there is nothing to page or sort from here.
     */
    suspend fun latestAlbums(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/albums/latest")

    /** One page of every album, alphabetical; `per_page` is clamped to [MAX_PER_PAGE] server-side. */
    suspend fun albums(page: Long, perPage: Long): HttpResponse =
        pagedList("${serverUrl.require().apiBaseUrl}/music/albums", page, perPage)

    suspend fun albumDetails(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/albums/details/$id")

    /** One page of every musician, alphabetical by the server's sort name. */
    suspend fun musicians(page: Long, perPage: Long): HttpResponse =
        pagedList("${serverUrl.require().apiBaseUrl}/music/musicians", page, perPage)

    suspend fun musicianDetails(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/musicians/$id")

    /**
     * The library track list, ordered by letter bucket then title. Unlike the album and musician
     * lists this route pages by `limit`/`offset`; `limit` is clamped to 100 server-side.
     */
    suspend fun tracks(limit: Long, offset: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/tracks") {
            parameter("limit", limit)
            parameter("offset", offset)
        }

    /**
     * A random sample of the library. [exclude] is sent comma-joined; the server honours only the
     * first [SHUFFLE_MAX_EXCLUDE] ids, so the caller decides which ids are worth the slots.
     */
    suspend fun shuffleTracks(limit: Long, exclude: List<Long>): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/tracks/shuffle") {
            parameter("limit", limit)
            if (exclude.isNotEmpty()) parameter("exclude", exclude.joinToString(","))
        }

    /** Every track id the current user has liked; the bulk seed for like state. */
    suspend fun likedTrackIds(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/tracks/liked-ids")

    /** Server-side toggle — no request body; the response carries the new state. */
    suspend fun toggleTrackLike(id: Long): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/music/tracks/$id/like")

    /** Library-wide counts of albums, tracks and musicians. */
    suspend fun musicStats(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/stats")

    /**
     * Absolute URL of a track's direct stream. Media3 fetches it on its own HTTP stack
     * (Range/206), not through Ktor, so this is a string rather than a request.
     */
    fun trackStreamUrl(id: Long): String =
        "${serverUrl.require().apiBaseUrl}/music/tracks/$id/stream"

    /** The album and musician lists speak the same query dialect; spell it once. */
    private suspend fun pagedList(url: String, page: Long, perPage: Long): HttpResponse =
        client.get(url) {
            parameter("page", page)
            parameter("per_page", perPage)
        }

    companion object {
        /** The album and musician lists clamp a larger `per_page` to this. */
        const val MAX_PER_PAGE = 48L

        /** Shuffle clamps `limit` to 200 and ignores `exclude` ids past this count. */
        const val SHUFFLE_MAX_EXCLUDE = 200
    }
}
