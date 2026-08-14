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

fun testServerHealthProbe(
    handler: MockRequestHandler,
    timeoutMillis: Long = 10_000,
): ServerHealthProbe = ServerHealthProbe(
    client = createServerProbeHttpClient(MockEngine(handler)),
    timeoutMillis = timeoutMillis,
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

/** One `GET /movies/continue-watching` list entry: the latest-movie shape plus progress. */
fun continueWatchingMovieJson(
    id: Long = 1,
    title: String = "Heat",
    posterPath: String? = "/heat.jpg",
    year: Long? = 1995,
    progressSec: Double = 1800.0,
    durationSec: Double = 10200.0,
): String {
    val movie = latestMovieJson(id, title, posterPath, year)
    return movie.dropLast(1) + ""","progress_sec":$progressSec,"duration_sec":$durationSec}"""
}

fun continueWatchingMoviesJson(vararg movies: String): String =
    """{"error":false,"message":"continue watching","data":{"movies":[${movies.joinToString(",")}]}}"""

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
 * `GET /movies/details/{id}` payload: the full movie plus its related lists, which the spec
 * leaves untyped and these tests leave empty. Only hero-relevant nullables are parameterized.
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
): String = """
    {"error":false,"message":"movie details","data":{
      "movie":{
        "id":$id,"title":"$title","file_path":"/media/heat.mkv","file_name":"heat.mkv",
        "size":4000000000,"container":"mkv","mime_type":"video/x-matroska","adult":false,
        "backdrop_path":${sqlNullStringJson(backdropPath)},
        "overview":${sqlNullStringJson(overview)},
        "year":${sqlNullInt64Json(year)},
        "certification":${sqlNullStringJson(certification)},
        "run_time":${sqlNullInt64Json(runTimeMinutes)},
        "critic_rating":${sqlNullFloat64Json(criticRating)},
        "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
      },
      "cast":[],"crew":[],"genres":[],"production_companies":[],"extra_videos":[]
    }}
""".trimIndent()

/** `GET /auth/user` payload; `has_pin` is required by the contract. */
fun authUserJson(
    id: Long = 1,
    name: String = "Jose",
    hasPin: Boolean = false,
): String = """
    {"error":false,"message":"user found","data":{"user":{
        "id":$id,"name":"$name","email":"${name.lowercase()}@example.com","is_admin":false,
        "avatar":{"String":"","Valid":false},"has_pin":$hasPin,
        "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
    }}}
""".trimIndent()
