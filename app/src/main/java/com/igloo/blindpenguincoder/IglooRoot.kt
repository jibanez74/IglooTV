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
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.auth.AppAuthState
import com.igloo.blindpenguincoder.feature.auth.LoginScreen
import com.igloo.blindpenguincoder.feature.auth.LoginViewModel
import com.igloo.blindpenguincoder.feature.auth.ServerSetupScreen
import com.igloo.blindpenguincoder.feature.auth.ServerSetupViewModel
import com.igloo.blindpenguincoder.feature.home.IglooApp
import kotlinx.coroutines.launch

@Composable
fun IglooRoot(container: IglooAppContainer) {
    IglooTheme {
        val sessionManager = container.sessionManager
        val authState by sessionManager.state.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            sessionManager.restore()
        }

        when (val state = authState) {
            AppAuthState.Loading -> LoadingSplash()

            AppAuthState.NeedsServer -> {
                val setupViewModel = viewModel {
                    ServerSetupViewModel(container.serverRepository, sessionManager)
                }
                ServerSetupScreen(setupViewModel)
            }

            is AppAuthState.NeedsLogin -> {
                val loginViewModel = viewModel {
                    LoginViewModel(container.authRepository, sessionManager)
                }
                LoginScreen(
                    viewModel = loginViewModel,
                    serverUrl = state.serverUrl,
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
                .size(64.dp)
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
