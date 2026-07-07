package com.igloo.blindpenguincoder

import android.content.Context
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.storage.DataStoreSessionCookieStore
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
    val cookiesStorage by lazy {
        PersistentCookiesStorage(DataStoreSessionCookieStore(appContext.sessionDataStore))
    }
    val httpClient by lazy { createIglooHttpClient(cookiesStorage) }
    val authApi by lazy { AuthApi(httpClient, serverUrlProvider) }
    val authRepository by lazy { AuthRepository(authApi, cookiesStorage) }
    val serverRepository by lazy {
        ServerRepository(authApi, serverSettingsStore, serverUrlProvider, cookiesStorage)
    }
    val sessionManager by lazy {
        SessionManager(authRepository, serverSettingsStore, serverUrlProvider)
    }
}
