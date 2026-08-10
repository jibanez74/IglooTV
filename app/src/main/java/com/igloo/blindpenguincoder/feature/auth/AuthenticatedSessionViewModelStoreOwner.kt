package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns ViewModels whose state is valid only for the current authenticated session. */
internal class AuthenticatedSessionViewModelStoreOwner(
    authState: StateFlow<AppAuthState>,
) : ViewModel(), ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var wasAuthenticated = authState.value is AppAuthState.Authenticated

    init {
        viewModelScope.launch {
            authState.collect { state ->
                val isAuthenticated = state is AppAuthState.Authenticated
                if (wasAuthenticated && !isAuthenticated) {
                    viewModelStore.clear()
                }
                wasAuthenticated = isAuthenticated
            }
        }
    }

    override fun onCleared() {
        viewModelStore.clear()
    }
}
