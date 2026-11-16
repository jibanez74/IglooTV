package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.data.api.WatchRoomApi
import com.igloo.blindpenguincoder.data.api.webSocket
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The room's socket over a real OkHttp connection: MockEngine cannot upgrade, so every other room
 * test fakes [WatchRoomLink]. This one checks what only the real client does — the upgrade's URL
 * and bearer token, and frames both ways through [WatchRoomRepository.connect].
 */
class WatchRoomSocketTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `the room's socket upgrades with the bearer token and carries frames both ways`() = runBlocking {
        val fromClient = Channel<String>(Channel.UNLIMITED)
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"type":"room_snapshot","room_id":7,"playback":{"paused":true,""" +
                                    """"position_sec":42.5,"updated_at":"2026-11-16T12:00:00Z"},""" +
                                    """"connected_user_ids":[1]}""",
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            fromClient.trySend(text)
                        }

                        // MockWebServer waits on its side of the socket when it shuts down.
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                        }
                    },
                )
                .build(),
        )
        val http = TestHttp { error("no plain requests in this test") }
        http.profiles.setPending("igd_socket")
        http.serverUrl.set(testServerAddress(server.url("/").toString().trimEnd('/')))
        val client = createIglooHttpClient(http.credentials, http.authEvents)
        val repository = WatchRoomRepository(WatchRoomApi(client, http.serverUrl))

        // The session ends when the block returns: the client closes, the server answers.
        withTimeout(10_000) {
            repository.connect(7) { socket ->
                val snapshot = socket.events.first()
                assertEquals(WatchRoomEventType.RoomSnapshot, snapshot.type)
                assertEquals(42.5, snapshot.playback!!.positionSec, 0.0)
                assertEquals(listOf(1L), snapshot.connectedUserIds)

                socket.send(WatchRoomClientEvent(WatchRoomCommandType.Pause, 42.5))
                assertEquals("""{"type":"pause","position_sec":42.5}""", fromClient.receive())
            }
        }
        client.close()

        val upgrade = server.takeRequest()
        assertEquals("/api/watch-rooms/7/ws", upgrade.url.encodedPath)
        assertEquals("Bearer igd_socket", upgrade.headers["Authorization"])
        assertEquals("websocket", upgrade.headers["Upgrade"]?.lowercase())
    }

    @Test
    fun `the socket takes the server's scheme, ws for http and wss for https`() {
        val plain = ServerUrlProvider().apply { set(testServerAddress("http://igloo.test:8080")) }
        val secure = ServerUrlProvider().apply { set(testServerAddress("https://igloo.example.com")) }

        assertEquals("ws://igloo.test:8080/api/watch-rooms/7/ws", plain.webSocket("/watch-rooms/7/ws"))
        assertEquals("wss://igloo.example.com/api/watch-rooms/7/ws", secure.webSocket("/watch-rooms/7/ws"))
    }
}
