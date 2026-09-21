package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.DeviceIdentity
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.core.network.AuthEventBus
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.network.createServerProbeHttpClient
import com.igloo.blindpenguincoder.core.storage.FakeProfileStore
import com.igloo.blindpenguincoder.core.storage.ProfileVault
import com.igloo.blindpenguincoder.core.storage.StoredProfile
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.api.UserApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher

const val TEST_SERVER = "http://igloo.test:8080/api"

val testDeviceIdentity = DeviceIdentity(
    name = "Shield",
    platform = "android_tv",
    appVersion = "0.1.0",
)

class TestHttp(
    engineDispatcher: CoroutineDispatcher? = null,
    handler: MockRequestHandler,
) {
    val profileStore = FakeProfileStore()
    val credentials = BearerTokenProvider()
    val authEvents = AuthEventBus()

    /** Advanced by tests that care about last-used ordering. */
    var clockMillis = 1_000L

    val profiles = ProfileRepository(profileStore, credentials, clock = { clockMillis })
    val serverUrl = ServerUrlProvider().apply { set(testServerAddress()) }
    val client: HttpClient = createIglooHttpClient(
        credentials = credentials,
        authEvents = authEvents,
        engine = MockEngine(
            MockEngineConfig().apply {
                engineDispatcher?.let { dispatcher = it }
                addHandler(handler)
            },
        ),
    )
    val api = AuthApi(client, serverUrl)
    val userApi = UserApi(client, serverUrl)
    val authRepository = AuthRepository(api, userApi, profiles, testDeviceIdentity)
    val movieApi = MovieApi(client, serverUrl)
    val movieRepository = MovieRepository(movieApi)
    val musicApi = MusicApi(client, serverUrl)
    val musicRepository = MusicRepository(musicApi)

    /** Puts profiles in the vault without going through a sign-in. */
    fun seedVault(
        vararg profiles: StoredProfile,
        activeUserId: Long? = null,
        pendingToken: String? = null,
    ) {
        profileStore.vault = ProfileVault(
            activeUserId = activeUserId,
            pendingToken = pendingToken,
            profiles = profiles.toList(),
        )
    }
}

fun testStoredProfile(
    userId: Long = 1,
    name: String = "Jose",
    token: String = "igd_$userId",
    hasPin: Boolean = false,
    lastUsedAtEpochMillis: Long = userId,
) = StoredProfile(
    userId = userId,
    token = token,
    name = name,
    avatarUrl = null,
    hasPin = hasPin,
    lastUsedAtEpochMillis = lastUsedAtEpochMillis,
)

fun testServerAddress(origin: String = "http://igloo.test:8080"): ServerAddress =
    (parseServerAddress(origin) as ServerAddressParseResult.Valid).address

/**
 * [dispatcher] must be backed by the caller's test scheduler, and the mock engine is put on it
 * too so the probe's `withTimeout` and the response it is waiting for share one clock. The
 * probe's production default is `Dispatchers.IO`, and a real thread hop escapes `runTest`: the
 * probe can resume after the test has finished and, for a caller on `Dispatchers.Main`, after
 * `resetMain()` has run — which then fails whichever unrelated test is running by then.
 */
fun testServerHealthProbe(
    handler: MockRequestHandler,
    timeoutMillis: Long = 10_000,
    dispatcher: CoroutineDispatcher,
): ServerHealthProbe = ServerHealthProbe(
    client = createServerProbeHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler(handler)
            },
        ),
    ),
    timeoutMillis = timeoutMillis,
    dispatcher = dispatcher,
)

fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

/** Go `sql.NullString` wire shape; null renders the invalid wrapper, not JSON null. */
fun sqlNullStringJson(value: String?): String =
    if (value != null) """{"String":"$value","Valid":true}""" else """{"String":"","Valid":false}"""

fun sqlNullInt64Json(value: Long?): String =
    if (value != null) """{"Int64":$value,"Valid":true}""" else """{"Int64":0,"Valid":false}"""

fun sqlNullFloat64Json(value: Double?): String =
    if (value != null) """{"Float64":$value,"Valid":true}""" else """{"Float64":0,"Valid":false}"""

