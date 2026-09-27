package com.igloo.blindpenguincoder

import android.content.Context
import com.igloo.blindpenguincoder.core.config.deviceIdentity
import com.igloo.blindpenguincoder.core.image.CoilImageCache
import com.igloo.blindpenguincoder.core.network.AuthEventBus
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.DeviceCredentialSource
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.network.createServerProbeHttpClient
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.storage.AndroidKeystoreCipher
import com.igloo.blindpenguincoder.core.storage.DataStoreProfileStore
import com.igloo.blindpenguincoder.core.storage.SecretCipher
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.core.storage.UiPreferencesStore
import com.igloo.blindpenguincoder.core.storage.sessionDataStore
import com.igloo.blindpenguincoder.core.storage.settingsDataStore
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.api.ShowApi
import com.igloo.blindpenguincoder.data.api.VideoPlaybackApi
import com.igloo.blindpenguincoder.data.api.UserApi
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.data.repository.VideoPlaybackRepository
import com.igloo.blindpenguincoder.feature.auth.SessionManager
import com.igloo.blindpenguincoder.playback.hls.HLS_SEGMENT_READ_TIMEOUT_MS
import com.igloo.blindpenguincoder.playback.media3.bearerStreamDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class IglooAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val serverSettingsStore by lazy { ServerSettingsStore(appContext.settingsDataStore) }
    val uiPreferencesStore by lazy { UiPreferencesStore(appContext.settingsDataStore) }
    val serverUrlProvider by lazy { ServerUrlProvider() }
    private val secretCipher: SecretCipher by lazy { AndroidKeystoreCipher() }
    val credentials: DeviceCredentialSource by lazy { tokenProvider }
    private val tokenProvider by lazy { BearerTokenProvider() }
    private val authEvents by lazy { AuthEventBus() }
    val profileRepository by lazy {
        ProfileRepository(
            DataStoreProfileStore(appContext.sessionDataStore, secretCipher),
            tokenProvider,
        )
    }
    private val identity by lazy { deviceIdentity(appContext) }
    val httpClient by lazy { createIglooHttpClient(credentials, authEvents) }

    /**
     * Media3's HTTP stack for the direct stream, with the bearer resolver and the 401 bridge.
     * Built here because [authEvents] is private on purpose: the signal is non-suspending and
     * safe from ExoPlayer's loader thread, and [SessionManager] already collects it.
     */
    val streamDataSourceFactory by lazy {
        bearerStreamDataSourceFactory(
            credentials,
            serverUrlProvider,
            onUnauthorized = { profileId -> authEvents.signalUnauthorized(profileId) },
        )
    }

    /** The same stack with the patience HLS needs: segment requests long-poll up to 120s. */
    val hlsStreamDataSourceFactory by lazy {
        bearerStreamDataSourceFactory(
            credentials,
            serverUrlProvider,
            onUnauthorized = { profileId -> authEvents.signalUnauthorized(profileId) },
            readTimeoutMs = HLS_SEGMENT_READ_TIMEOUT_MS,
        )
    }
    private val serverProbeHttpClient by lazy { createServerProbeHttpClient() }
    private val serverHealthProbe by lazy { ServerHealthProbe(serverProbeHttpClient) }
    val authApi by lazy { AuthApi(httpClient, serverUrlProvider) }
    private val userApi by lazy { UserApi(httpClient, serverUrlProvider) }
    private val movieApi by lazy { MovieApi(httpClient, serverUrlProvider) }
    val movieRepository by lazy { MovieRepository(movieApi) }
    private val showApi by lazy { ShowApi(httpClient, serverUrlProvider) }
    val showRepository by lazy { ShowRepository(showApi) }
    private val videoPlaybackApi by lazy { VideoPlaybackApi(httpClient, serverUrlProvider) }
    val videoPlaybackRepository by lazy { VideoPlaybackRepository(videoPlaybackApi) }
    private val musicApi by lazy { MusicApi(httpClient, serverUrlProvider) }
    val musicRepository by lazy { MusicRepository(musicApi) }
    val authRepository by lazy {
        AuthRepository(authApi, userApi, profileRepository, identity)
    }
    val serverRepository by lazy {
        ServerRepository(serverHealthProbe, serverSettingsStore, serverUrlProvider, profileRepository)
    }

    /**
     * Outlives any screen, so a rejected credential is still noticed mid-navigation and a
     * released player's HLS stop request still reaches the server.
     */
    val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    val sessionManager by lazy {
        SessionManager(
            authRepository = authRepository,
            profiles = profileRepository,
            settings = serverSettingsStore,
            serverUrl = serverUrlProvider,
            authEvents = authEvents,
            scope = applicationScope,
            imageCache = CoilImageCache(appContext),
        )
    }
}
