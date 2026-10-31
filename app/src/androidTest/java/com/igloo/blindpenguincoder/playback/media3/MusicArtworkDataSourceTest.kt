@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.core.network.ActiveCredential
import com.igloo.blindpenguincoder.core.network.DeviceCredentialSource
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Cover failures must never enter the stream's profile-revocation bridge. */
@RunWith(AndroidJUnit4::class)
class MusicArtworkDataSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val credentials = DeviceCredentialSource { ActiveCredential(42L, "test-token") }

    @Test
    fun externalArtwork401AfterAuthenticatedStreamDoesNotReportRevocationOrSendBearer() {
        MusicArtworkTestServer().use { server ->
            MusicArtworkTestServer().use { external ->
                val serverUrl = serverUrl(server)
                val revoked = CopyOnWriteArrayList<Long?>()
                val streams = bearerStreamDataSourceFactory(credentials, serverUrl, revoked::add)
                val artwork = bearerArtworkDataSourceFactory(credentials, serverUrl)

                // Prime the stream resolver with a profile, reproducing the stale identity that
                // used to turn an unrelated external cover's 401 into profile removal.
                openStream(streams, "${server.origin}/stream")
                assertEquals("Bearer test-token", server.takeRequest().headers["authorization"])

                assertArtworkUnauthorized(artwork, "${external.origin}/cover")
                assertNull(external.takeRequest().headers["authorization"])
                assertTrue("artwork must not revoke the active profile", revoked.isEmpty())
            }
        }
    }

    @Test
    fun sameOriginArtwork401SendsBearerButDoesNotReportRevocation() {
        MusicArtworkTestServer().use { server ->
            val serverUrl = serverUrl(server)
            val revoked = CopyOnWriteArrayList<Long?>()
            val streams = bearerStreamDataSourceFactory(credentials, serverUrl, revoked::add)
            val artwork = bearerArtworkDataSourceFactory(credentials, serverUrl)

            openStream(streams, "${server.origin}/stream")
            server.takeRequest()
            assertArtworkUnauthorized(artwork, "${server.origin}/api/static/cover")
            assertEquals("Bearer test-token", server.takeRequest().headers["authorization"])
            assertTrue("a rejected server cover is only an artwork failure", revoked.isEmpty())
        }
    }

    @Test
    fun stream401StillReportsTheRejectedTokensProfile() {
        MusicArtworkTestServer().use { server ->
            val revoked = CopyOnWriteArrayList<Long?>()
            val streams = bearerStreamDataSourceFactory(credentials, serverUrl(server), revoked::add)

            try {
                openStream(streams, "${server.origin}/rejected-stream")
                fail("the stream should fail with HTTP 401")
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                assertEquals(401, error.responseCode)
            }

            assertEquals("Bearer test-token", server.takeRequest().headers["authorization"])
            assertEquals(listOf(42L), revoked.toList())
        }
    }

    private fun serverUrl(server: MusicArtworkTestServer) = ServerUrlProvider().apply {
        set((parseServerAddress(server.origin) as ServerAddressParseResult.Valid).address)
    }

    private fun openStream(factory: DataSource.Factory, url: String) {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
        } finally {
            source.close()
        }
    }

    private fun assertArtworkUnauthorized(factory: DataSource.Factory, url: String) {
        val loader = DataSourceBitmapLoader.Builder(context).setDataSourceFactory(factory).build()
        try {
            loader.loadBitmap(Uri.parse(url)).get(5, TimeUnit.SECONDS)
            fail("the cover should fail with HTTP 401")
        } catch (error: ExecutionException) {
            val cause = error.cause
            assertTrue("expected an HTTP failure, got $cause", cause is HttpDataSource.InvalidResponseCodeException)
            assertEquals(401, (cause as HttpDataSource.InvalidResponseCodeException).responseCode)
        }
    }
}

/** Loopback HTTP fixture: one playable stream and rejected covers, with captured auth headers. */
internal class MusicArtworkTestServer : Closeable {
    data class Request(val path: String, val headers: Map<String, String>)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val requests = LinkedBlockingQueue<Request>()
    val origin = "http://127.0.0.1:${socket.localPort}"

    private val worker = thread(isDaemon = true, name = "MusicArtworkTestServer") {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: SocketException) {
                if (socket.isClosed) break else throw error
            }
            client.use {
                it.soTimeout = 5_000
                val input = it.getInputStream().bufferedReader()
                val path = input.readLine().split(' ')[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                requests.add(Request(path, headers))
                val body = if (path == "/stream") silentWav() else byteArrayOf()
                val status = if (path == "/stream") "200 OK" else "401 Unauthorized"
                it.getOutputStream().apply {
                    write(("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    write(body)
                    flush()
                }
            }
        }
    }

    fun takeRequest(): Request = checkNotNull(requests.poll(5, TimeUnit.SECONDS)) {
        "timed out waiting for an HTTP request"
    }

    override fun close() {
        socket.close()
        worker.join(5_000)
    }

    private fun silentWav(): ByteArray {
        // One second of mono, 16-bit PCM at 8 kHz; no external media fixture required.
        val dataSize = 16_000
        return java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}
