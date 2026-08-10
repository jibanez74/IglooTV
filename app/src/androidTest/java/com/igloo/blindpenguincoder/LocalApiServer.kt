package com.igloo.blindpenguincoder

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A canned Igloo API on the device's loopback, for gates that cannot be reached without one.
 *
 * The PIN gate is published from the server's `has_pin` rather than the vault's copy, so no
 * amount of seeding puts the keypad on screen with the server unreachable — which is the point of
 * that design, and inconvenient here. Only the two routes those screens call are implemented;
 * anything else answers 404 loudly rather than pretending.
 */
class LocalApiServer(private val hasPin: Boolean = true) {

    private val socket = ServerSocket(0)

    /** Whether the next `POST /user/pin/verify` accepts the digits. */
    @Volatile
    var pinValid: Boolean = false

    val apiBaseUrl: String get() = "http://127.0.0.1:${socket.localPort}/api"

    init {
        thread(isDaemon = true, name = "LocalApiServer") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                client.use(::respond)
            }
        }
    }

    fun close() = socket.close()

    private fun respond(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val path = readRequest(input)
        val body = when {
            path.endsWith("/auth/user") -> authUserJson()
            path.endsWith("/user/pin/verify") -> """{"error":false,"data":{"valid":$pinValid}}"""
            // The home rails load behind every authenticated gate; an empty library is the
            // cleanest true state for tests that only assert on the shell.
            path.endsWith("/movies/latest") -> """{"error":false,"data":{"movies":[]}}"""
            path.endsWith("/movies/continue-watching") -> """{"error":false,"data":{"movies":[]}}"""
            else -> null
        }
        val status = if (body == null) "404 Not Found" else "200 OK"
        val payload = body ?: """{"error":true,"message":"no test route for $path"}"""
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

    /** `avatar` is a Go `sql.NullString`, not the plain string docs/openapi.json describes. */
    private fun authUserJson() = """
        {"error":false,"data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":{"String":"","Valid":false},"has_pin":$hasPin,
            "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()
}
