package com.igloo.blindpenguincoder.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Shared by server setup, restoration, API requests, images, and Media3 loader threads. */
interface NetworkAccess {
    suspend fun awaitAccess(url: String)
    /** Loader-thread check: never wait for UI while ExoPlayer may be releasing on main. */
    fun checkAccess(url: String)
}

object UnrestrictedNetworkAccess : NetworkAccess {
    override suspend fun awaitAccess(url: String) = Unit
    override fun checkAccess(url: String) = Unit
}

data class LocalNetworkPermissionState(
    val granted: Boolean,
    val blockedHost: String? = null,
    val requestPending: Boolean = false,
    val requestId: Int = 0,
    val recovery: Int = 0,
    val cancelled: Int = 0,
)

class LocalNetworkPermissionException : IOException("Allow local network access to connect to this server.")

/**
 * Permission policy independent of Android and injectable in tests. One automatic request per
 * installation; denial leaves work suspended until an explicit Retry or Settings recovery.
 * No credentials or server settings belong to this coordinator.
 */
class LocalNetworkPermissionCoordinator(
    private val permissionGranted: () -> Boolean,
    private val wasRequested: () -> Boolean,
    private val markRequested: () -> Unit,
    private val requiresLocalAccess: (String) -> Boolean = { isLocalNetworkUrl(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NetworkAccess {
    private val _state = MutableStateFlow(LocalNetworkPermissionState(granted = permissionGranted()))
    val state: StateFlow<LocalNetworkPermissionState> = _state.asStateFlow()
    private var deliveredRequest = 0

    override suspend fun awaitAccess(url: String) {
        if (permissionGranted()) return
        val local = withContext(dispatcher) { requiresLocalAccess(url) }
        if (!local || permissionGranted()) return
        val cancellation = state.value.cancelled
        blocked(url)
        val result = state.first { it.granted || it.cancelled != cancellation }
        if (!result.granted) throw CancellationException("Local network operation cancelled")
    }

    override fun checkAccess(url: String) {
        if (permissionGranted() || !requiresLocalAccess(url) || permissionGranted()) return
        if (blocked(url)) throw LocalNetworkPermissionException()
    }

    @Synchronized
    private fun blocked(url: String): Boolean {
        if (permissionGranted()) {
            refresh()
            return false
        }
        _state.update { it.copy(granted = false, blockedHost = it.blockedHost ?: URI(url).host) }
        if (!wasRequested()) request()
        return true
    }

    /** Called only by the first blocked operation or by an explicit Retry press. */
    @Synchronized
    fun request() {
        if (permissionGranted()) {
            refresh()
            return
        }
        if (state.value.requestPending) return
        markRequested()
        _state.update { it.copy(requestPending = true, requestId = it.requestId + 1) }
    }

    /** Claim delivery once across Activity recreation; process recreation starts a fresh claim. */
    @Synchronized
    fun claimPermissionRequest(requestId: Int): Boolean {
        if (!state.value.requestPending || state.value.requestId != requestId || deliveredRequest == requestId) return false
        deliveredRequest = requestId
        return true
    }

    /** A fresh Activity launch cannot receive an old Activity's outstanding result. */
    fun onNewHost() {
        _state.update { it.copy(requestPending = false) }
        refresh()
    }

    /** Activity Result callback; a denial does not request again or discard the pending work. */
    fun onPermissionResult() {
        _state.update { it.copy(requestPending = false) }
        refresh()
    }

    /** Re-read on resume, including return from system settings and permission revocation. */
    fun refresh() {
        val granted = permissionGranted()
        _state.update {
            it.copy(
                granted = granted,
                blockedHost = if (granted) null else it.blockedHost,
                recovery = it.recovery + if (granted && !it.granted && it.blockedHost != null) 1 else 0,
            )
        }
    }

    /** Switching servers cancels old work; saved settings and credentials are untouched. */
    fun cancelPending() {
        _state.update { it.copy(blockedHost = null, cancelled = it.cancelled + 1) }
    }
}

/**
 * Resolve ordinary DNS off main as well: a public-looking hostname can resolve to a LAN IP.
 * Recognise .local before resolving, since mDNS itself needs permission. Failed DNS is left to
 * the HTTP stack to report normally. Android remains the final authority for redirects and
 * addresses that change between DNS resolution and socket connection.
 */
internal fun isLocalNetworkUrl(
    url: String,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
): Boolean {
    val uri = URI(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host?.removeSurrounding("[", "]")?.trimEnd('.')?.lowercase() ?: return false
    if (host.endsWith(".local") || !host.contains('.') && !host.contains(':')) return true
    return try {
        resolve(host).any(::isLocalAddress)
    } catch (_: UnknownHostException) {
        false
    }
}

internal fun isLocalAddress(address: InetAddress): Boolean {
    val bytes = address.address
    return address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
        address.isLinkLocalAddress || address.isMulticastAddress ||
        (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
}
