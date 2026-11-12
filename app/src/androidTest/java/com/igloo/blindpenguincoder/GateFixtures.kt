package com.igloo.blindpenguincoder

import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.delay
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
internal fun seedPinProtectedProfile(apiBaseUrl: String) =
    seedInactiveProfiles(apiBaseUrl, testUser(1, "Jose", hasPin = true))

/**
 * Stores [users] on [apiBaseUrl] as inactive profiles, in order, so the last one is the most
 * recently used. Committing a sign-in is pure vault work, so this seeds real profiles without a
 * server.
 */
internal fun seedInactiveProfiles(apiBaseUrl: String, vararg users: AuthUser) {
    val app = iglooApp()
    runBlocking {
        app.container.serverSettingsStore.save(apiBaseUrl)
        app.container.profileRepository.clearAll()
        users.forEachIndexed { index, user ->
            // Distinct wall-clock stamps, so "most recently used" is unambiguous.
            if (index > 0) delay(2)
            app.container.profileRepository.setPending("igd_${user.name.lowercase()}")
            app.container.profileRepository.commitSignIn(user)
        }
        app.container.profileRepository.deactivate()
    }
    app.container.serverUrlProvider.set(null)
}

internal fun testUser(id: Long, name: String, hasPin: Boolean = false) =
    AuthUser(id = id, name = name, isAdmin = false, avatar = null, hasPin = hasPin)
