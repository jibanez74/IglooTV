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

    @Test
    fun decodesContinueWatchingMoviesEnvelope() {
        val body = """
            {
              "error": false,
              "message": "continue watching",
              "data": {
                "movies": [
                  {
                    "id": 5,
                    "title": "Heat",
                    "poster_path": {"String": "/heat.jpg", "Valid": true},
                    "year": {"Int64": 1995, "Valid": true},
                    "progress_sec": 1800.5,
                    "duration_sec": 10200
                  },
                  {
                    "id": 6,
                    "title": "Untitled",
                    "poster_path": {"String": "", "Valid": false},
                    "year": {"Int64": 0, "Valid": false},
                    "progress_sec": 45,
                    "duration_sec": 5400
                  }
                ]
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<ContinueWatchingMoviesData>>(body)

        val movies = envelope.data!!.movies
        assertEquals("/heat.jpg", movies[0].posterPath.orNull())
        assertEquals(1995L, movies[0].year.orNull())
        assertEquals(1800.5, movies[0].progressSec, 0.0)
        assertEquals(10200.0, movies[0].durationSec, 0.0)
        assertNull(movies[1].posterPath.orNull())
        assertNull(movies[1].year.orNull())
        assertEquals(45.0, movies[1].progressSec, 0.0)
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

        val envelope = json.decodeFromString<ApiEnvelope<MovieWatchProgress>>(body)

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
            UpdateMovieWatchProgressRequest(
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
    fun decodesUntypedTrackPayloadAsJsonObject() {
        val body = """
            {
              "error": false,
              "data": {
                "track": {"id": 12, "title": "Song", "unexpected_field": {"nested": true}}
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<TrackDetailsData>>(body)

        assertEquals("12", envelope.data!!.track["id"].toString())
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

    // The models below were generated from an older openapi.json and kept fields the contract has
    // since dropped or moved. Each payload carries exactly the current schema's required keys and
    // nothing else, so a future spec sync that drops a field fails here instead of shipping.

    /** `GET /movie-playlists/{id}`: no `folder_id` — the column is gone from the backend schema. */
    @Test
    fun decodesMoviePlaylistDetailWithoutFolderId() {
        val body = """
            {
              "error": false,
              "data": {
                "playlist": {
                  "id": 3,
                  "user_id": 7,
                  "name": "Saturday night",
                  "description": {"String": "", "Valid": false},
                  "cover_image": {"String": "/api/static/playlists/3.jpg", "Valid": true},
                  "is_public": true,
                  "movie_id": {"Int64": 0, "Valid": false},
                  "content_type": "movie",
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-02T00:00:00Z"
                },
                "movie_count": 12,
                "is_owner": true,
                "can_edit": true,
                "collaborators": []
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MoviePlaylistDetailData>>(body).data!!

        assertEquals(3L, data.playlist.id)
        assertEquals("movie", data.playlist.contentType)
        assertNull(data.playlist.description.orNull())
        assertEquals("/api/static/playlists/3.jpg", data.playlist.coverImage.orNull())
        assertEquals(12L, data.movieCount)
    }

    /** `GET /movie-playlists`: summaries carry list metadata but still no `folder_id`. */
    @Test
    fun decodesMoviePlaylistSummariesWithoutFolderId() {
        val body = """
            {
              "error": false,
              "data": {
                "playlists": [
                  {
                    "id": 3,
                    "user_id": 7,
                    "name": "Saturday night",
                    "description": {"String": "Popcorn films", "Valid": true},
                    "cover_image": {"String": "", "Valid": false},
                    "is_public": false,
                    "movie_id": {"Int64": 55, "Valid": true},
                    "content_type": "movie",
                    "created_at": "2026-01-01T00:00:00Z",
                    "updated_at": "2026-01-02T00:00:00Z",
                    "movie_count": 4,
                    "is_owner": true,
                    "can_edit": true
                  }
                ]
              }
            }
        """.trimIndent()

        val playlist = json.decodeFromString<ApiEnvelope<MoviePlaylistsData>>(body).data!!.playlists.single()

        assertEquals("Popcorn films", playlist.description.orNull())
        assertEquals(55L, playlist.movieId.orNull())
        assertEquals(4L, playlist.movieCount)
    }

    /** `POST /notifications`: the response has no `user_id`; the backend never emitted one. */
    @Test
    fun decodesCreatedNotificationWithoutUserId() {
        val body = """
            {
              "error": false,
              "data": {
                "notification": {
                  "id": 11,
                  "created_by_user_id": 7,
                  "title": "movie_request",
                  "message": "Please add Heat",
                  "is_admin": true,
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-01T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val notification = json.decodeFromString<ApiEnvelope<CreateNotificationData>>(body).data!!.notification

        assertEquals(11L, notification.id)
        assertEquals(NotificationTitle.MovieRequest, notification.title)
        assertTrue(notification.isAdmin)
    }

    /** `GET /notifications`: list items are keyed by `created_by_name`, not `user_id`. */
    @Test
    fun decodesNotificationListWithoutUserId() {
        val body = """
            {
              "error": false,
              "data": {
                "notifications": [
                  {
                    "id": 11,
                    "title": "album_request",
                    "message": "Please add Rumours",
                    "is_admin": false,
                    "is_read": false,
                    "created_by_name": "Jose",
                    "created_at": "2026-01-01T00:00:00Z"
                  }
                ],
                "unread_count": 1
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<NotificationsListData>>(body).data!!

        assertEquals(1L, data.unreadCount)
        val item = data.notifications.single()
        assertEquals(NotificationTitle.AlbumRequest, item.title)
        assertEquals("Jose", item.createdByName)
        assertFalse(item.isRead)
    }

    /**
     * `POST /{music,movies}/playlists/{id}/collaborators`: the mutation echo has no user join,
     * so it carries neither `username` nor `email`. Decoding it as the list-shaped
     * `PlaylistCollaborator` threw `MissingFieldException` outright.
     */
    @Test
    fun decodesCollaboratorMutationWithoutTheUserJoin() {
        val body = """
            {
              "error": false,
              "data": {
                "collaborator": {
                  "id": 9,
                  "playlist_id": 3,
                  "user_id": 7,
                  "can_edit": true,
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-02T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val collaborator =
            json.decodeFromString<ApiEnvelope<PlaylistCollaboratorMutationData>>(body)
                .data!!
                .collaborator

        assertEquals(9L, collaborator.id)
        assertEquals(3L, collaborator.playlistId)
        assertEquals(7L, collaborator.userId)
        assertTrue(collaborator.canEdit)
    }

    /** `GET /{music,movies}/playlists/{id}/collaborators`: the list shape does join the user. */
    @Test
    fun decodesCollaboratorListWithTheUserJoin() {
        val body = """
            {
              "error": false,
              "data": {
                "collaborators": [
                  {
                    "id": 9,
                    "playlist_id": 3,
                    "user_id": 7,
                    "can_edit": false,
                    "created_at": "2026-01-01T00:00:00Z",
                    "updated_at": "2026-01-02T00:00:00Z",
                    "username": "Jose",
                    "email": "jose@example.com"
                  }
                ]
              }
            }
        """.trimIndent()

        val collaborator =
            json.decodeFromString<ApiEnvelope<PlaylistCollaboratorsData>>(body)
                .data!!
                .collaborators
                .single()

        assertEquals("Jose", collaborator.username)
        assertEquals("jose@example.com", collaborator.email)
        assertFalse(collaborator.canEdit)
    }

    /** `GET /settings/general`: hardware acceleration and upload cap moved to the playback routes. */
    @Test
    fun decodesGeneralSettingsWithoutPlaybackOnlyFields() {
        val body = """
            {
              "error": false,
              "data": {
                "settings": {
                  "tmdb_key": "key",
                  "immich_base_url": null,
                  "immich_api_key": null,
                  "jellyfin_base_url": null,
                  "jellyfin_api_key": null,
                  "spotify_client_id": "spotify-id",
                  "spotify_client_secret": null,
                  "enable_watcher": true,
                  "download_images": false,
                  "static_dir": "/srv/igloo/static",
                  "transcode_dir": "/srv/igloo/transcode"
                }
              }
            }
        """.trimIndent()

        val settings = json.decodeFromString<ApiEnvelope<GeneralSettingsData>>(body).data!!.settings

        assertEquals("key", settings.tmdbKey)
        assertNull(settings.immichBaseUrl)
        assertTrue(settings.enableWatcher)
        assertEquals("/srv/igloo/transcode", settings.transcodeDir)
        // Absent from the contract, so absent from the request the app would send back.
        assertEquals(
            """{"tmdb_key":"key","immich_base_url":"","immich_api_key":"","jellyfin_base_url":"",""" +
                """"jellyfin_api_key":"","spotify_client_id":"spotify-id","spotify_client_secret":"",""" +
                """"enable_watcher":true,"download_images":false,"static_dir":"/srv/igloo/static",""" +
                """"transcode_dir":"/srv/igloo/transcode"}""",
            json.encodeToString(
                UpdateGeneralSettingsRequest(
                    tmdbKey = "key",
                    immichBaseUrl = "",
                    immichApiKey = "",
                    jellyfinBaseUrl = "",
                    jellyfinApiKey = "",
                    spotifyClientId = "spotify-id",
                    spotifyClientSecret = "",
                    enableWatcher = true,
                    downloadImages = false,
                    staticDir = "/srv/igloo/static",
                    transcodeDir = "/srv/igloo/transcode",
                ),
            ),
        )
    }

    /** `GET /settings/playback`: this is where `hardware_acceleration_device` now lives. */
    @Test
    fun decodesPlaybackSettingsWithHardwareAccelerationDevice() {
        val body = """
            {
              "error": false,
              "data": {
                "settings": {
                  "profiles": [
                    {"id": "1080p", "label": "1080p", "height": 1080, "video_mbps": 8}
                  ],
                  "preferred_profile": null,
                  "download_mbps": 50.5,
                  "server_upload_mbps": null,
                  "hardware_acceleration_device": "nvidia",
                  "is_admin": true,
                  "preferred_audio_language": "eng",
                  "preferred_subtitle_language": "off"
                }
              }
            }
        """.trimIndent()

        val settings = json.decodeFromString<ApiEnvelope<PlaybackSettingsData>>(body).data!!.settings

        assertEquals(HardwareAccelerationDevice.Nvidia, settings.hardwareAccelerationDevice)
        assertEquals(1080, settings.profiles.single().height)
        assertNull(settings.preferredProfile)
        assertNull(settings.serverUploadMbps)
        assertEquals(50.5, settings.downloadMbps!!, 0.0)
    }

    /** A partial playback update sends only the keys the user actually changed. */
    @Test
    fun encodesPlaybackSettingsUpdateWithoutUntouchedFields() {
        val request = json.encodeToString(
            UpdatePlaybackSettingsRequest(hardwareAccelerationDevice = HardwareAccelerationDevice.Intel),
        )

        assertEquals("""{"hardware_acceleration_device":"intel"}""", request)
    }

    @Test
    fun orNullIfBlankTreatsBlankAndInvalidAlikeAndKeepsText() {
        assertNull(SqlNullString(value = "   ", valid = true).orNullIfBlank())
        assertNull(SqlNullString(value = "hidden", valid = false).orNullIfBlank())
        assertEquals("PG-13", SqlNullString(value = "PG-13", valid = true).orNullIfBlank())
    }

    /**
     * `GET /movies/details/{id}` related lists: the spec leaves them `additionalProperties:
     * true`, so the typed models were pinned against live responses and must tolerate keys
     * they do not map (`unexpected` below).
     */
    @Test
    fun decodesMovieDetailsRelatedLists() {
        val body = """
            {
              "error": false,
              "data": {
                "movie": {
                  "id": 406, "title": "The Prestige", "file_path": "/m/p.mkv",
                  "file_name": "p.mkv", "size": 1, "container": "mkv",
                  "mime_type": "video/x-matroska", "adult": false,
                  "created_at": "2026-01-01", "updated_at": "2026-01-01"
                },
                "cast": [
                  {"id": 24147, "movie_id": 406, "artist_id": 1230, "character": "Robert Angier",
                   "cast_order": 0, "artist_name": "Hugh Jackman",
                   "artist_profile": {"String": "/hj.jpg", "Valid": true}, "unexpected": 1}
                ],
                "crew": [
                  {"id": 68444, "movie_id": 406, "artist_id": 16036, "job": "Director",
                   "department": "Directing", "artist_name": "Christopher Nolan",
                   "artist_profile": {"String": "", "Valid": false}}
                ],
                "genres": [{"id": 5, "tag": "Drama"}],
                "production_companies": [
                  {"id": 266, "name": "Syncopy", "tmdb_id": 9996,
                   "logo": {"String": "/s.png", "Valid": true},
                   "country": {"String": "GB", "Valid": true}}
                ],
                "extra_videos": [
                  {"id": 6390, "title": "The Prestige - Trailer",
                   "external_id": {"String": "58f6", "Valid": true}, "key": "ijXruSzfGEc",
                   "type": "trailer", "site": "youtube", "official": true,
                   "created_at": "2026-08-14 00:52:18", "updated_at": "2026-08-14 00:52:18"}
                ]
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<ApiEnvelope<MovieDetailsData>>(body).data!!

        val cast = data.cast.single()
        assertEquals("Hugh Jackman", cast.artistName)
        assertEquals("Robert Angier", cast.character)
        assertEquals(0L, cast.castOrder)
        assertEquals("/hj.jpg", cast.artistProfile?.orNull())
        val crew = data.crew.single()
        assertEquals("Director", crew.job)
        assertEquals("Directing", crew.department)
        assertNull(crew.artistProfile?.orNull())
        assertEquals("Drama", data.genres.single().tag)
        assertEquals("Syncopy", data.productionCompanies.single().name)
        assertEquals("GB", data.productionCompanies.single().country?.orNull())
        val video = data.extraVideos.single()
        assertEquals("ijXruSzfGEc", video.key)
        assertEquals("youtube", video.site)
        assertTrue(video.official)
    }

    /** `GET /movies/{id}/technical-details`: streams and chapters are typed in the spec. */
    @Test
    fun decodesTechnicalDetailsStreams() {
        val body = """
            {
              "error": false,
              "data": {
                "movie": {"id": 406},
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
                   "title": {"String": "", "Valid": false},
                   "created_at": "2026-08-14", "updated_at": "2026-08-14"}
                ],
                "audio_streams": [
                  {"id": 636, "movie_id": 406, "stream_index": 1, "codec": "dts",
                   "codec_profile": {"String": "DTS-HD MA", "Valid": true}, "bit_rate": 0,
                   "sample_rate": {"Int64": 48000, "Valid": true}, "channels": 6,
                   "channel_layout": {"String": "5.1(side)", "Valid": true},
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "", "Valid": false}, "is_default": true,
                   "created_at": "2026-08-14", "updated_at": "2026-08-14"}
                ],
                "subtitles": [
                  {"id": 2351, "movie_id": 406, "stream_index": 2, "codec": "subrip",
                   "language": {"String": "eng", "Valid": true},
                   "title": {"String": "Stripped SRT", "Valid": true},
                   "is_forced": false, "is_default": false,
                   "created_at": "2026-08-14", "updated_at": "2026-08-14"}
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
        // The payload's `movie_id` is a plain number where the spec promises a SqlNullInt64
        // object; the model leaves it unmapped, so the mismatch cannot fail the decode.
    }
}
