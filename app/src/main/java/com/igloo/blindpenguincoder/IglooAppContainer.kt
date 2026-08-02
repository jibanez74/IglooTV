package com.igloo.blindpenguincoder

import android.content.Context
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.network.createServerProbeHttpClient
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.storage.AndroidKeystoreCipher
import com.igloo.blindpenguincoder.core.storage.DataStoreSessionCookieStore
import com.igloo.blindpenguincoder.core.storage.SecretCipher
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.core.storage.sessionDataStore
import com.igloo.blindpenguincoder.core.storage.settingsDataStore
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import com.igloo.blindpenguincoder.feature.auth.SessionManager

class IglooAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val serverSettingsStore by lazy { ServerSettingsStore(appContext.settingsDataStore) }
    val serverUrlProvider by lazy { ServerUrlProvider() }
    // The device bearer token will want the same cipher.
    private val secretCipher: SecretCipher by lazy { AndroidKeystoreCipher() }
    val cookiesStorage by lazy {
        PersistentCookiesStorage(
            DataStoreSessionCookieStore(appContext.sessionDataStore, secretCipher),
        )
    }
    val httpClient by lazy { createIglooHttpClient(cookiesStorage) }
    private val serverProbeHttpClient by lazy { createServerProbeHttpClient() }
    private val serverHealthProbe by lazy { ServerHealthProbe(serverProbeHttpClient) }
    val authApi by lazy { AuthApi(httpClient, serverUrlProvider) }
    val authRepository by lazy { AuthRepository(authApi, cookiesStorage) }
    val serverRepository by lazy {
        ServerRepository(serverHealthProbe, serverSettingsStore, serverUrlProvider, cookiesStorage)
    }
    val sessionManager by lazy {
        SessionManager(authRepository, serverSettingsStore, serverUrlProvider)
    }
}
