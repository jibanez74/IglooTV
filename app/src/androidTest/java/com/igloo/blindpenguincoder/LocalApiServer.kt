package com.igloo.blindpenguincoder

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** What `POST /quick-connect/initiate` does, switchable mid-test. */
sealed interface QuickConnectInitiate {
    /** Holds the connection open so the pairing phase rests at `RequestingCode`. */
    data object Hang : QuickConnectInitiate

    /** Answers 500, which the pairing loop treats as non-retryable: `Failed` immediately. */
    data object Fail : QuickConnectInitiate

    /** Issues [code] with a 1s poll interval so redeem outcomes land fast. */
    data class Code(val code: String) : QuickConnectInitiate
}

/**
 * A canned Igloo API on the device's loopback, for gates that cannot be reached without one.
 *
 * The PIN gate is published from the server's `has_pin` rather than the vault's copy, and the
 * quick-connect phase is the server's answer to `initiate` — so no amount of seeding puts those
 * screens in a chosen state with the server unreachable. Which is the point of those designs,
 * and inconvenient here. Only the routes the tested screens call are implemented; anything else
 * answers 404 loudly rather than pretending.
 *
 * Each connection is handled on its own thread so a deliberately hanging route (see
 * [QuickConnectInitiate.Hang]) never stalls the requests behind it.
 */
class LocalApiServer(private val hasPin: Boolean = true) {

    private val socket = ServerSocket(0)
    private val closed = CountDownLatch(1)

    /** Whether the next `POST /user/pin/verify` accepts the digits. */
    @Volatile
    var pinValid: Boolean = false

    @Volatile
    var quickConnectInitiate: QuickConnectInitiate = QuickConnectInitiate.Hang

    /** Whether `POST /quick-connect/redeem` approves (with a token) or stays pending. */
    @Volatile
    var quickConnectRedeemApproved: Boolean = false

    val apiBaseUrl: String get() = "http://127.0.0.1:${socket.localPort}/api"

    init {
        thread(isDaemon = true, name = "LocalApiServer") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true, name = "LocalApiServer-connection") {
                    client.use(::respond)
                }
            }
        }
    }

    fun close() {
        closed.countDown()
        socket.close()
    }

    private fun respond(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val path = readRequest(input)
        val body = when {
            path.endsWith("/auth/user") -> authUserJson()
            path.endsWith("/user/pin/verify") -> """{"error":false,"data":{"valid":$pinValid}}"""
            path.endsWith("/quick-connect/initiate") -> when (val behavior = quickConnectInitiate) {
                // Held open until the server closes; the client sees no response at all, so the
                // screen rests at "Requesting pairing code" for the whole test.
                QuickConnectInitiate.Hang -> {
                    closed.await()
                    return
                }
                QuickConnectInitiate.Fail -> {
                    respondWith(client, "500 Internal Server Error", """{"error":true,"message":"initiate failed"}""")
                    return
                }
                is QuickConnectInitiate.Code -> """{"error":false,"data":{
                    "code":"${behavior.code}","secret":"igqc_test_secret",
                    "expires_in_seconds":600,"poll_interval_seconds":1}}"""
            }
            path.endsWith("/quick-connect/redeem") -> if (quickConnectRedeemApproved) {
                """{"error":false,"data":{"status":"approved","token":"igd_quick_connect"}}"""
            } else {
                """{"error":false,"data":{"status":"pending"}}"""
            }
            // The home rails load behind every authenticated gate; an empty library is the
            // cleanest true state for tests that only assert on the shell.
            path.endsWith("/movies/latest") -> """{"error":false,"data":{"movies":[]}}"""
            path.endsWith("/movies/continue-watching") -> """{"error":false,"data":{"movies":[]}}"""
            path.endsWith("/tmdb/movies/in-theaters") -> """{"error":false,"data":{"movies":[]}}"""
            path.endsWith("/music/albums/latest") -> """{"error":false,"data":{"albums":[]}}"""
            else -> null
        }
        val status = if (body == null) "404 Not Found" else "200 OK"
        val payload = body ?: """{"error":true,"message":"no test route for $path"}"""
        respondWith(client, status, payload)
    }

    private fun respondWith(client: Socket, status: String, payload: String) {
        client.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        payload
                    ).toByteArray(),
            )
            flush()
        }
    }

    /**
     * Returns the request path, having consumed the headers and any body. The body has to be read
     * even though nothing here inspects it: leaving it in the socket makes the close look like a
     * reset to the client, which surfaces as a network error rather than the response just sent.
     */
    private fun readRequest(input: InputStream): String {
        val requestLine = input.readLine()
        var contentLength = 0
        while (true) {
            val header = input.readLine()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        repeat(contentLength) { input.read() }
        return requestLine.split(' ').getOrElse(1) { "" }
    }

    private fun InputStream.readLine(): String {
        val line = StringBuilder()
        while (true) {
            val byte = read()
            if (byte == -1 || byte == '\n'.code) break
            if (byte != '\r'.code) line.append(byte.toChar())
        }
        return line.toString()
    }

    /**
     * `avatar` carries an uploaded relative path rather than null, so the gates that sign in
     * exercise the shape that used to fail: it is what the server actually stores, and what
     * `avatarImageUrl` has to resolve before the rail can render it.
     */
    private fun authUserJson() = """
        {"error":false,"data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":"/api/static/avatars/1-1735689600.jpg","has_pin":$hasPin,
            "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()
}