/** One `GET /movies/latest` list entry in the Go `sql.Null*` wire shapes. */
fun latestMovieJson(
    id: Long = 1,
    title: String = "Heat",
    posterPath: String? = "/heat.jpg",
    year: Long? = 1995,
): String = """{"id":$id,"title":"$title","poster_path":${sqlNullStringJson(posterPath)},""" +
    """"year":${sqlNullInt64Json(year)}}"""

fun latestMoviesJson(vararg movies: String): String =
    """{"error":false,"message":"latest movies","data":{"movies":[${movies.joinToString(",")}]}}"""

/** One `GET /movies/library` list entry: the latest-movie shape plus a certification. */
fun movieLibraryItemJson(
    id: Long = 1,
    title: String = "Heat",
    posterPath: String? = "/heat.jpg",
    year: Long? = 1995,
    certification: String? = "R",
): String = """{"id":$id,"title":"$title","poster_path":${sqlNullStringJson(posterPath)},""" +
    """"year":${sqlNullInt64Json(year)},""" +
    """"certification":${sqlNullStringJson(certification)}}"""

/** A `GET /movies/library` page. The paging counts are what the grid's tail is driven by. */
fun moviesLibraryJson(
    page: Long = 1,
    perPage: Long = 48,
    total: Long = 1,
    totalPages: Long = 1,
    sort: String = "asc",
    vararg movies: String,
): String = """{"error":false,"message":"movies library","data":{""" +
    """"movies":[${movies.joinToString(",")}],"total":$total,"page":$page,""" +
    """"per_page":$perPage,"total_pages":$totalPages,"sort":"$sort"}}"""

fun moviesStatsJson(totalMovies: Long = 1): String =
    """{"error":false,"message":"movie stats","data":{"total_movies":$totalMovies}}"""

/** One `GET /movies/genres` list entry. */
fun movieGenreWithCountJson(
    id: Long = 7,
    tag: String = "Action",
    movieCount: Long = 26,
): String = """{"genre_id":$id,"genre_tag":"$tag","movie_count":$movieCount}"""

fun moviesGenresJson(vararg genres: String): String =
    """{"error":false,"message":"movie genres","data":{"genres":[${genres.joinToString(",")}]}}"""

/** One `GET /continue-watching` movie entry: the latest-movie shape plus a kind and progress. */
fun continueWatchingMovieJson(
    id: Long = 1,
    title: String = "Heat",
    posterPath: String? = "/heat.jpg",
    year: Long? = 1995,
    progressSec: Double = 1800.0,
    durationSec: Double = 10200.0,
): String {
    val movie = latestMovieJson(id, title, posterPath, year)
    return """{"kind":"movie",""" + movie.drop(1).dropLast(1) +
        ""","progress_sec":$progressSec,"duration_sec":$durationSec}"""
}

/** One `GET /continue-watching` episode entry; title, poster and year describe the show. */
fun continueWatchingEpisodeJson(
    id: Long = 900,
    showTitle: String = "Severance",
    showId: Long = 40,
    seasonNumber: Long = 1,
    episodeNumber: Long = 3,
    episodeName: String = "In Perpetuity",
    progressSec: Double = 600.0,
    durationSec: Double = 3300.0,
): String = """{"kind":"episode","id":$id,"title":"$showTitle",""" +
    """"poster_path":${sqlNullStringJson("/severance.jpg")},"year":${sqlNullInt64Json(2022)},""" +
    """"progress_sec":$progressSec,"duration_sec":$durationSec,"show_id":$showId,""" +
    """"season_number":$seasonNumber,"episode_number":$episodeNumber,"episode_name":"$episodeName"}"""

fun continueWatchingJson(vararg items: String): String =
    """{"error":false,"message":"continue watching","data":{"items":[${items.joinToString(",")}]}}"""

/** One `GET /music/albums/latest` list entry; covers arrive as absolute Spotify URLs. */
fun simpleAlbumJson(
    id: Long = 1,
    title: String = "Help!",
    cover: String? = "https://i.scdn.co/image/help.jpg",
    musician: String? = "The Beatles",
    year: Long? = 1965,
): String = """{"id":$id,"title":"$title","cover":${sqlNullStringJson(cover)},""" +
    """"musician":${sqlNullStringJson(musician)},"year":${sqlNullInt64Json(year)}}"""

