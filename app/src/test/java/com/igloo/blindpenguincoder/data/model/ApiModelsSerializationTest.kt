package com.igloo.blindpenguincoder.data.model

import com.igloo.blindpenguincoder.core.network.IglooJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsSerializationTest {
    // The production decoder, so a payload that passes here is one the app can really decode.
    private val json = IglooJson

    /** `GET /auth/user`: exactly AuthUser's required keys. `avatar` is a string or null. */
    @Test
    fun decodesAuthUserEnvelope() {
        val body = authUserBody("null")

        val envelope = json.decodeFromString<ApiEnvelope<AuthUserData>>(body)

        assertFalse(envelope.error)
        val user = envelope.data!!.user
        assertEquals(7L, user.id)
        assertEquals("Jose", user.name)
        assertEquals("jose@example.com", user.email)
        assertTrue(user.isAdmin)
        assertTrue(user.hasPin)
        assertNull(user.avatar)
    }

    /**
     * The case that broke sign-in: `avatar` was modelled as a Go `sql.NullString` object, but
     * userResponseMap unwraps it, so a user with an uploaded avatar sends a bare string and
     * the whole authenticated session failed to decode.
     */
    @Test
    fun decodesAuthUserWithUploadedAvatarPath() {
        val body = authUserBody("\"/api/static/avatars/7-1735689600.jpg\"")

        val user = json.decodeFromString<ApiEnvelope<AuthUserData>>(body).data!!.user

        assertEquals("/api/static/avatars/7-1735689600.jpg", user.avatar)
    }

    /** `PUT /users/avatar` takes an arbitrary string, so an absolute URL is equally valid. */
    @Test
    fun decodesAuthUserWithAbsoluteAvatarUrl() {
        val body = authUserBody("\"https://cdn.example.com/a.png\"")

        val user = json.decodeFromString<ApiEnvelope<AuthUserData>>(body).data!!.user

        assertEquals("https://cdn.example.com/a.png", user.avatar)
    }

    private fun authUserBody(avatar: String) = """
        {
          "error": false,
          "message": "user found",
          "data": {
            "user": {
              "id": 7,
              "name": "Jose",
              "email": "jose@example.com",
              "is_admin": true,
              "has_pin": true,
              "avatar": $avatar,
              "created_at": "2026-01-01T00:00:00Z",
              "updated_at": "2026-01-02T00:00:00Z"
            }
          }
        }
    """.trimIndent()

    @Test
    fun decodesErrorEnvelopeWithoutData() {
        val body = """{"error": true, "message": "invalid credentials"}"""

        val envelope = json.decodeFromString<ApiEnvelope<AuthUserData>>(body)

        assertTrue(envelope.error)
        assertEquals("invalid credentials", envelope.message)
        assertNull(envelope.data)
    }

    @Test
    fun decodesMoviesLibraryWithSqlNullFieldsAndUnmodelledEchoes() {
        val body = """
            {
              "error": false,
              "data": {
                "movies": [
                  {
                    "id": 1,
                    "title": "Arrival",
                    "poster_path": {"String": "/arrival.jpg", "Valid": true},
                    "year": {"Int64": 0, "Valid": false},
                    "certification": {"String": "PG-13", "Valid": true}
                  }
                ],
                "total": 1,
                "page": 1,
                "per_page": 24,
                "total_pages": 1,
                "sort": "asc"
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<MoviesLibraryData>>(body)

        // `certification`, `page`, `per_page`, and `sort` ride along unmodelled;
        // `ignoreUnknownKeys` must absorb them without failing the decode.
        val data = envelope.data!!
        assertEquals(1L, data.total)
        assertEquals(1L, data.totalPages)
        val movie = data.movies.single()
        assertEquals("/arrival.jpg", movie.posterPath.orNull())
        assertNull(movie.year.orNull())
    }

    /**
     * `GET /shows/library`: the movie page's shape under `shows`, with `name` and `premiere_year`.
     */
    @Test
    fun decodesShowsLibraryWithSqlNullFieldsAndUnmodelledEchoes() {
        val body = """
            {
              "error": false,
              "data": {
                "shows": [
                  {
                    "id": 40,
                    "name": "Severance",
                    "poster_path": {"String": "/severance.jpg", "Valid": true},
                    "premiere_year": {"Int64": 0, "Valid": false},
                    "certification": {"String": "TV-MA", "Valid": true}
                  }
                ],
                "total": 1,
                "page": 1,
                "per_page": 24,
                "total_pages": 1,
                "sort": "asc"
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<ShowsLibraryData>>(body).data!!

        // `certification`, `page`, `per_page`, and `sort` ride along unmodelled, as on movies.
        assertEquals(1L, data.total)
        assertEquals(1L, data.totalPages)
        val show = data.shows.single()
        assertEquals(40L, show.id)
        assertEquals("Severance", show.name)
        assertEquals("/severance.jpg", show.posterPath.orNull())
        assertNull(show.premiereYear.orNull())
    }

    @Test
    fun decodesShowGenresAndStatsEnvelopes() {
        val genres = json.decodeFromString<ApiEnvelope<ShowGenresData>>(
            """{"error":false,"message":"show genres","data":{"genres":[""" +
                """{"genre_id":7,"genre_tag":"Comedy","show_count":2}]}}""",
        ).data!!.genres
        val stats = json.decodeFromString<ApiEnvelope<ShowsStatsData>>(
            """{"error":false,"message":"show stats","data":{"total_shows":3}}""",
        ).data!!

        assertEquals(
            listOf(ShowGenreWithCount(genreId = 7, genreTag = "Comedy", showCount = 2)),
            genres,
        )
        assertEquals(3L, stats.totalShows)
    }

    /**
     * `SortOrder.wireName` is spelled a second time so a query parameter can be built by hand.
     * This is the guard that keeps it from drifting away from the `@SerialName`.
     */
    @Test
    fun everySortOrderWireNameMatchesItsSerialName() {
        SortOrder.entries.forEach { order ->
            assertEquals(""""${order.wireName}"""", json.encodeToString(order))
        }
    }

    @Test
    fun decodesLatestMoviesEnvelope() {
        val body = """
            {
              "error": false,
              "message": "latest movies",
              "data": {
                "movies": [
                  {
                    "id": 5,
                    "title": "Heat",
                    "poster_path": {"String": "/heat.jpg", "Valid": true},
                    "year": {"Int64": 1995, "Valid": true}
                  },
                  {
                    "id": 6,
                    "title": "Untitled",
                    "poster_path": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false}
                  }
                ]
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<LatestMoviesData>>(body)

        val movies = envelope.data!!.movies
        assertEquals("/heat.jpg", movies[0].posterPath.orNull())
        assertEquals(1995L, movies[0].year.orNull())
        assertNull(movies[1].posterPath.orNull())
        assertNull(movies[1].year.orNull())
    }

    /**
     * `GET /continue-watching` mixes movies and episodes under one `kind`; the episode-only keys
     * decode on an episode and stay absent on a movie.
     */
    @Test
    fun decodesContinueWatchingEnvelope() {
        val body = """
            {
              "error": false,
              "message": "continue watching",
              "data": {
                "items": [
                  {
                    "kind": "movie",
                    "id": 5,
                    "title": "Heat",
                    "poster_path": {"String": "/heat.jpg", "Valid": true},
                    "year": {"Int64": 1995, "Valid": true},
                    "progress_sec": 1800.5,
                    "duration_sec": 10200
                  },
                  {
                    "kind": "episode",
                    "id": 900,
                    "title": "Severance",
                    "poster_path": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false},
                    "progress_sec": 45,
                    "duration_sec": 5400,
                    "show_id": 40,
                    "season_number": 1,
                    "episode_number": 3,
                    "episode_name": "In Perpetuity"
                  }
                ]
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<ContinueWatchingData>>(body)

        val items = envelope.data!!.items
        assertTrue(items[0].isMovie)
        assertEquals("/heat.jpg", items[0].posterPath.orNull())
        assertEquals(1995L, items[0].year.orNull())
        assertEquals(1800.5, items[0].progressSec, 0.0)
        assertEquals(10200.0, items[0].durationSec, 0.0)
        assertNull(items[0].episodeName)
        assertFalse(items[1].isMovie)
        assertTrue(items[1].isEpisode)
        assertEquals("episode", items[1].kind)
        assertEquals("Severance", items[1].title)
        assertNull(items[1].posterPath.orNull())
        assertNull(items[1].year.orNull())
        assertEquals(45.0, items[1].progressSec, 0.0)
        assertEquals(1L, items[1].seasonNumber)
        assertEquals(3L, items[1].episodeNumber)
        assertEquals("In Perpetuity", items[1].episodeName)
    }

    /**
     * `GET /shows/episodes/{id}`: `next_episode` is required but unread, populated or null, and
     * the catalog fields the player does not read ride along unmodelled.
     */
    @Test
    fun decodesShowEpisodePlaybackHeaderIgnoringUpNext() {
        fun body(nextEpisode: String) = """
            {
              "error": false,
              "data": {
                "show": {"id": 40, "name": "Severance",
                         "poster_path": {"String": "/severance.jpg", "Valid": true},
                         "backdrop_path": {"String": "", "Valid": false}},
                "season": {"season_number": 1, "name": "Season 1"},
                "episode": {"id": 900, "episode_number": 3, "name": "In Perpetuity",
                            "overview": {"String": "", "Valid": false},
                            "air_date": {"String": "2022-02-25", "Valid": true},
                            "still_path": {"String": "/still.jpg", "Valid": true},
                            "tmdb_runtime": {"Int64": 55, "Valid": true},
                            "vote_average": {"Float64": 8.1, "Valid": true},
                            "vote_count": {"Int64": 400, "Valid": true}},
                "next_episode": $nextEpisode
              }
            }
        """.trimIndent()
        val upNext = """{"id": 901, "season_number": 1, "episode_number": 4, "name": "Next",
            "still_path": {"String": "", "Valid": false},
            "progress_sec": {"Float64": 0, "Valid": false},
            "duration_sec": {"Float64": 0, "Valid": false}, "watched": false}"""

        listOf("null", upNext).forEach { nextEpisode ->
            val data = json.decodeFromString<ApiEnvelope<ShowEpisodePlaybackData>>(body(nextEpisode)).data!!
            assertEquals("Severance", data.show.name)
            assertEquals("/severance.jpg", data.show.posterPath.orNull())
            assertEquals(1L, data.season.seasonNumber)
            assertEquals(3L, data.episode.episodeNumber)
            assertEquals("In Perpetuity", data.episode.name)
        }
    }

    /** `GET /shows/episodes/{id}/technical-details`: the same stream rows, keyed by `file_id`. */
    @Test
    fun decodesShowEpisodeTechnicalDetails() {
        val body = """
            {
              "error": false,
              "data": {
                "file": {"file_name": "s01e03.mkv", "size": 1, "container": "mkv",
                         "mime_type": "video/x-matroska",
                         "duration": {"Float64": 3300.5, "Valid": true}},
                "video_streams": [],
                "audio_streams": [
                  {"id": 12, "file_id": 7, "stream_index": 1, "codec": "eac3",
                   "codec_profile": {"String": "", "Valid": false}, "bit_rate": 0,
                   "sample_rate": {"Int64": 48000, "Valid": true}, "channels": 6,
                   "channel_layout": {"String": "5.1", "Valid": true},
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "", "Valid": false}, "is_default": true}
                ],
                "subtitles": [
                  {"id": 13, "file_id": 7, "stream_index": 2, "codec": "subrip",
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "", "Valid": false},
                   "is_forced": false, "is_default": false}
                ],
                "chapters": [
                  {"id": 14, "title": "", "start_time": 60,
                   "thumb": {"String": "", "Valid": false}, "file_id": 7}
                ]
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<ShowEpisodeTechnicalDetailsData>>(body).data!!

        assertEquals("video/x-matroska", data.file.mimeType)
        assertEquals(3300.5, requireNotNull(data.file.duration.orNull()), 0.0)
        assertEquals("eac3", data.audioStreams.single().codec)
        assertEquals(6L, data.audioStreams.single().channels)
        assertEquals("subrip", data.subtitles.single().codec)
        assertEquals(60L, data.chapters.single().startTime)
    }

    @Test
    fun decodesMovieWatchProgressWithNulls() {
        val body = """
            {
              "error": false,
              "data": {
                "progress_sec": null,
                "duration_sec": null,
                "watched": false,
                "updated_at": null
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<WatchProgress>>(body)

        val progress = envelope.data!!
        assertNull(progress.progressSec)
        assertFalse(progress.watched)
    }

    @Test
    fun decodesWatchRoomServerEvent() {
        val body = """
            {
              "type": "playback_changed",
              "room_id": 3,
              "playback": {"paused": true, "position_sec": 42.5, "updated_at": "2026-07-01T10:00:00Z"}
            }
        """.trimIndent()

        val event = json.decodeFromString<WatchRoomServerEvent>(body)

        assertEquals(WatchRoomEventType.PlaybackChanged, event.type)
        assertEquals(3L, event.roomId)
        assertEquals(42.5, event.playback!!.positionSec, 0.0)
        assertNull(event.member)
    }

    @Test
    fun encodesPlaybackModeAndRequestsWithWireNames() {
        assertEquals("\"2160p_16mbps\"", json.encodeToString(PlaybackMode.P2160Mbps16))
        assertEquals("\"direct\"", json.encodeToString(PlaybackMode.Direct))

        val progress = json.encodeToString(
            UpdateWatchProgressRequest(
                progressSec = 30.0,
                durationSec = 7200.0,
                saveSessionId = "11111111-1111-4111-8111-111111111111",
                saveSequence = 1,
            ),
        )
        assertEquals(
            """{"progress_sec":30.0,"duration_sec":7200.0,""" +
                """"save_session_id":"11111111-1111-4111-8111-111111111111","save_sequence":1}""",
            progress,
        )
    }

    @Test
    fun decodesQuickConnectInitiateEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "code": "ABCD12",
                "secret": "device-secret",
                "expires_in_seconds": 600,
                "poll_interval_seconds": 2
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<QuickConnectInitiateData>>(body)

        val data = envelope.data!!
        assertEquals("ABCD12", data.code)
        assertEquals("device-secret", data.secret)
        assertEquals(600, data.expiresInSeconds)
        assertEquals(2, data.pollIntervalSeconds)
    }

    @Test
    fun decodesDeviceTokenEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "token": "igd_test",
                "device": {
                  "id": 9,
                  "name": "Shield",
                  "platform": "android_tv",
                  "app_version": "0.1.0",
                  "created_at": "2026-07-01T00:00:00Z",
                  "last_used_at": "2026-07-01T00:01:00Z",
                  "is_current": true
                }
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<DeviceTokenData>>(body)

        // The client only needs the token; the device object the backend also sends is ignored.
        val data = envelope.data!!
        assertEquals("igd_test", data.token)
    }

    @Test
    fun decodesQuickConnectRedeemStatuses() {
        val pending = json.decodeFromString<ApiEnvelope<QuickConnectRedeemData>>(
            """{"error":false,"data":{"status":"pending"}}""",
        )
        val approved = json.decodeFromString<ApiEnvelope<QuickConnectRedeemData>>(
            """
                {
                  "error": false,
                  "data": {
                    "status": "approved",
                    "token": "igd_test",
                    "device": {
                      "id": 9,
                      "name": "Shield",
                      "platform": "android_tv",
                      "app_version": null,
                      "created_at": "2026-07-01T00:00:00Z",
                      "last_used_at": "2026-07-01T00:01:00Z",
                      "is_current": true
                    }
                  }
                }
            """.trimIndent(),
        )

        val pendingData = pending.data!!
        val approvedData = approved.data!!
        assertEquals(QuickConnectStatus.Pending, pendingData.status)
        assertNull(pendingData.token)
        assertEquals(QuickConnectStatus.Approved, approvedData.status)
        assertEquals("igd_test", approvedData.token)
    }

    @Test
    fun encodesDeviceRequestsWithWireNames() {
        val initiate = json.encodeToString(
            QuickConnectInitiateRequest(
                deviceName = "Shield",
                platform = "android_tv",
                appVersion = "0.1.0",
            ),
        )
        assertEquals(
            """{"device_name":"Shield","platform":"android_tv","app_version":"0.1.0"}""",
            initiate,
        )

        val deviceLogin = json.encodeToString(
            DeviceLoginRequest(
                email = "a@b.c",
                password = "secret",
                deviceName = "Shield",
                platform = "android_tv",
            ),
        )
        assertEquals(
            """{"email":"a@b.c","password":"secret","device_name":"Shield","platform":"android_tv"}""",
            deviceLogin,
        )
    }

    @Test
    fun ignoresUnknownFieldsFromNewerServers() {
        val body = """
            {
              "error": false,
              "data": {"is_liked": true, "brand_new_field": "ignored"}
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<MovieLikeStatusData>>(body)

        assertTrue(envelope.data!!.isLiked)
    }

    /** `GET /tmdb/movies/in-theaters`: TMDB fields are plain values, not `sql.Null*` wrappers. */
    @Test
    fun decodesTheaterMoviesEnvelope() {
        val body = """
            {
              "error": false,
              "message": "movies in theaters",
              "data": {
                "movies": [
                  {
                    "id": 969681,
                    "title": "Spider-Man: Brand New Day",
                    "original_title": "Spider-Man: Brand New Day",
                    "overview": "Fighting crime full-time as Spider-Man.",
                    "release_date": "2026-07-31",
                    "poster_path": "/spidey.jpg",
                    "backdrop_path": "/spidey-backdrop.jpg",
                    "popularity": 1065.0058,
                    "vote_average": 7.869,
                    "vote_count": 1643,
                    "adult": false,
                    "original_language": "en",
                    "genre_ids": [878, 28, 12],
                    "video": false
                  }
                ]
              }
            }
        """.trimIndent()

        val movie = json.decodeFromString<ApiEnvelope<TheaterMoviesData>>(body).data!!.movies.single()

        assertEquals(969681, movie.id)
        assertEquals("Spider-Man: Brand New Day", movie.title)
        assertEquals("2026-07-31", movie.releaseDate)
        assertEquals("/spidey.jpg", movie.posterPath)
        assertEquals("/spidey-backdrop.jpg", movie.backdropPath)
        assertEquals(7.869, movie.voteAverage, 0.0)
    }

    /**
     * `GET /tmdb/movies/{id}`: the in-theaters detail payload, trimmed from a live response —
     * including the `genre_ids: null` the server really sends and the unmodelled fields
     * `ignoreUnknownKeys` has to absorb.
     */
    @Test
    fun decodesTmdbMovieEnvelope() {
        val body = """
            {
              "error": false,
              "message": "tmdb movie",
              "data": {
                "movie": {
                  "id": 969681,
                  "title": "Spider-Man: Brand New Day",
                  "original_title": "Spider-Man: Brand New Day",
                  "overview": "Fighting crime full-time as Spider-Man.",
                  "release_date": "2026-07-29",
                  "poster_path": "/spidey.jpg",
                  "backdrop_path": "/spidey-backdrop.jpg",
                  "popularity": 1831.2906,
                  "vote_average": 7.876,
                  "vote_count": 1829,
                  "adult": false,
                  "original_language": "en",
                  "genre_ids": null,
                  "video": false,
                  "runtime": 145,
                  "status": "Released",
                  "tagline": "A brand new day starts now.",
                  "budget": 225000000,
                  "revenue": 2021832000,
                  "homepage": "https://spidermanbrandnewday.movie",
                  "imdb_id": "tt22084616",
                  "production_companies": [
                    {"id": 420, "logo_path": "/marvel.png", "name": "Marvel Studios",
                     "origin_country": "US"}
                  ],
                  "genres": [{"id": 878, "name": "Science Fiction"}, {"id": 28, "name": "Action"}],
                  "credits": {
                    "cast": [
                      {"id": 1136406, "name": "Tom Holland",
                       "character": "Peter Parker / Spider-Man", "profile_path": "/holland.jpg",
                       "order": 0}
                    ],
                    "crew": [
                      {"id": 1223784, "name": "Destin Daniel Cretton", "job": "Director",
                       "department": "Directing", "profile_path": "/cretton.jpg"}
                    ]
                  },
                  "videos": {
                    "results": [
                      {"id": "6a81", "key": "yvxsgcXc59I", "name": "listen to mother",
                       "site": "YouTube", "type": "Featurette", "official": true}
                    ]
                  },
                  "release_dates": {
                    "results": [
                      {"iso_3166_1": "AE", "release_dates": [{"certification": ""}]},
                      {"iso_3166_1": "US", "release_dates": [{"certification": "PG-13"}]}
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val movie = json.decodeFromString<ApiEnvelope<TmdbMovieData>>(body).data!!.movie

        assertEquals(969681, movie.id)
        assertEquals("Spider-Man: Brand New Day", movie.title)
        assertEquals("2026-07-29", movie.releaseDate)
        assertEquals("/spidey.jpg", movie.posterPath)
        assertEquals("/spidey-backdrop.jpg", movie.backdropPath)
        assertEquals(7.876, movie.voteAverage, 0.0)
        assertEquals(145L, movie.runtime)
        assertEquals("Released", movie.status)
        assertEquals("A brand new day starts now.", movie.tagline)
        assertEquals(225000000L, movie.budget)
        assertEquals(2021832000L, movie.revenue)
        assertEquals("Marvel Studios", movie.productionCompanies?.single()?.name)
        assertEquals(listOf("Science Fiction", "Action"), movie.genres?.map { it.name })
        assertEquals("Tom Holland", movie.credits.cast?.single()?.name)
        assertEquals("Peter Parker / Spider-Man", movie.credits.cast?.single()?.character)
        assertEquals("Director", movie.credits.crew?.single()?.job)
        assertEquals("yvxsgcXc59I", movie.videos.results?.single()?.key)
        assertEquals("Featurette", movie.videos.results?.single()?.type)
        assertEquals(
            listOf("" to "AE", "PG-13" to "US"),
            movie.releaseDates.results.orEmpty().map {
                it.releaseDates?.single()?.certification to it.country
            },
        )
    }

    /** `GET /music/albums/latest`: a SimpleAlbum list under `albums`, no pagination envelope. */
    @Test
    fun decodesLatestAlbumsEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "albums": [
                  {
                    "id": 211,
                    "title": "You Get What You Give (Deluxe Version)",
                    "cover": {"String": "https://i.scdn.co/image/ab67.jpg", "Valid": true},
                    "musician": {"String": "Zac Brown Band", "Valid": true},
                    "year": {"Int64": 2010, "Valid": true}
                  },
                  {
                    "id": 42,
                    "title": "Untagged",
                    "cover": {"String": "", "Valid": false},
                    "musician": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false}
                  }
                ]
              }
            }
        """.trimIndent()

        val albums = json.decodeFromString<ApiEnvelope<LatestAlbumsData>>(body).data!!.albums

        assertEquals(listOf(211L, 42L), albums.map { it.id })
        assertEquals("https://i.scdn.co/image/ab67.jpg", albums[0].cover.orNull())
        assertEquals("Zac Brown Band", albums[0].musician.orNull())
        assertEquals(2010L, albums[0].year.orNull())
        // A `Valid: false` wrapper is absence, not the empty string the server pads it with.
        assertNull(albums[1].cover.orNull())
        assertNull(albums[1].musician.orNull())
        assertNull(albums[1].year.orNull())
    }

    /**
     * `GET /music/albums/details/{id}`: full album row, tracks and artists typed to the fields
     * the detail page reads. Durations are milliseconds on this wire — the raw values must come
     * through untouched.
     */
    @Test
    fun decodesAlbumDetailsEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "album": {
                  "id": 211,
                  "title": "Glacier Sessions",
                  "sort_title": "glacier sessions",
                  "spotify_id": {"String": "5uPKKfe1Y3PoLLmnrIhEIp", "Valid": true},
                  "spotify_popularity": {"Float64": 73.4, "Valid": true},
                  "musician": {"String": "Aurora Pines", "Valid": true},
                  "release_date": {"String": "2026-02-13", "Valid": true},
                  "year": {"Int64": 2026, "Valid": true},
                  "total_tracks": {"Int64": 3, "Valid": true},
                  "cover": {"String": "https://i.scdn.co/image/ab67.jpg", "Valid": true},
                  "created_at": "2026-02-14T01:02:03Z",
                  "updated_at": "2026-02-14T01:02:03Z"
                },
                "tracks": [
                  {
                    "id": 900, "title": "Northern Drift", "sort_title": "northern drift",
                    "file_path": "/music/a/01.flac", "file_name": "01.flac",
                    "container": "flac", "mime_type": "audio/flac", "codec": "flac",
                    "size": 31457280, "track_index": 1, "duration": 214000, "disc": 1,
                    "channels": "2", "channel_layout": "stereo", "bit_rate": 900000,
                    "profile": "",
                    "release_date": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false},
                    "composer": {"String": "", "Valid": false},
                    "copyright": {"String": "", "Valid": false},
                    "language": {"String": "", "Valid": false},
                    "album_id": {"Int64": 211, "Valid": true},
                    "musician_id": {"Int64": 4, "Valid": true},
                    "created_at": "2026-02-14T01:02:03Z", "updated_at": "2026-02-14T01:02:03Z"
                  },
                  {
                    "id": 901, "title": "Second Disc Opener", "sort_title": "second disc opener",
                    "file_path": "/music/a/d2-01.flac", "file_name": "d2-01.flac",
                    "container": "flac", "mime_type": "audio/flac", "codec": "flac",
                    "size": 41457280, "track_index": 1, "duration": 245000, "disc": 2,
                    "channels": "2", "channel_layout": "stereo", "bit_rate": 850000,
                    "profile": "",
                    "release_date": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false},
                    "composer": {"String": "", "Valid": false},
                    "copyright": {"String": "", "Valid": false},
                    "language": {"String": "", "Valid": false},
                    "album_id": {"Int64": 211, "Valid": true},
                    "musician_id": {"Int64": 4, "Valid": true},
                    "created_at": "2026-02-14T01:02:03Z", "updated_at": "2026-02-14T01:02:03Z"
                  }
                ],
                "artists": [
                  {
                    "id": 4, "name": "Aurora Pines",
                    "thumb": {"String": "", "Valid": false},
                    "spotify_id": {"String": "artist123", "Valid": true},
                    "sort_name": "aurora pines"
                  }
                ],
                "track_genres": [
                  {"track_id": 900, "genre_id": 12, "tag": "Ambient"},
                  {"track_id": 900, "genre_id": 13, "tag": "Electronic"}
                ],
                "album_genres": ["Ambient", "Electronic"],
                "total_duration": 657000
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<AlbumDetailsData>>(body).data!!

        assertEquals("Glacier Sessions", data.album.title)
        assertEquals(73.4, data.album.spotifyPopularity.orNull())
        assertEquals("Aurora Pines", data.album.musician.orNull())
        assertEquals("2026-02-13", data.album.releaseDate.orNull())
        assertEquals("https://i.scdn.co/image/ab67.jpg", data.album.cover.orNull())
        // Milliseconds on the wire, untouched by decode; formatting owns the /1000.
        assertEquals(214000L, data.tracks[0].duration)
        assertEquals(657000.0, data.totalDuration, 0.0)
        assertEquals(listOf(1L, 2L), data.tracks.map { it.disc })
        assertEquals("stereo", data.tracks[0].channelLayout)
        assertEquals(900000L, data.tracks[0].bitRate)
        // Artists arrive as full musician rows (additionalProperties on the wire); the fields
        // the page does not read must be ignored, not fatal.
        assertEquals("Aurora Pines", data.artists[0].name)
        assertNull(data.artists[0].thumb.orNull())
        assertEquals(listOf("Ambient", "Electronic"), data.trackGenres.map { it.tag })
        assertEquals(900L, data.trackGenres[0].trackId)
        assertEquals(listOf("Ambient", "Electronic"), data.albumGenres)
    }

    /**
     * `GET /music/tracks`: a `limit`/`offset` window. Each row carries its LEFT-JOINed album and
     * musician columns as wrappers, `duration` is integer milliseconds, and there is no
     * `file_path` — the `b8dc4c2` sync removed it (known-issues.md, instance nine).
     */
    @Test
    fun decodesTracksEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "tracks": [
                  {
                    "id": 900, "title": "Northern Drift", "duration": 214000,
                    "codec": "flac", "bit_rate": 900000,
                    "album_id": {"Int64": 211, "Valid": true},
                    "album_title": {"String": "Glacier Sessions", "Valid": true},
                    "album_cover": {"String": "https://i.scdn.co/image/ab67.jpg", "Valid": true},
                    "musician_id": {"Int64": 4, "Valid": true},
                    "musician_name": {"String": "Aurora Pines", "Valid": true}
                  },
                  {
                    "id": 901, "title": "1999", "duration": 180000,
                    "codec": "mp3", "bit_rate": 320000,
                    "album_id": {"Int64": 0, "Valid": false},
                    "album_title": {"String": "", "Valid": false},
                    "album_cover": {"String": "", "Valid": false},
                    "musician_id": {"Int64": 0, "Valid": false},
                    "musician_name": {"String": "", "Valid": false}
                  }
                ],
                "total": 1234, "offset": 50, "limit": 50, "has_more": true
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<TracksData>>(body).data!!

        assertEquals(listOf(900L, 901L), data.tracks.map { it.id })
        assertEquals(214000L, data.tracks[0].duration)
        assertEquals("Glacier Sessions", data.tracks[0].albumTitle.orNull())
        assertEquals(4L, data.tracks[0].musicianId.orNull())
        assertEquals("Aurora Pines", data.tracks[0].musicianName.orNull())
        assertNull(data.tracks[1].albumId.orNull())
        assertNull(data.tracks[1].albumCover.orNull())
        assertNull(data.tracks[1].musicianName.orNull())
        assertEquals(1234L, data.total)
        assertEquals(50L, data.offset)
        assertEquals(50L, data.limit)
        assertTrue(data.hasMore)
    }

    /** `GET /music/tracks/shuffle`: the same rows as the track list, no paging counts at all. */
    @Test
    fun decodesShuffleTracksEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "tracks": [
                  {
                    "id": 42, "title": "Example Track", "duration": 213000,
                    "codec": "flac", "bit_rate": 921600,
                    "album_id": {"Int64": 7, "Valid": true},
                    "album_title": {"String": "Example Album", "Valid": true},
                    "album_cover": {"String": "/covers/example.jpg", "Valid": true},
                    "musician_id": {"Int64": 3, "Valid": true},
                    "musician_name": {"String": "Example Musician", "Valid": true}
                  }
                ]
              }
            }
        """.trimIndent()

        val tracks = json.decodeFromString<ApiEnvelope<ShuffleTracksData>>(body).data!!.tracks

        assertEquals(42L, tracks.single().id)
        assertEquals("Example Musician", tracks.single().musicianName.orNull())
    }

    /**
     * `GET /music/musicians`: page-based like albums. A row has no `sort_name` — the server sorts
     * by it but does not send it (known-issues.md, instance ten).
     */
    @Test
    fun decodesMusiciansEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "musicians": [
                  {
                    "id": 4, "name": "Aurora Pines",
                    "thumb": {"String": "https://i.scdn.co/image/aurora.jpg", "Valid": true},
                    "album_count": 3, "track_count": 40
                  },
                  {
                    "id": 5, "name": "Untagged",
                    "thumb": {"String": "", "Valid": false},
                    "album_count": 0, "track_count": 2
                  }
                ],
                "total": 60, "page": 1, "per_page": 48, "total_pages": 2
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MusiciansData>>(body).data!!

        assertEquals(listOf("Aurora Pines", "Untagged"), data.musicians.map { it.name })
        assertEquals("https://i.scdn.co/image/aurora.jpg", data.musicians[0].thumb.orNull())
        assertNull(data.musicians[1].thumb.orNull())
        assertEquals(3L, data.musicians[0].albumCount)
        assertEquals(40L, data.musicians[0].trackCount)
        assertEquals(60L, data.total)
        assertEquals(2L, data.totalPages)
    }

    /**
     * `GET /music/musicians/{id}`: the full musician row, a discography with per-album track
     * counts, and tracks that carry their album but no musician columns. Durations are ms.
     */
    @Test
    fun decodesMusicianDetailsEnvelope() {
        val body = """
            {
              "error": false,
              "data": {
                "musician": {
                  "id": 4, "name": "Aurora Pines", "sort_name": "aurora pines",
                  "summary": {"String": "Formed in 2019.", "Valid": true},
                  "spotify_id": {"String": "artist123", "Valid": true},
                  "spotify_popularity": {"Float64": 61.5, "Valid": true},
                  "spotify_followers": {"Int64": 120000, "Valid": true},
                  "thumb": {"String": "", "Valid": false},
                  "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z"
                },
                "albums": [
                  {
                    "id": 211, "title": "Glacier Sessions",
                    "cover": {"String": "https://i.scdn.co/image/ab67.jpg", "Valid": true},
                    "year": {"Int64": 2026, "Valid": true},
                    "release_date": {"String": "2026-02-13", "Valid": true},
                    "track_count": 12
                  }
                ],
                "tracks": [
                  {
                    "id": 900, "title": "Northern Drift", "duration": 214000,
                    "codec": "flac", "bit_rate": 900000,
                    "album_id": {"Int64": 211, "Valid": true},
                    "album_title": {"String": "Glacier Sessions", "Valid": true},
                    "album_cover": {"String": "https://i.scdn.co/image/ab67.jpg", "Valid": true}
                  }
                ],
                "genres": ["Ambient", "Electronic"],
                "total_duration": 214000
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MusicianDetailsData>>(body).data!!

        assertEquals("Aurora Pines", data.musician.name)
        assertEquals("Formed in 2019.", data.musician.summary.orNull())
        assertEquals(61.5, data.musician.spotifyPopularity.orNull())
        assertEquals(120000L, data.musician.spotifyFollowers.orNull())
        assertNull(data.musician.thumb.orNull())
        assertEquals(214000L, data.tracks.single().duration)
        assertEquals(211L, data.tracks.single().albumId.orNull())
        assertEquals(listOf("Ambient", "Electronic"), data.genres)
        assertEquals(214000.0, data.totalDuration, 0.0)
    }

    /** `GET /music/tracks/liked-ids`, `POST /music/tracks/{id}/like`, `GET /music/stats`. */
    @Test
    fun decodesTrackLikeAndStatsEnvelopes() {
        val liked = json.decodeFromString<ApiEnvelope<LikedTrackIdsData>>(
            """{"error":false,"data":{"liked_track_ids":[3,5,900]}}""",
        ).data!!
        val toggle = json.decodeFromString<ApiEnvelope<TrackLikeToggleData>>(
            """{"error":false,"data":{"track_id":900,"is_liked":false}}""",
        ).data!!
        val stats = json.decodeFromString<ApiEnvelope<MusicStats>>(
            """{"error":false,"data":{"total_albums":12,"total_tracks":150,"total_musicians":9}}""",
        ).data!!

        assertEquals(listOf(3L, 5L, 900L), liked.likedTrackIds)
        assertEquals(900L, toggle.trackId)
        assertFalse(toggle.isLiked)
        assertEquals(12L, stats.totalAlbums)
        assertEquals(150L, stats.totalTracks)
        assertEquals(9L, stats.totalMusicians)
    }

    // The models below were generated from an older openapi.json and kept fields the contract has
    // since dropped or moved. Each payload carries exactly the current schema's required keys and
    // nothing else, so a future spec sync that drops a field fails here instead of shipping.

    /** `POST /notifications`: the reply carries no payload, only the shared message envelope. */
    @Test
    fun decodesCreatedNotificationAsMessageResponse() {
        val body = """{"error": false, "message": "notification created"}"""

        val response = json.decodeFromString<MessageResponse>(body)

        assertFalse(response.error)
        assertEquals("notification created", response.message)
    }

    @Test
    fun orNullIfBlankTreatsBlankAndInvalidAlikeAndKeepsText() {
        assertNull(SqlNullString(value = "   ", valid = true).orNullIfBlank())
        assertNull(SqlNullString(value = "hidden", valid = false).orNullIfBlank())
        assertEquals("PG-13", SqlNullString(value = "PG-13", valid = true).orNullIfBlank())
    }

    /**
     * `GET /movies/details/{id}`: the movie carries only its metadata (no file fields), and the
     * related lists are typed in the spec; a key the app does not map (`unexpected`) still
     * decodes.
     */
    @Test
    fun decodesMovieDetailsRelatedLists() {
        val body = """
            {
              "error": false,
              "data": {
                "movie": {
                  "id": 406, "title": "The Prestige", "adult": false,
                  "tmdb_id": {"Int64": 1124, "Valid": true},
                  "imdb_id": {"String": "tt0482571", "Valid": true},
                  "poster_path": {"String": "/p.jpg", "Valid": true},
                  "backdrop_path": {"String": "", "Valid": false},
                  "language": {"String": "en", "Valid": true},
                  "year": {"Int64": 2006, "Valid": true},
                  "release_date": {"String": "2006-10-19", "Valid": true},
                  "overview": {"String": "Two rival magicians.", "Valid": true},
                  "tag_line": {"String": "", "Valid": false},
                  "certification": {"String": "PG-13", "Valid": true},
                  "critic_rating": {"Float64": 8.2, "Valid": true},
                  "audience_rating": {"Float64": 0, "Valid": false},
                  "revenue": {"Float64": 109676311, "Valid": true},
                  "budget": {"Float64": 40000000, "Valid": true},
                  "run_time": {"Int64": 130, "Valid": true},
                  "duration": {"Float64": 7800.5, "Valid": true}
                },
                "cast": [
                  {"id": 24147, "character": "Robert Angier", "cast_order": 0,
                   "artist_name": "Hugh Jackman",
                   "artist_profile": {"String": "/hj.jpg", "Valid": true}, "unexpected": 1}
                ],
                "crew": [
                  {"id": 68444, "job": "Director", "department": "Directing",
                   "artist_name": "Christopher Nolan"}
                ],
                "genres": [{"id": 5, "tag": "Drama"}],
                "production_companies": [{"id": 266, "name": "Syncopy"}],
                "extra_videos": [
                  {"id": 6390, "title": "The Prestige - Trailer", "key": "ijXruSzfGEc",
                   "type": "trailer", "site": "youtube"}
                ]
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MovieDetailsData>>(body).data!!

        assertEquals("The Prestige", data.movie.title)
        assertEquals(2006L, data.movie.year?.orNull())
        assertEquals(7800.5, data.movie.duration?.orNull()!!, 0.0)
        assertNull(data.movie.tagLine?.orNull())
        val cast = data.cast.single()
        assertEquals("Hugh Jackman", cast.artistName)
        assertEquals("Robert Angier", cast.character)
        assertEquals(0L, cast.castOrder)
        assertEquals("/hj.jpg", cast.artistProfile?.orNull())
        val crew = data.crew.single()
        assertEquals("Director", crew.job)
        assertEquals("Directing", crew.department)
        assertEquals("Christopher Nolan", crew.artistName)
        assertEquals("Drama", data.genres.single().tag)
        assertEquals("Syncopy", data.productionCompanies.single().name)
        val video = data.extraVideos.single()
        assertEquals("ijXruSzfGEc", video.key)
        assertEquals("youtube", video.site)
        assertEquals("trailer", video.type)
    }

    /**
     * `GET /movies/{id}/technical-details`: the file subset, streams and chapters are all typed
     * in the spec; only the container media type of the file is mapped.
     */
    @Test
    fun decodesTechnicalDetailsStreams() {
        val body = """
            {
              "error": false,
              "data": {
                "movie": {"file_name": "p.mkv", "size": 1, "container": "mkv",
                          "mime_type": "video/x-matroska",
                          "run_time": {"Int64": 130, "Valid": true},
                          "duration": {"Float64": 7800.5, "Valid": true}},
                "video_streams": [
                  {"id": 406, "movie_id": 406, "stream_index": 0, "codec": "hevc",
                   "codec_profile": {"String": "Main 10", "Valid": true},
                   "codec_level": {"Int64": 153, "Valid": true}, "bit_rate": 0,
                   "width": 3840, "height": 1600,
                   "coded_width": {"Int64": 3840, "Valid": true},
                   "coded_height": {"Int64": 1600, "Valid": true},
                   "aspect_ratio": {"String": "12:5", "Valid": true}, "frame_rate": 23.976,
                   "avg_frame_rate": {"String": "24000/1001", "Valid": true},
                   "bit_depth": {"Int64": 0, "Valid": false},
                   "pixel_format": {"String": "yuv420p10le", "Valid": true},
                   "color_range": {"String": "tv", "Valid": true},
                   "color_space": {"String": "bt2020nc", "Valid": true},
                   "color_primaries": {"String": "bt2020", "Valid": true},
                   "color_transfer": {"String": "smpte2084", "Valid": true},
                   "field_order": {"String": "", "Valid": false},
                   "rotation": {"Int64": 0, "Valid": false},
                   "language": {"String": "", "Valid": false},
                   "title": {"String": "", "Valid": false}}
                ],
                "audio_streams": [
                  {"id": 636, "movie_id": 406, "stream_index": 1, "codec": "dts",
                   "codec_profile": {"String": "DTS-HD MA", "Valid": true}, "bit_rate": 0,
                   "sample_rate": {"Int64": 48000, "Valid": true}, "channels": 6,
                   "channel_layout": {"String": "5.1(side)", "Valid": true},
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "", "Valid": false}, "is_default": true}
                ],
                "subtitles": [
                  {"id": 2351, "movie_id": 406, "stream_index": 2, "codec": "subrip",
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "Stripped SRT", "Valid": true},
                   "is_forced": false, "is_default": false}
                ],
                "chapters": [
                  {"id": 6675, "title": "00:03:13.026", "start_time": 193,
                   "thumb": {"String": "", "Valid": false},
                   "movie_id": 406}
                ]
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MovieTechnicalDetailsData>>(body).data!!

        val video = data.videoStreams.single()
        assertEquals(3840L, video.width)
        assertEquals(1600L, video.height)
        assertEquals("smpte2084", video.colorTransfer?.orNull())
        assertNull(video.bitDepth?.orNull())
        val audio = data.audioStreams.single()
        assertEquals(6L, audio.channels)
        assertEquals("5.1(side)", audio.channelLayout?.orNull())
        assertTrue(audio.isDefault)
        val subtitle = data.subtitles.single()
        assertEquals("subrip", subtitle.codec)
        assertFalse(subtitle.isForced)
        val chapter = data.chapters.single()
        assertEquals(193L, chapter.startTime)
        assertNull(chapter.thumb?.orNull())
        assertEquals("video/x-matroska", data.movie.mimeType)
    }
}
