package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomRequest
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.model.WatchRoomPlaybackState
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.WATCH_ROOM_TRACKS_CHANGED_MESSAGE
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRoomRepositoryTest {

    @Test
    fun `watch rooms hits the contract path with the bearer token and no query`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchRoomsJson())
        }
        http.profiles.setPending("igd_test")

        val result = http.watchRoomRepository.watchRooms()

        val captured = requireNotNull(request)
        assertEquals("/api/watch-rooms", captured.url.encodedPath)
        assertTrue(captured.url.parameters.isEmpty())
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        assertEquals(emptyList<Any>(), (result as ApiResult.Success).value)
    }

    @Test
    fun `a room decodes its movie, its owner and its members in order`() = runTest {
        val maria = userSummaryJson(id = 1, name = "Maria", avatar = "/api/static/avatars/1.jpg")
        val jorge = userSummaryJson(id = 2, name = "Jorge", avatar = null)
        val http = TestHttp {
            jsonResponse(
                watchRoomsJson(
                    watchRoomJson(id = 7, movieTitle = "Heat", owner = maria, members = listOf(maria, jorge)),
                    // A movie the scanner never matched has no poster.
                    watchRoomJson(id = 8, movieTitle = "Unmatched", moviePoster = null, isOwner = false),
                ),
            )
        }

        val rooms = (http.watchRoomRepository.watchRooms() as ApiResult.Success).value

        assertEquals(listOf(7L, 8L), rooms.map { it.id })
        val first = rooms.first()
        assertEquals("Heat", first.movieTitle)
        assertEquals("/heat.jpg", first.moviePoster)
        assertEquals("Maria", first.owner.name)
        assertEquals(listOf("Maria", "Jorge"), first.members.map { it.name })
        assertEquals("/api/static/avatars/1.jpg", first.members.first().avatar)
        assertNull(first.members.last().avatar)
        assertTrue(first.isOwner)
        assertNull(rooms.last().moviePoster)
        assertFalse(rooms.last().isOwner)
    }

    @Test
    fun `a room's details carry its movie, mode and fixed tracks`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchRoomDetailJson(id = 7, movieId = 42, playbackMode = "1080p_8mbps", audioTrack = 1, subtitleTrack = 2))
        }

        val room = (http.watchRoomRepository.watchRoom(7) as ApiResult.Success).value

        assertEquals("/api/watch-rooms/7", requireNotNull(request).url.encodedPath)
        assertEquals(42L, room.movieId)
        assertEquals("1080p_8mbps", room.playbackMode)
        assertEquals(1L, room.audioTrack)
        assertEquals(2L, room.subtitleTrack)
    }

    @Test
    fun `a room without subtitles decodes a null track`() = runTest {
        val http = TestHttp { jsonResponse(watchRoomDetailJson(subtitleTrack = null)) }

        val room = (http.watchRoomRepository.watchRoom(1) as ApiResult.Success).value

        assertNull(room.subtitleTrack)
    }

    @Test
    fun `a room the caller cannot open is the server's refusal`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"access denied"}""", HttpStatusCode.Forbidden)
        }

        val result = http.watchRoomRepository.watchRoom(9)

        assertEquals(AppError.Api("access denied", 403), (result as ApiResult.Failure).error)
    }

    @Test
    fun `create posts the room's wire body and returns the new id`() = runTest {
        var request: HttpRequestData? = null
        var body = ""
        val http = TestHttp {
            request = it
            body = it.body.toByteArray().decodeToString()
            jsonResponse("""{"error":false,"data":{"room_id":12}}""", HttpStatusCode.Created)
        }

        val result = http.watchRoomRepository.create(
            CreateWatchRoomRequest(
                movieId = 42,
                mode = "remux",
                audioTrack = 1,
                subtitleTrack = null,
                invitedUserIds = listOf(2, 3),
            ),
        )

        val captured = requireNotNull(request)
        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/watch-rooms", captured.url.encodedPath)
        // A null subtitle is left out, which the server reads as "no subtitles".
        assertEquals(
            JsonObject(
                mapOf(
                    "movie_id" to JsonPrimitive(42),
                    "mode" to JsonPrimitive("remux"),
                    "audio_track" to JsonPrimitive(1),
                    "invited_user_ids" to Json.parseToJsonElement("[2,3]"),
                ),
            ),
            Json.parseToJsonElement(body).jsonObject,
        )
        assertEquals(12L, (result as ApiResult.Success).value)
    }

    @Test
    fun `a full server's refusal to create keeps its Retry-After`() = runTest {
        val http = TestHttp {
            respond(
                content = """{"error":true,"message":"the server has no room to start this stream right now"}""",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.RetryAfter to listOf("5"),
                ),
            )
        }

        val result = http.watchRoomRepository.create(
            CreateWatchRoomRequest(42, "remux", 0, null, listOf(2)),
        )

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals(503, error.status)
        assertEquals(5, error.retryAfterSec)
    }

    @Test
    fun `join and delete hit their routes and ignore the body`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = TestHttp {
            requests += it
            jsonResponse("""{"error":false,"data":{"room_id":7,"joined":true}}""")
        }

        assertEquals(ApiResult.Success(Unit), http.watchRoomRepository.join(7))
        assertEquals(ApiResult.Success(Unit), http.watchRoomRepository.delete(7))

        assertEquals(
            listOf(HttpMethod.Post to "/api/watch-rooms/7/join", HttpMethod.Delete to "/api/watch-rooms/7"),
            requests.map { it.method to it.url.encodedPath },
        )
    }

    @Test
    fun `update members puts the invitee list and leave posts to its route`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val http = TestHttp {
            requests += it
            bodies += String(it.body.toByteArray())
            jsonResponse("""{"error":false,"data":{"left":true}}""")
        }

        assertEquals(ApiResult.Success(Unit), http.watchRoomRepository.updateMembers(7, listOf(3, 2)))
        assertEquals(ApiResult.Success(Unit), http.watchRoomRepository.leave(7))

        assertEquals(
            listOf(HttpMethod.Put to "/api/watch-rooms/7/members", HttpMethod.Post to "/api/watch-rooms/7/leave"),
            requests.map { it.method to it.url.encodedPath },
        )
        assertEquals("""{"invited_user_ids":[3,2]}""", bodies.first())
    }

    @Test
    fun `an owner leaving is the server's conflict`() = runTest {
        val http = TestHttp {
            respond(
                """{"error":true,"message":"the room owner cannot leave; close the room instead"}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = http.watchRoomRepository.leave(7)

        assertEquals(409, ((result as ApiResult.Failure).error as AppError.Api).status)
    }

    @Test
    fun `member list and revoked access frames decode`() {
        assertEquals(
            WatchRoomServerEvent(
                type = WatchRoomEventType.MembersChanged,
                members = listOf(UserSummary(id = 1, name = "Jose", avatar = null)),
            ),
            decodeRoomEvent(
                """{"type":"members_changed","room_id":7,"members":[{"id":1,"name":"Jose","avatar":null}]}""",
            ),
        )
        assertEquals(
            WatchRoomServerEvent(type = WatchRoomEventType.RoomAccessRevoked),
            decodeRoomEvent("""{"type":"room_access_revoked","room_id":7}"""),
        )
    }

    @Test
    fun `invite users decodes everyone the server offers`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                """{"error":false,"data":{"users":[""" +
                    """{"id":2,"name":"Jorge","email":"jorge@example.com","avatar":null},""" +
                    """{"id":3,"name":"Ana","email":"ana@example.com","avatar":"/api/static/avatars/3.jpg"}]}}""",
            )
        }

        val users = (http.watchRoomRepository.inviteUsers() as ApiResult.Success).value

        assertEquals("/api/users", requireNotNull(request).url.encodedPath)
        assertEquals(listOf(2L to "Jorge", 3L to "Ana"), users.map { it.id to it.name })
        assertEquals("/api/static/avatars/3.jpg", users.last().avatar)
    }

    @Test
    fun `a snapshot frame decodes where the room is and who is connected`() {
        val event = decodeRoomEvent(
            """{"type":"room_snapshot","room_id":7,"playback":{"paused":false,"position_sec":61.5,""" +
                """"updated_at":"2026-11-13T18:30:00.123456789Z"},"connected_user_ids":[1,3]}""",
        )

        assertEquals(
            WatchRoomServerEvent(
                type = WatchRoomEventType.RoomSnapshot,
                playback = WatchRoomPlaybackState(paused = false, positionSec = 61.5),
                connectedUserIds = listOf(1, 3),
            ),
            event,
        )
    }

    @Test
    fun `member and deletion frames decode with only the fields the server sends`() {
        assertEquals(
            WatchRoomServerEvent(
                type = WatchRoomEventType.MemberJoined,
                member = UserSummary(id = 3, name = "Ana", avatar = null),
                connectedUserIds = listOf(1, 3),
            ),
            decodeRoomEvent(
                """{"type":"member_joined","room_id":7,"member":{"id":3,"name":"Ana","avatar":null},""" +
                    """"connected_user_ids":[1,3]}""",
            ),
        )
        assertEquals(
            WatchRoomServerEvent(type = WatchRoomEventType.RoomDeleted),
            decodeRoomEvent("""{"type":"room_deleted","room_id":7}"""),
        )
    }

    @Test
    fun `a frame this build cannot read is skipped rather than ending the socket`() {
        assertNull(decodeRoomEvent("""{"type":"chat_message","room_id":7,"text":"hi"}"""))
        assertNull(decodeRoomEvent("not json"))
    }

    @Test
    fun `a command encodes its contract type and the sender's position`() {
        assertEquals(
            """{"type":"seek","position_sec":3725.5}""",
            encodeRoomCommand(WatchRoomClientEvent(WatchRoomCommandType.Seek, 3725.5)),
        )
        assertEquals(
            """{"type":"pause","position_sec":0.0}""",
            encodeRoomCommand(WatchRoomClientEvent(WatchRoomCommandType.Pause, 0.0)),
        )
    }

    // --- the room's shared HLS session ---

    private val movieSessionSpec = HlsSessionSpec(
        media = PlaybackMediaRef.Movie(101),
        profileId = "remux",
        audioTypeIndex = 0,
        startSec = 600,
        sessionUuid = "uuid",
    )

    @Test
    fun `the room's manifest is a plain request whose media starts at 0`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = TestHttp {
            requests += it
            respond("#EXTM3U", HttpStatusCode.OK)
        }
        val session = http.watchRoomRepository.hlsSession(7, http.videoPlaybackRepository)

        val start = session.fetchHlsManifest(movieSessionSpec, keepalive = false)
        val keepalive = session.fetchHlsManifest(movieSessionSpec, keepalive = true)

        // Nothing of the player's spec is sent, the keepalive included: the room fixed it all.
        assertEquals(listOf("/api/watch-rooms/7/hls/playlist.m3u8"), requests.map { it.url.encodedPath }.distinct())
        assertTrue(requests.all { it.url.parameters.isEmpty() })
        assertEquals(HlsManifestResult.Ready(effectiveProfileId = "remux", actualStartSec = 0.0), start)
        assertEquals(start, keepalive)
        assertEquals("$TEST_SERVER/watch-rooms/7/hls/playlist.m3u8", session.hlsPlaylistUrl(movieSessionSpec))
    }

    @Test
    fun `the room's manifest reads a full server and a replaced file as their own states`() = runTest {
        var status = HttpStatusCode.ServiceUnavailable
        val http = TestHttp {
            respond("", status, headersOf(HttpHeaders.RetryAfter, "5"))
        }
        val session = http.watchRoomRepository.hlsSession(7, http.videoPlaybackRepository)

        assertEquals(HlsManifestResult.Busy(retryAfterSec = 5), session.fetchHlsManifest(movieSessionSpec))
        status = HttpStatusCode.Conflict
        assertEquals(
            HlsManifestResult.Failed(WATCH_ROOM_TRACKS_CHANGED_MESSAGE),
            session.fetchHlsManifest(movieSessionSpec),
        )
    }

    @Test
    fun `leaving the room never stops its session and subtitles stay the movie's`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = TestHttp {
            requests += it
            respond("", HttpStatusCode.OK)
        }
        val session = http.watchRoomRepository.hlsSession(7, http.videoPlaybackRepository)

        session.stopHlsSession(PlaybackMediaRef.Movie(101), "uuid")

        assertTrue(requests.isEmpty())
        assertEquals(
            "$TEST_SERVER/movies/101/subtitles/2/stream.mkv",
            session.subtitleStreamUrl(PlaybackMediaRef.Movie(101), trackIndex = 2, startSec = 0.0),
        )
        assertEquals("$TEST_SERVER/watch-rooms/7/stream", http.watchRoomRepository.streamUrl(7))
    }
}
