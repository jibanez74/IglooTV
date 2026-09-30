package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.playback.model.HLS_AUDIO_CONVERSION_UNAVAILABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.HLS_RECONNECTING_MESSAGE
import com.igloo.blindpenguincoder.playback.model.HLS_WAITING_FOR_CAPACITY_MESSAGE
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_BUSY_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SESSION_LOST_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * What the controller needs from the network layer, consumer-owned so JVM tests can fake it.
 * Implemented by the video playback repository.
 */
interface HlsSessionApi {
    suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult
    suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String)
    fun hlsPlaylistUrl(spec: HlsSessionSpec): String
    fun subtitleUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String
}

/** A session could not be established within the retry budget. */
class HlsStartException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * Owns one title's HLS session lifecycle for the lifetime of one player engine: the
 * `playback_session` UUID (reused across uninterrupted HLS restarts so the backend self-evicts
 * the previous session on commit), the preflight manifest fetch with its capacity/lost retry
 * loops, the keepalive that covers the player's quiet stretches, and the best-effort stop on
 * release.
 */
class HlsSessionController(
    private val media: PlaybackMediaRef,
    private val api: HlsSessionApi,
    /** Engine-lifetime scope; the keepalive loop dies with it. */
    private val scope: CoroutineScope,
    /** App-lifetime scope; the stop POST must survive the engine's release. */
    private val stopScope: CoroutineScope,
    sessionUuid: String = UUID.randomUUID().toString(),
) {
    private var sessionUuid: String = sessionUuid
    private var currentSpec: HlsSessionSpec? = null
    private var manifestRequestIssued = false
    private var reload = 0
    private var keepaliveJob: Job? = null
    private var generation = 0L
    private var generationReserved = false

    /** Marks this UUID as owned by a pending HLS transition before its coroutine is dispatched. */
    fun reserveGeneration() {
        generationReserved = true
    }

    /**
     * Establishes (or re-establishes) a session and returns where its media actually starts.
     * Blocks through the capacity and session-lost retry budgets, narrating waits through
     * [onStatus] (cleared with null once ready). Throws [HlsStartException] when a budget is
     * exhausted or the server refuses outright.
     */
    suspend fun start(
        profileId: String,
        audioTypeIndex: Int?,
        audioProfile: HlsAudioProfile?,
        startSec: Int,
        onStatus: (String?) -> Unit = {},
    ): HlsSessionStart {
        reserveGeneration()
        val startGeneration = generation
        val startUuid = sessionUuid
        // The attempt budgets bound retries, not time: a server that accepts connections and
        // never answers spends the manifest request timeout on each one. This is the ceiling
        // the viewer actually feels, and it runs on the coroutine clock so it is testable.
        return try {
            withTimeout(HLS_START_TOTAL_BUDGET_MS) {
                startLoop(
                    startGeneration, startUuid, profileId, audioTypeIndex, audioProfile,
                    startSec, onStatus,
                )
            }
        } catch (_: TimeoutCancellationException) {
            throw HlsStartException(PLAYBACK_SERVER_BUSY_MESSAGE)
        }
    }

    private suspend fun startLoop(
        startGeneration: Long,
        startUuid: String,
        profileId: String,
        audioTypeIndex: Int?,
        requestedAudioProfile: HlsAudioProfile?,
        startSec: Int,
        onStatus: (String?) -> Unit,
    ): HlsSessionStart {
        var busyAttempts = 0
        var lostAttempts = 0
        var audioProfile = requestedAudioProfile
        while (true) {
            ensureCurrent(startGeneration, startUuid)
            val spec = HlsSessionSpec(
                media = media,
                profileId = profileId,
                audioTypeIndex = audioTypeIndex,
                startSec = startSec,
                sessionUuid = startUuid,
                reload = reload,
                audioProfile = audioProfile,
            )
            manifestRequestIssued = true
            val result = api.fetchHlsManifest(spec)
            ensureCurrent(startGeneration, startUuid)
            when (result) {
                is HlsManifestResult.Ready -> {
                    currentSpec = spec
                    onStatus(null)
                    return HlsSessionStart(
                        effectiveProfileId = result.effectiveProfileId,
                        actualStartSec = result.actualStartSec,
                        playlistUrl = api.hlsPlaylistUrl(spec),
                    )
                }
                is HlsManifestResult.Busy -> {
                    busyAttempts++
                    val delayMs = capacityRetryDelayMs(busyAttempts, result.retryAfterSec)
                        ?: throw HlsStartException(PLAYBACK_SERVER_BUSY_MESSAGE)
                    onStatus(HLS_WAITING_FOR_CAPACITY_MESSAGE)
                    delay(delayMs)
                }
                is HlsManifestResult.Lost -> {
                    lostAttempts++
                    reload++
                    val delayMs = sessionLostRetryDelayMs(lostAttempts)
                        ?: throw HlsStartException(PLAYBACK_SESSION_LOST_MESSAGE)
                    onStatus(HLS_RECONNECTING_MESSAGE)
                    delay(delayMs)
                }
                is HlsManifestResult.Failed ->
                    // A 400/422 on the conversion pair means server-side contract drift or
                    // source metadata the server rejects; one immediate legacy retry keeps the
                    // movie playing with the pre-conversion behavior. One-shot by construction:
                    // the profile drops and cannot come back within this start.
                    if (result.rejectedRequest && audioProfile != null) {
                        audioProfile = null
                        onStatus(HLS_AUDIO_CONVERSION_UNAVAILABLE_MESSAGE)
                    } else {
                        throw HlsStartException(result.message, result.unauthorized)
                    }
            }
        }
    }

    /** Marks the current session dead so the next [start] busts caches with a fresh reload. */
    fun noteSessionLost() {
        reload++
    }

    /**
     * Refreshes the server's 5-minute idle TTL for the life of the session. The refresh only
     * matters while the player is paused or fully buffered — its own traffic covers the rest —
     * but it deliberately runs unconditionally: one small manifest GET every two minutes is
     * cheaper than racing the player's transport-state changes. A lost session surfaces
     * through [onSessionLost]; transient failures are ignored — the next tick or the player's
     * own traffic will recover.
     */
    fun startKeepalive(onSessionLost: () -> Unit) {
        keepaliveJob?.cancel()
        val keepaliveGeneration = generation
        val keepaliveUuid = sessionUuid
        keepaliveJob = scope.launch {
            while (true) {
                delay(HLS_KEEPALIVE_INTERVAL_MS)
                if (!isCurrent(keepaliveGeneration, keepaliveUuid)) return@launch
                val spec = currentSpec ?: continue
                // A throwing tick must not end the loop, and must not reach the scope's
                // uncaught handler: the next tick, or the player's own traffic, recovers.
                val result = try {
                    api.fetchHlsManifest(spec)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    continue
                }
                if (!isCurrent(keepaliveGeneration, keepaliveUuid)) return@launch
                when (result) {
                    is HlsManifestResult.Lost -> onSessionLost()
                    else -> Unit
                }
            }
        }
    }

    fun cancelKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /**
     * Invalidates the current generation synchronously, then ends its server session on the
     * surviving scope. Rotating before the POST launches guarantees a delayed old stop can
     * never target a later HLS session.
     */
    fun releaseAndStop() {
        cancelKeepalive()
        if (!generationReserved) return
        val stoppedUuid = sessionUuid
        val shouldStopServer = manifestRequestIssued
        generation++
        currentSpec = null
        manifestRequestIssued = false
        reload = 0
        generationReserved = false
        sessionUuid = UUID.randomUUID().toString()
        if (shouldStopServer) {
            stopScope.launch {
                runCatching { api.stopHlsSession(media, stoppedUuid) }
            }
        }
    }

    private fun ensureCurrent(expectedGeneration: Long, expectedUuid: String) {
        if (!isCurrent(expectedGeneration, expectedUuid)) {
            throw CancellationException("HLS session generation was invalidated")
        }
    }

    private fun isCurrent(expectedGeneration: Long, expectedUuid: String): Boolean =
        generation == expectedGeneration && sessionUuid == expectedUuid
}
