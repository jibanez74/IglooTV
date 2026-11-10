package com.igloo.blindpenguincoder.data.model

import com.igloo.blindpenguincoder.core.network.IglooJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
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

        val user = envelope.data!!.user
        assertEquals(7L, user.id)
        assertEquals("Jose", user.name)
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

        assertNull(envelope.data)
    }

    @Test
    fun encodesRequestsWithWireNames() {
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
                  "last_used_at": "2026-07-01T00:01:00Z"
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
                      "last_used_at": "2026-07-01T00:01:00Z"
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

        val envelope = json.decodeFromString<ApiEnvelope<LikedData>>(body)

        assertTrue(envelope.data!!.isLiked)
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

    @Test
    fun orNullIfBlankTreatsBlankAndInvalidAlikeAndKeepsText() {
        assertNull(SqlNullString(value = "   ", valid = true).orNullIfBlank())
        assertNull(SqlNullString(value = "hidden", valid = false).orNullIfBlank())
        assertEquals("PG-13", SqlNullString(value = "PG-13", valid = true).orNullIfBlank())
    }

    /**
     * `GET /movies/details/{id}`: the file's probed `duration`, the player's fallback length.
     * `movieDetailsJson` does not send it, so MovieRepositoryTest cannot assert it.
     */
    @Test
    fun decodesMovieDetailsFileDuration() {
        val body = """
            {"error": false, "data": {
              "movie": {"id": 406, "title": "The Prestige",
                        "duration": {"Float64": 7800.5, "Valid": true}},
              "cast": [], "crew": [], "genres": [], "production_companies": [], "extra_videos": []
            }}
        """.trimIndent()

        val movie = json.decodeFromString<ApiEnvelope<MovieDetailsData>>(body).data!!.movie

        assertEquals(7800.5, movie.duration?.orNull()!!, 0.0)
    }
}
