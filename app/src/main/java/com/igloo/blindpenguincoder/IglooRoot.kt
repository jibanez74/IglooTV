package com.igloo.blindpenguincoder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.auth.AppAuthState
import com.igloo.blindpenguincoder.feature.auth.LoginViewModel
import com.igloo.blindpenguincoder.feature.auth.QuickConnectViewModel
import com.igloo.blindpenguincoder.feature.auth.ServerSetupScreen
import com.igloo.blindpenguincoder.feature.auth.SignInScreen
import com.igloo.blindpenguincoder.feature.auth.ServerSetupViewModel
import com.igloo.blindpenguincoder.feature.home.IglooApp
import kotlinx.coroutines.launch

@Composable
fun IglooRoot(container: IglooAppContainer) {
    val uiScale by container.uiPreferencesStore.uiScale
        .collectAsStateWithLifecycle(initialValue = UiScale.Standard)

    IglooTheme(uiScale = uiScale) {
        val sessionManager = container.sessionManager
        val authState by sessionManager.state.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            sessionManager.restore()
        }

        when (val state = authState) {
            AppAuthState.Loading -> LoadingSplash()

            is AppAuthState.NeedsServer -> {
                val setupViewModel = viewModel(key = "server-setup") {
                    ServerSetupViewModel(container.serverRepository, sessionManager)
                }
                LaunchedEffect(setupViewModel, state) {
                    setupViewModel.beginSetup(state.initialOrigin)
                }
                ServerSetupScreen(setupViewModel)
            }

            is AppAuthState.NeedsLogin -> {
                val quickConnectViewModel = viewModel(key = "quick-connect") {
                    QuickConnectViewModel(container.authRepository, sessionManager)
                }
                val loginViewModel = viewModel(key = "login") {
                    LoginViewModel(container.authRepository, sessionManager)
                }
                SignInScreen(
                    quickConnectViewModel = quickConnectViewModel,
                    loginViewModel = loginViewModel,
                    serverOrigin = state.serverAddress.origin,
                    restoreError = state.restoreError,
                )
            }

            is AppAuthState.Authenticated -> {
                val scope = rememberCoroutineScope()
                IglooApp(
                    user = state.user,
                    onLogout = { scope.launch { sessionManager.logout() } },
                )
            }
        }
    }
}

@Composable
private fun LoadingSplash() {
    val colors = IglooTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .semantics { contentDescription = "Loading" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp.scaled())
                .clip(RoundedCornerShape(IglooTheme.radius.xl))
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            IglooText(
                text = "I",
                style = IglooTheme.typography.titleLarge,
                color = colors.primaryForeground,
            )
        }
    }
}