fun latestAlbumsJson(vararg albums: String): String =
    """{"error":false,"message":"latest albums","data":{"albums":[${albums.joinToString(",")}]}}"""

/** The full album row `GET /music/albums/details/{id}` returns under `album`. */
fun albumJson(
    id: Long = 1,
    title: String = "Help!",
    cover: String? = "https://i.scdn.co/image/help.jpg",
    musician: String? = "The Beatles",
    releaseDate: String? = "1965-08-06",
    year: Long? = 1965,
    totalTracks: Long? = 14,
    spotifyPopularity: Double? = 73.0,
): String = """{"id":$id,"title":"$title","sort_title":"${title.lowercase()}",""" +
    """"spotify_id":${sqlNullStringJson("spot-$id")},""" +
    """"spotify_popularity":${sqlNullFloat64Json(spotifyPopularity)},""" +
    """"musician":${sqlNullStringJson(musician)},""" +
    """"release_date":${sqlNullStringJson(releaseDate)},""" +
    """"year":${sqlNullInt64Json(year)},""" +
    """"total_tracks":${sqlNullInt64Json(totalTracks)},""" +
    """"cover":${sqlNullStringJson(cover)},""" +
    """"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""

/** One album-details track row (the contract's `AlbumTrack`); `duration` is milliseconds. */
fun albumTrackJson(
    id: Long = 1,
    title: String = "Yesterday",
    trackIndex: Long = 1,
    durationMs: Long = 125_000,
    disc: Long = 1,
    codec: String = "flac",
    bitRate: Long = 900_000,
    channelLayout: String = "stereo",
    albumId: Long = 1,
    musicianId: Long? = 4,
): String = """{"id":$id,"title":"$title","codec":"$codec","mime_type":"audio/flac",""" +
    """"track_index":$trackIndex,"duration":$durationMs,"disc":$disc,""" +
    """"channel_layout":"$channelLayout","bit_rate":$bitRate,""" +
    """"album_id":${sqlNullInt64Json(albumId)},"musician_id":${sqlNullInt64Json(musicianId)}}"""

/** One album-details artist: a full musician row on the wire; the model reads a subset. */
fun albumArtistJson(
    id: Long = 4,
    name: String = "The Beatles",
    thumb: String? = null,
): String = """{"id":$id,"name":"$name","sort_name":"${name.lowercase()}",""" +
    """"thumb":${sqlNullStringJson(thumb)},"spotify_id":${sqlNullStringJson("artist-$id")}}"""

fun trackGenreJson(trackId: Long = 1, genreId: Long = 12, tag: String = "Rock"): String =
    """{"track_id":$trackId,"genre_id":$genreId,"tag":"$tag"}"""

/** A `GET /music/albums/details/{id}` payload; `total_duration` is milliseconds. */
fun albumDetailsJson(
    album: String = albumJson(),
    tracks: List<String> = listOf(albumTrackJson()),
    artists: List<String> = listOf(albumArtistJson()),
    trackGenres: List<String> = listOf(trackGenreJson()),
    albumGenres: List<String> = listOf("Rock"),
    totalDurationMs: Double = 125_000.0,
): String = """{"error":false,"message":"album details","data":{""" +
    """"album":$album,"tracks":[${tracks.joinToString(",")}],""" +
    """"artists":[${artists.joinToString(",")}],""" +
    """"track_genres":[${trackGenres.joinToString(",")}],""" +
    """"album_genres":[${albumGenres.joinToString(",") { "\"$it\"" }}],""" +
    """"total_duration":$totalDurationMs}}"""

/** One `GET /music/albums` page; the same `SimpleAlbum` rows as the latest-albums rail. */
fun albumsJson(
    albums: List<String>,
    total: Long = albums.size.toLong(),
    page: Long = 1,
    perPage: Long = 48,
    totalPages: Long = 1,
): String = """{"error":false,"message":"albums","data":{"albums":[${albums.joinToString(",")}],""" +
    """"total":$total,"page":$page,"per_page":$perPage,"total_pages":$totalPages}}"""

/** One `GET /music/musicians` list entry; no `sort_name` — the server sorts by it but keeps it. */
fun simpleMusicianJson(
    id: Long = 4,
    name: String = "The Beatles",
    thumb: String? = "https://i.scdn.co/image/beatles.jpg",
    albumCount: Long = 3,
    trackCount: Long = 40,
): String = """{"id":$id,"name":"$name","thumb":${sqlNullStringJson(thumb)},""" +
    """"album_count":$albumCount,"track_count":$trackCount}"""

fun musiciansJson(
    musicians: List<String>,
    total: Long = musicians.size.toLong(),
    page: Long = 1,
    perPage: Long = 48,
    totalPages: Long = 1,
): String = """{"error":false,"message":"musicians","data":{""" +
    """"musicians":[${musicians.joinToString(",")}],""" +
    """"total":$total,"page":$page,"per_page":$perPage,"total_pages":$totalPages}}"""

/** The full musician row `GET /music/musicians/{id}` returns under `musician`. */
fun musicianJson(
    id: Long = 4,
    name: String = "The Beatles",
    summary: String? = "Liverpool, 1960.",
    spotifyPopularity: Double? = 88.0,
    spotifyFollowers: Long? = 25_000_000,
    thumb: String? = "https://i.scdn.co/image/beatles.jpg",
): String = """{"id":$id,"name":"$name","sort_name":"${name.lowercase()}",""" +
    """"summary":${sqlNullStringJson(summary)},"spotify_id":${sqlNullStringJson("artist-$id")},""" +
    """"spotify_popularity":${sqlNullFloat64Json(spotifyPopularity)},""" +
    """"spotify_followers":${sqlNullInt64Json(spotifyFollowers)},""" +
    """"thumb":${sqlNullStringJson(thumb)},""" +
    """"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""

/** One discography entry of a musician-details payload. */
fun musicianAlbumJson(
    id: Long = 1,
    title: String = "Help!",
    cover: String? = "https://i.scdn.co/image/help.jpg",
    year: Long? = 1965,
    releaseDate: String? = "1965-08-06",
    trackCount: Long = 14,
): String = """{"id":$id,"title":"$title","cover":${sqlNullStringJson(cover)},""" +
    """"year":${sqlNullInt64Json(year)},"release_date":${sqlNullStringJson(releaseDate)},""" +
    """"track_count":$trackCount}"""

/** One track of a musician-details payload: its album, no musician columns; `duration` is ms. */
fun musicianTrackJson(
    id: Long = 900,
    title: String = "Yesterday",
    durationMs: Long = 125_000,
    albumId: Long? = 1,
    albumTitle: String? = "Help!",
    albumCover: String? = "https://i.scdn.co/image/help.jpg",
): String = """{"id":$id,"title":"$title","duration":$durationMs,"codec":"flac",""" +
    """"bit_rate":900000,"album_id":${sqlNullInt64Json(albumId)},""" +
    """"album_title":${sqlNullStringJson(albumTitle)},"album_cover":${sqlNullStringJson(albumCover)}}"""

/** A `GET /music/musicians/{id}` payload; `total_duration` is milliseconds. */
fun musicianDetailsJson(
    musician: String = musicianJson(),
    albums: List<String> = listOf(musicianAlbumJson()),
    tracks: List<String> = listOf(musicianTrackJson()),
    genres: List<String> = listOf("Rock", "Pop"),
    totalDurationMs: Double = 125_000.0,
): String = """{"error":false,"message":"musician details","data":{""" +
    """"musician":$musician,"albums":[${albums.joinToString(",")}],""" +
    """"tracks":[${tracks.joinToString(",")}],""" +
    """"genres":[${genres.joinToString(",") { "\"$it\"" }}],""" +
    """"total_duration":$totalDurationMs}}"""

/**
 * One library track row (`GET /music/tracks`, `/music/tracks/shuffle`). `duration` is
 * milliseconds; the album and musician columns are LEFT JOINs, hence the wrappers.
 */
fun trackListItemJson(
    id: Long = 900,
    title: String = "Yesterday",
    durationMs: Long = 125_000,
    albumId: Long? = 1,
    albumTitle: String? = "Help!",
    albumCover: String? = "https://i.scdn.co/image/help.jpg",
    musicianId: Long? = 4,
    musicianName: String? = "The Beatles",
): String = """{"id":$id,"title":"$title","duration":$durationMs,"codec":"flac",""" +
    """"bit_rate":900000,"album_id":${sqlNullInt64Json(albumId)},""" +
    """"album_title":${sqlNullStringJson(albumTitle)},"album_cover":${sqlNullStringJson(albumCover)},""" +
    """"musician_id":${sqlNullInt64Json(musicianId)},""" +
    """"musician_name":${sqlNullStringJson(musicianName)}}"""

/** A `GET /music/tracks` window; `has_more` is the cursor, `total` the library count. */
fun tracksJson(
    tracks: List<String>,
    total: Long = tracks.size.toLong(),
    offset: Long = 0,
    limit: Long = 50,
    hasMore: Boolean = false,
): String = """{"error":false,"message":"tracks","data":{"tracks":[${tracks.joinToString(",")}],""" +
    """"total":$total,"offset":$offset,"limit":$limit,"has_more":$hasMore}}"""

fun shuffleTracksJson(vararg tracks: String): String =
    """{"error":false,"message":"shuffle","data":{"tracks":[${tracks.joinToString(",")}]}}"""

fun likedTrackIdsJson(vararg ids: Long): String =
    """{"error":false,"message":"liked ids","data":{"liked_track_ids":[${ids.joinToString(",")}]}}"""

fun trackLikeToggleJson(trackId: Long = 900, isLiked: Boolean = true): String =
    """{"error":false,"message":"like toggled","data":{"track_id":$trackId,"is_liked":$isLiked}}"""

fun musicStatsJson(albums: Long = 12, tracks: Long = 150, musicians: Long = 9): String =
    """{"error":false,"message":"stats","data":{"total_albums":$albums,"total_tracks":$tracks,""" +
    """"total_musicians":$musicians}}"""

/**
 * One `GET /tmdb/movies/in-theaters` list entry. TMDB fields are plain values, not `sql.Null*`
 * wrappers; every field the contract requires is emitted even though the model maps a subset.
 */
fun theaterMovieJson(
    id: Int = 1,
    title: String = "Heat 2",
    releaseDate: String = "2026-08-01",
    posterPath: String = "/heat2.jpg",
    voteAverage: Double = 7.9,
): String = """{"id":$id,"title":"$title","original_title":"$title",""" +
    """"overview":"A prequel and sequel.","release_date":"$releaseDate",""" +
    """"poster_path":"$posterPath","backdrop_path":"/heat2-backdrop.jpg",""" +
    """"popularity":100.5,"vote_average":$voteAverage,"vote_count":1000,"adult":false,""" +
    """"original_language":"en","genre_ids":[80,18],"video":false}"""

fun theaterMoviesJson(vararg movies: String): String =
    """{"error":false,"message":"movies in theaters","data":{"movies":[${movies.joinToString(",")}]}}"""

/**
 * `GET /tmdb/movies/{id}` payload — the in-theaters detail screen's one read. The server marshals
 * plain Go values rather than `sql.Null*` wrappers, so only the lists can arrive as `null` (which
 * the nullable parameters here reproduce); a string it has nothing for arrives as `""`.
 */
fun tmdbMovieJson(
    id: Int = 21,
    title: String = "Heat 2",
    overview: String = "A prequel and a sequel.",
    releaseDate: String = "2026-08-01",
    posterPath: String = "/heat2.jpg",
    backdropPath: String = "/heat2-backdrop.jpg",
    voteAverage: Double = 7.9,
    runtime: Long = 170,
    status: String = "Released",
    tagline: String = "A Los Angeles crime saga.",
    budget: Long = 60_000_000,
    revenue: Long = 187_436_818,
    originalLanguage: String = "en",
    genres: List<String>? = listOf(tmdbGenreJson(), tmdbGenreJson(id = 18, name = "Drama")),
    productionCompanies: List<String>? = listOf(tmdbProductionCompanyJson()),
    cast: List<String>? = emptyList(),
    crew: List<String>? = emptyList(),
    videos: List<String>? = emptyList(),
    releaseDates: List<String>? = listOf(tmdbCountryReleaseDatesJson()),
): String = """
    {"error":false,"message":"tmdb movie","data":{"movie":{
      "id":$id,"title":"$title","original_title":"$title",
      "overview":"$overview","release_date":"$releaseDate",
      "poster_path":"$posterPath",
      "backdrop_path":"$backdropPath",
      "popularity":1831.2,"vote_average":$voteAverage,"vote_count":1829,"adult":false,
      "original_language":"$originalLanguage","genre_ids":null,"video":false,
      "runtime":$runtime,"status":"$status","tagline":"$tagline",
      "budget":$budget,"revenue":$revenue,"homepage":"","imdb_id":"tt0113277",
      "production_companies":${jsonArrayOrNull(productionCompanies)},
      "genres":${jsonArrayOrNull(genres)},
      "credits":{"cast":${jsonArrayOrNull(cast)},"crew":${jsonArrayOrNull(crew)}},
      "videos":{"results":${jsonArrayOrNull(videos)}},
      "release_dates":{"results":${jsonArrayOrNull(releaseDates)}}
    }}}
""".trimIndent()

fun tmdbGenreJson(id: Int = 80, name: String = "Crime"): String = """{"id":$id,"name":"$name"}"""

fun tmdbProductionCompanyJson(
    id: Int = 508,
    name: String = "Regency Enterprises",
): String = """{"id":$id,"logo_path":"/regency.png","name":"$name","origin_country":"US"}"""

fun tmdbCastJson(
    id: Int = 100,
    name: String = "Al Pacino",
    character: String = "Vincent Hanna",
    profilePath: String = "/pacino.jpg",
    order: Int = 0,
): String = """{"id":$id,"name":"$name","character":"$character",""" +
    """"profile_path":"$profilePath","order":$order}"""

fun tmdbCrewJson(
    id: Int = 200,
    name: String = "Michael Mann",
    job: String = "Director",
    department: String = "Directing",
): String = """{"id":$id,"name":"$name","job":"$job","department":"$department",""" +
    """"profile_path":"/mann.jpg"}"""

fun tmdbVideoJson(
    id: String = "v1",
    key: String = "0xbkYZbdIVw",
    name: String = "Official Trailer",
    site: String = "YouTube",
    type: String = "Trailer",
): String = """{"id":"$id","key":"$key","name":"$name","site":"$site","type":"$type",""" +
    """"official":true}"""

fun tmdbCountryReleaseDatesJson(
    country: String = "US",
    certifications: List<String> = listOf("R"),
): String = """{"iso_3166_1":"$country","release_dates":[""" +
    certifications.joinToString(",") { """{"certification":"$it"}""" } +
    "]}"

private fun jsonArrayOrNull(values: List<String>?): String =
    values?.joinToString(",", prefix = "[", postfix = "]") ?: "null"

/**
 * `GET /movies/details/{id}` payload: the movie plus its related lists. The lists default empty;
 * details-screen tests pass populated entries from the builders below.
 */
fun movieDetailsJson(
    id: Long = 1,
    title: String = "Heat",
    backdropPath: String? = "/heat-backdrop.jpg",
    overview: String? = "Obsessive master thief Neil McCauley leads a top-notch crew.",
    year: Long? = 1995,
    certification: String? = "R",
    runTimeMinutes: Long? = 170,
    criticRating: Double? = 8.2,
    posterPath: String? = "/heat.jpg",
    tagLine: String? = "A Los Angeles crime saga.",
    releaseDate: String? = "1995-12-15",
    language: String? = "en",
    budget: Double? = 60000000.0,
    revenue: Double? = 187436818.0,
    cast: List<String> = emptyList(),
    crew: List<String> = emptyList(),
    genres: List<String> = emptyList(),
    productionCompanies: List<String> = emptyList(),
    extraVideos: List<String> = emptyList(),
): String = """
    {"error":false,"message":"movie details","data":{
      "movie":{
        "id":$id,"title":"$title","adult":false,
        "poster_path":${sqlNullStringJson(posterPath)},
        "backdrop_path":${sqlNullStringJson(backdropPath)},
        "overview":${sqlNullStringJson(overview)},
        "tag_line":${sqlNullStringJson(tagLine)},
        "release_date":${sqlNullStringJson(releaseDate)},
        "language":${sqlNullStringJson(language)},
        "year":${sqlNullInt64Json(year)},
        "certification":${sqlNullStringJson(certification)},
        "run_time":${sqlNullInt64Json(runTimeMinutes)},
        "critic_rating":${sqlNullFloat64Json(criticRating)},
        "budget":${sqlNullFloat64Json(budget)},
        "revenue":${sqlNullFloat64Json(revenue)}
      },
      "cast":[${cast.joinToString(",")}],
      "crew":[${crew.joinToString(",")}],
      "genres":[${genres.joinToString(",")}],
      "production_companies":[${productionCompanies.joinToString(",")}],
      "extra_videos":[${extraVideos.joinToString(",")}]
    }}
""".trimIndent()

fun castMemberJson(
    id: Long = 1,
    character: String = "Neil McCauley",
    castOrder: Long = 0,
    artistName: String = "Robert De Niro",
    artistProfile: String? = "/deniro.jpg",
): String = """{"id":$id,"character":"$character","cast_order":$castOrder,""" +
    """"artist_name":"$artistName","artist_profile":${sqlNullStringJson(artistProfile)}}"""

fun crewMemberJson(
    id: Long = 1,
    job: String = "Director",
    department: String = "Directing",
    artistName: String = "Michael Mann",
): String = """{"id":$id,"job":"$job","department":"$department","artist_name":"$artistName"}"""

fun movieGenreJson(id: Long = 1, tag: String = "Crime"): String = """{"id":$id,"tag":"$tag"}"""

fun productionCompanyJson(
    id: Long = 1,
    name: String = "Regency Enterprises",
): String = """{"id":$id,"name":"$name"}"""

fun extraVideoJson(
    id: Long = 1,
    title: String = "Heat - Trailer",
    key: String = "0xbkYZbdIVw",
    type: String = "trailer",
    site: String = "youtube",
): String = """{"id":$id,"title":"$title","key":"$key","type":"$type","site":"$site"}"""

fun videoStreamJson(
    id: Long = 1,
    movieId: Long = 1,
    codec: String = "hevc",
    width: Long = 3840,
    height: Long = 1600,
    colorTransfer: String? = "smpte2084",
    bitDepth: Long? = 10,
): String = """{"id":$id,"movie_id":$movieId,"stream_index":0,"codec":"$codec",""" +
    """"codec_profile":${sqlNullStringJson("Main 10")},"codec_level":${sqlNullInt64Json(153)},""" +
    """"bit_rate":0,"width":$width,"height":$height,""" +
    """"coded_width":${sqlNullInt64Json(width)},"coded_height":${sqlNullInt64Json(height)},""" +
    """"aspect_ratio":${sqlNullStringJson("12:5")},"frame_rate":23.976,""" +
    """"avg_frame_rate":${sqlNullStringJson("24000/1001")},""" +
    """"bit_depth":${sqlNullInt64Json(bitDepth)},"pixel_format":${sqlNullStringJson("yuv420p10le")},""" +
    """"color_range":${sqlNullStringJson("tv")},"color_space":${sqlNullStringJson("bt2020nc")},""" +
    """"color_primaries":${sqlNullStringJson("bt2020")},""" +
    """"color_transfer":${sqlNullStringJson(colorTransfer)},""" +
    """"field_order":${sqlNullStringJson(null)},"rotation":${sqlNullInt64Json(null)},""" +
    """"language":${sqlNullStringJson(null)},"title":${sqlNullStringJson(null)}}"""

fun audioStreamJson(
    id: Long = 1,
    movieId: Long = 1,
    codec: String = "dts",
    channels: Long = 6,
    channelLayout: String? = "5.1(side)",
    language: String? = "eng",
    isDefault: Boolean = true,
): String = """{"id":$id,"movie_id":$movieId,"stream_index":1,"codec":"$codec",""" +
    """"codec_profile":${sqlNullStringJson("DTS-HD MA")},"bit_rate":0,""" +
    """"sample_rate":${sqlNullInt64Json(48000)},"channels":$channels,""" +
    """"channel_layout":${sqlNullStringJson(channelLayout)},""" +
    """"language":${sqlNullStringJson(language)},"title":${sqlNullStringJson(null)},""" +
    """"is_default":$isDefault}"""

fun subtitleJson(
    id: Long = 1,
    movieId: Long = 1,
    codec: String = "subrip",
    language: String? = "eng",
    isForced: Boolean = false,
    isDefault: Boolean = false,
): String = """{"id":$id,"movie_id":$movieId,"stream_index":2,"codec":"$codec",""" +
    """"language":${sqlNullStringJson(language)},"title":${sqlNullStringJson(null)},""" +
    """"is_forced":$isForced,"is_default":$isDefault}"""

fun chapterJson(
    id: Long = 1,
    title: String = "00:03:13.026",
    startTimeSec: Long = 193,
    thumb: String? = null,
    movieId: Long = 1,
): String = """{"id":$id,"title":"$title","start_time":$startTimeSec,""" +
    """"thumb":${sqlNullStringJson(thumb)},"movie_id":$movieId}"""

/** `GET /movies/{id}/technical-details` payload; `movie` is the file's playback subset. */
fun technicalDetailsJson(
    mimeType: String = "video/x-matroska",
    videoStreams: List<String> = listOf(videoStreamJson()),
    audioStreams: List<String> = listOf(audioStreamJson()),
    subtitles: List<String> = listOf(subtitleJson()),
    chapters: List<String> = emptyList(),
): String = """
    {"error":false,"message":"technical details","data":{
      "movie":{"file_name":"heat.mkv","size":4000000000,"container":"mkv","mime_type":"$mimeType",
        "run_time":${sqlNullInt64Json(170)},"duration":${sqlNullFloat64Json(10200.0)}},
      "video_streams":[${videoStreams.joinToString(",")}],
      "audio_streams":[${audioStreams.joinToString(",")}],
      "subtitles":[${subtitles.joinToString(",")}],
      "chapters":[${chapters.joinToString(",")}]
    }}
""".trimIndent()

/** `GET /movies/{id}/watch-progress` payload — plain JSON nulls, not `sql.Null*` wrappers. */
fun watchProgressJson(
    progressSec: Double? = null,
    durationSec: Double? = null,
    watched: Boolean = false,
    updatedAt: String? = null,
): String = """{"error":false,"message":"watch progress","data":{""" +
    """"progress_sec":${progressSec ?: "null"},"duration_sec":${durationSec ?: "null"},""" +
    """"watched":$watched,"updated_at":${updatedAt?.let { "\"$it\"" } ?: "null"}}}"""

fun likeStatusJson(isLiked: Boolean = false): String =
    """{"error":false,"message":"like status","data":{"is_liked":$isLiked}}"""

fun likeToggleJson(movieId: Long = 1, isLiked: Boolean = true): String =
    """{"error":false,"message":"like toggled","data":{"movie_id":$movieId,"is_liked":$isLiked}}"""

fun watchedUpdateJson(movieId: Long = 1, watched: Boolean = true): String =
    """{"error":false,"message":"watched updated","data":{"movie_id":$movieId,"watched":$watched}}"""

/** `GET /auth/user` payload; `has_pin` is required by the contract. */
fun authUserJson(
    id: Long = 1,
    name: String = "Jose",
    hasPin: Boolean = false,
): String = """
    {"error":false,"message":"user found","data":{"user":{
        "id":$id,"name":"$name","email":"${name.lowercase()}@example.com","is_admin":false,
        "avatar":null,"has_pin":$hasPin,
        "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
    }}}
""".trimIndent()
