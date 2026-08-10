package com.igloo.blindpenguincoder.feature.auth

import androidx.activity.compose.BackHandler
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
    canCancel: Boolean,
    notice: String? = null,
) {
    var mode by rememberSaveable { mutableStateOf(SignInMode.QuickConnect) }

    // Reached by adding a user, this screen sits below the picker, so Back belongs to it — the
    // same reason the PIN gate handles its own. Left unhandled during first setup and after the
    // last profile signs out, where there is nothing behind it and exiting the app is right.
    BackHandler(enabled = canCancel, onBack = loginViewModel::cancel)

    when (mode) {
        SignInMode.QuickConnect -> QuickConnectScreen(
            viewModel = quickConnectViewModel,
            serverOrigin = serverOrigin,
            restoreError = restoreError,
            canCancel = canCancel,
            onRetryRestore = loginViewModel::retryRestore,
            onSwitchToPassword = { mode = SignInMode.Password },
            onLeave = if (canCancel) loginViewModel::cancel else loginViewModel::changeServer,
            notice = notice,
        )
        SignInMode.Password -> LoginScreen(
            viewModel = loginViewModel,
            serverOrigin = serverOrigin,
            restoreError = restoreError,
            canCancel = canCancel,
            onSwitchToQuickConnect = { mode = SignInMode.QuickConnect },
            notice = notice,
        )
    }
}

/**
 * Changing the server wipes every stored profile, so while adding a user the same slot
 * offers a way back to the picker instead.
 */
internal fun leaveActionText(canCancel: Boolean) =
    if (canCancel) "Back to profiles" else "Change server"

internal fun leaveActionSemanticLabel(canCancel: Boolean) =
    if (canCancel) "Back to profiles" else "Change server address"
