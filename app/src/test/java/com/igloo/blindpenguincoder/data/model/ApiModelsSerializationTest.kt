package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAuthUserEnvelope() {
        val body = """
            {
              "error": false,
              "message": "user found",
              "data": {
                "user": {
                  "id": 7,
                  "name": "Jose",
                  "email": "jose@example.com",
                  "is_admin": true,
                  "avatar": null,
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-02T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<ApiEnvelope<AuthUserData>>(body)

        assertFalse(envelope.error)
        val user = envelope.data!!.user
        assertEquals(7L, user.id)
        assertTrue(user.isAdmin)
        assertNull(user.avatar)
    }

    @Test
    fun decodesErrorEnvelopeWithoutData() {
        val body = """{"error": true, "message": "invalid credentials"}"""

        val envelope = json.decodeFromString<ApiEnvelope<AuthUserData>>(body)

        assertTrue(envelope.error)
        assertEquals("invalid credentials", envelope.message)
        assertNull(envelope.data)
    }

    @Test
    fun decodesMoviesLibraryWithSqlNullFieldsAndSort() {
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

        val data = envelope.data!!
        assertEquals(SortOrder.Ascending, data.sort)
        val movie = data.movies.single()
        assertEquals("/arrival.jpg", movie.posterPath.orNull())
        assertNull(movie.year.orNull())
        assertEquals("PG-13", movie.certification?.orNull())
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
            UpdateMovieWatchProgressRequest(progressSec = 30.0, durationSec = 7200.0),
        )
        assertEquals("""{"progress_sec":30.0,"duration_sec":7200.0}""", progress)

        val login = json.encodeToString(LoginRequest(email = "a@b.c", password = "secret"))
        assertEquals("""{"email":"a@b.c","password":"secret"}""", login)
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
}
