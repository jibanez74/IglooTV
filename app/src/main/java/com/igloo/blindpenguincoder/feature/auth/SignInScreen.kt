package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.igloo.blindpenguincoder.core.error.AppError

enum class SignInMode { QuickConnect, Password }

/** Hosts the two sign-in modes; Quick Connect is the default. */
@Composable
fun SignInScreen(
    quickConnectViewModel: QuickConnectViewModel,
    loginViewModel: LoginViewModel,
    serverOrigin: String,
    restoreError: AppError?,
) {
    var mode by rememberSaveable { mutableStateOf(SignInMode.QuickConnect) }

    when (mode) {
        SignInMode.QuickConnect -> QuickConnectScreen(
            viewModel = quickConnectViewModel,
            serverOrigin = serverOrigin,
            restoreError = restoreError,
            onRetryRestore = loginViewModel::retryRestore,
            onSwitchToPassword = { mode = SignInMode.Password },
            onChangeServer = loginViewModel::changeServer,
        )
        SignInMode.Password -> LoginScreen(
            viewModel = loginViewModel,
            serverOrigin = serverOrigin,
            restoreError = restoreError,
            onSwitchToQuickConnect = { mode = SignInMode.QuickConnect },
        )
    }
}
