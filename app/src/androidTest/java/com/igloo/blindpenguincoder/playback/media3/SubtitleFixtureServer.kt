// DataSource, DataSpec and AssetDataSource are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Plays the Igloo server over the fixtures `fixtures/generate-subtitle-streams.sh` writes to
 * `assets/subtitle-streams`: a 12 s HLS session, answered by file name whatever the path, and
 * `/subtitles/<n>/stream.mkv` as track n's stream — 0 the PGS track, 1 the SubRip one — with no
 * length, like the server's chunked response. Cues sit at 1.0–2.5, 3.0–4.5, 6.0–7.0, 8.0–9.5 s.
 * The assets ship in the test APK, so they are read through the instrumentation's own context.
 */
internal class SubtitleFixtureServer {
    private val context = InstrumentationRegistry.getInstrumentation().context

    /** Every path opened, in order. */
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Answers subtitle streams with this HTTP status instead, while set. */
    @Volatile
    var subtitleFailure: Int? = null

    /** Answers media segments with this HTTP status instead, while set. */
    @Volatile
    var segmentFailure: Int? = null

    /**
     * Serves the playlist as a remux session's does while FFmpeg still runs: an EVENT playlist
     * without its end tag, which Media3 plays as live.
     */
    @Volatile
    var eventPlaylist: Boolean = false

    /**
     * Holds each subtitle stream this long right after its primer, sending a Void every 250 ms,
     * as the server does while FFmpeg reads on toward a sparse track's next cue.
     */
    @Volatile
    var subtitleHoldMs: Long = 0

    val factory: DataSource.Factory = DataSource.Factory { FixtureDataSource() }

    fun subtitleRequests(trackIndex: Int): Int =
        synchronized(requests) { requests.count { it.contains("/subtitles/$trackIndex/stream.mkv") } }

    private inner class FixtureDataSource : DataSource {
        private var delegate: DataSource? = null
        private var held: HeldStream? = null
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            val path = dataSpec.uri.path.orEmpty()
            requests += path
            uri = dataSpec.uri
            val subtitle = SUBTITLE_STREAM.find(path)
            val failure = if (subtitle != null) subtitleFailure else segmentFailure.takeIf { path.endsWith(".m4s") }
            if (failure != null) {
                throw HttpDataSource.InvalidResponseCodeException(
                    failure,
                    /* responseMessage= */ null,
                    /* cause= */ null,
                    emptyMap(),
                    dataSpec,
                    ByteArray(0),
                )
            }
            val asset = when (subtitle?.groupValues?.get(1)) {
                null -> path.substringAfterLast('/')
                "0" -> "pgs.mkv"
                else -> "srt.mkv"
            }
            if (subtitle != null && subtitleHoldMs > 0) {
                held = HeldStream(readAsset(asset), SystemClock.uptimeMillis() + subtitleHoldMs)
                return C.LENGTH_UNSET.toLong()
            }
            if (asset == "playlist.m3u8" && eventPlaylist) {
                val playlist = String(readAsset(asset))
                    .replace("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-PLAYLIST-TYPE:EVENT")
                    // The server's own start tag, without which Media3 joins a live playlist
                    // near its edge.
                    .replace("#EXT-X-VERSION:7", "#EXT-X-START:TIME-OFFSET=0\n#EXT-X-VERSION:7")
                    .replace("#EXT-X-ENDLIST\n", "")
                return ByteArrayDataSource(playlist.toByteArray()).also { delegate = it }.open(dataSpec)
            }
            val source = AssetDataSource(context).also { delegate = it }
            val length = source.open(dataSpec.buildUpon().setUri(Uri.parse("asset:///subtitle-streams/$asset")).build())
            return if (subtitle != null) C.LENGTH_UNSET.toLong() else length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            held?.read(buffer, offset, length) ?: requireNotNull(delegate).read(buffer, offset, length)

        override fun getUri(): Uri? = uri

        override fun close() {
            delegate?.close()
            delegate = null
            held = null
        }
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open("subtitle-streams/$name").use { it.readBytes() }

    /** A stream that goes silent after its primer until [holdUntilMs], but for keepalive Voids. */
    private class HeldStream(private val bytes: ByteArray, private val holdUntilMs: Long) {
        private val holdAt = primerEnd(bytes)
        private var position = 0
        private var voidSent = VOID.size

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (voidSent < VOID.size) {
                val count = minOf(length, VOID.size - voidSent)
                VOID.copyInto(buffer, offset, voidSent, voidSent + count)
                voidSent += count
                return count
            }
            if (position == holdAt && SystemClock.uptimeMillis() < holdUntilMs) {
                // Uninterruptible, like a socket read: Media3 cancels a load by interrupting it.
                SystemClock.sleep((holdUntilMs - SystemClock.uptimeMillis()).coerceIn(0, 250))
                voidSent = 0
                return read(buffer, offset, length)
            }
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val end = if (position < holdAt) holdAt else bytes.size
            val count = minOf(length, end - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        private companion object {
            val PRIMER = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0x83.toByte(), 0xE7.toByte(), 0x81.toByte(), 0x00)
            val VOID = byteArrayOf(0xEC.toByte(), 0x81.toByte(), 0x00)

            fun primerEnd(bytes: ByteArray): Int {
                val at = (0..bytes.size - PRIMER.size).first { start ->
                    PRIMER.indices.all { bytes[start + it] == PRIMER[it] }
                }
                return at + PRIMER.size
            }
        }
    }

    private companion object {
        val SUBTITLE_STREAM = Regex("/subtitles/([0-9]+)/stream\\.mkv$")
    }
}
