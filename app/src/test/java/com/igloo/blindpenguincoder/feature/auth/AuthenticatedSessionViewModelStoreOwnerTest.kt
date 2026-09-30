package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatedSessionViewModelStoreOwnerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `leaving authentication clears session ViewModels`() {
        val state = MutableStateFlow<AppAuthState>(authenticated())
        val parentStore = ViewModelStore()
        val owner = sessionOwner(parentStore, state)
        val sessionViewModel = sessionViewModel(owner)

        state.value = AppAuthState.NeedsServer()

        assertTrue(sessionViewModel.cleared)
        parentStore.clear()
    }

    @Test
    fun `reauthenticating the same user ID creates a fresh ViewModel`() {
        val state = MutableStateFlow<AppAuthState>(authenticated(userId = 7L))
        val parentStore = ViewModelStore()
        val owner = sessionOwner(parentStore, state)
        val first = sessionViewModel(owner)

        state.value = AppAuthState.NeedsServer()
        state.value = authenticated(userId = 7L)
        val second = sessionViewModel(owner)

        assertTrue(first.cleared)
        assertNotSame(first, second)
        parentStore.clear()
    }

    @Test
    fun `authenticating the same user ID on another server cannot reuse the prior store`() {
        val state = MutableStateFlow<AppAuthState>(authenticated(userId = 7L))
        val parentStore = ViewModelStore()
        val owner = sessionOwner(parentStore, state)
        val first = sessionViewModel(owner)

        state.value = AppAuthState.NeedsLogin(address("https://other.example.com"))
        state.value = authenticated(userId = 7L)
        val second = sessionViewModel(owner)

        assertTrue(first.cleared)
        assertNotSame(first, second)
        parentStore.clear()
    }

    @Test
    fun `authenticated revalidation retains the current session ViewModel`() {
        val state = MutableStateFlow<AppAuthState>(authenticated())
        val parentStore = ViewModelStore()
        val owner = sessionOwner(parentStore, state)
        val first = sessionViewModel(owner)

        state.value = authenticated(name = "Updated name")
        val second = sessionViewModel(owner)

        assertFalse(first.cleared)
        assertSame(first, second)
        parentStore.clear()
    }

    @Test
    fun `Activity recreation retains the session owner and its ViewModels`() {
        val state = MutableStateFlow<AppAuthState>(authenticated())
        val parentStore = ViewModelStore()
        val beforeRecreation = sessionOwner(parentStore, state)
        val first = sessionViewModel(beforeRecreation)

        val afterRecreation = sessionOwner(parentStore, state)
        val second = sessionViewModel(afterRecreation)

        assertSame(beforeRecreation, afterRecreation)
        assertSame(first, second)
        assertFalse(first.cleared)
        parentStore.clear()
    }

    @Test
    fun `clearing the Activity store clears session ViewModels`() {
        val state = MutableStateFlow<AppAuthState>(authenticated())
        val parentStore = ViewModelStore()
        val owner = sessionOwner(parentStore, state)
        val sessionViewModel = sessionViewModel(owner)

        parentStore.clear()

        assertTrue(sessionViewModel.cleared)
    }

    private fun sessionOwner(
        parentStore: ViewModelStore,
        state: MutableStateFlow<AppAuthState>,
    ): AuthenticatedSessionViewModelStoreOwner = ViewModelProvider(
        StoreOwner(parentStore),
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthenticatedSessionViewModelStoreOwner(state) as T
        },
    )["authenticated-session", AuthenticatedSessionViewModelStoreOwner::class.java]

    private fun sessionViewModel(
        owner: AuthenticatedSessionViewModelStoreOwner,
    ): TrackingViewModel = ViewModelProvider(
        owner,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackingViewModel() as T
        },
    )["home", TrackingViewModel::class.java]

    private fun address(url: String) = when (val parsed = parseServerAddress(url)) {
        is ServerAddressParseResult.Valid -> parsed.address
        is ServerAddressParseResult.Invalid -> error(parsed.message)
    }

    private fun authenticated(
        userId: Long = 1L,
        name: String = "Jose",
    ) = AppAuthState.Authenticated(
        address("http://igloo.test:8080"),
        AuthUser(
            id = userId,
            name = name,
            isAdmin = false,
            hasPin = false,
        ),
    )

    private class StoreOwner(
        override val viewModelStore: ViewModelStore,
    ) : ViewModelStoreOwner

    private class TrackingViewModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }
}
