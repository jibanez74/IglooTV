// Media3's DataSource surface is marked unstable; this file is the one place the app builds on
// it, and the seam above (MoviePlayerEngine) keeps the instability from spreading.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.igloo.blindpenguincoder.core.network.DeviceCredentialSource
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import kotlinx.coroutines.runBlocking

/**
 * Media3 bypasses the app's Ktor client, so stream requests get their bearer here (section
 * 11.8). Same-origin only, like the image loader's interceptor: a credential sent to a foreign
 * host is a credential leaked. The credential sits behind a suspending source; [runBlocking] is
 * acceptable because [ResolvingDataSource.Resolver.resolveDataSpec] runs on ExoPlayer's own
 * loading thread — never main — which immediately blocks on network I/O anyway, and the
 * source's mutex is uncontended.
 */
private class BearerResolver(
    private val credentials: DeviceCredentialSource,
    private val serverUrl: ServerUrlProvider,
) : ResolvingDataSource.Resolver {

    /** Who the last attached token belonged to, for the 401 bridge's signal. */
    @Volatile
    var attachedProfileId: Long? = null

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val origin = serverUrl.current.value?.origin ?: return dataSpec
        if (!dataSpec.uri.toString().startsWith("$origin/")) return dataSpec
        val credential = runBlocking { credentials.current() } ?: return dataSpec
        attachedProfileId = credential.profileId
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(
                dataSpec.httpRequestHeaders + ("Authorization" to "Bearer ${credential.token}"),
            )
            .build()
    }
}

/**
 * A stream 401 never reaches the Ktor pipeline, so it is bridged to the session state machine
 * here: signal, then rethrow — the player still surfaces its own error while the host begins
 * revalidating the profile.
 */
private class UnauthorizedReportingDataSource(
    private val delegate: DataSource,
    private val onUnauthorized: () -> Unit,
) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long = try {
        delegate.open(dataSpec)
    } catch (exception: HttpDataSource.InvalidResponseCodeException) {
        if (exception.responseCode == 401) onUnauthorized()
        throw exception
    }
}

/**
 * The player's HTTP stack: [DefaultHttpDataSource] (Range/206 and redirects are all a single
 * progressive stream needs), the bearer resolver, and the 401 bridge. [onUnauthorized] receives
 * the profile the rejected token belonged to; it is thread-safe to call from the loader thread.
 */
fun bearerStreamDataSourceFactory(
    credentials: DeviceCredentialSource,
    serverUrl: ServerUrlProvider,
    onUnauthorized: (Long?) -> Unit,
): DataSource.Factory {
    val resolver = BearerResolver(credentials, serverUrl)
    val resolving = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory(), resolver)
    return DataSource.Factory {
        UnauthorizedReportingDataSource(resolving.createDataSource()) {
            onUnauthorized(resolver.attachedProfileId)
        }
    }
}
