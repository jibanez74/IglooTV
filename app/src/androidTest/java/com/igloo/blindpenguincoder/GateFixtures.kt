package com.igloo.blindpenguincoder

import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.runBlocking

/** The app under test, whose container the gate suites seed and clear directly. */
internal fun iglooApp(): IglooApplication =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IglooApplication

/** Forgets the server and every profile, so the next launch starts at server setup. */
internal fun clearStoredSetup() {
    val app = iglooApp()
    runBlocking {
        app.container.serverSettingsStore.clear()
        app.container.profileRepository.clearAll()
    }
    app.container.serverUrlProvider.set(null)
}

/** One stored, inactive profile with a PIN — Jose on [apiBaseUrl] — so a launch gates on it. */
internal fun seedPinProtectedProfile(apiBaseUrl: String) {
    val app = iglooApp()
    runBlocking {
        app.container.serverSettingsStore.save(apiBaseUrl)
        app.container.profileRepository.clearAll()
        app.container.profileRepository.setPending("igd_jose")
        app.container.profileRepository.commitSignIn(
            AuthUser(id = 1, name = "Jose", isAdmin = false, avatar = null, hasPin = true),
        )
        app.container.profileRepository.deactivate()
    }
    app.container.serverUrlProvider.set(null)
}
