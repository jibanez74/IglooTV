package com.igloo.blindpenguincoder.feature.auth

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.network.LOCAL_NETWORK_PERMISSION
import com.igloo.blindpenguincoder.core.network.LocalNetworkPermissionCoordinator
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely

val LocalNetworkRecovery = staticCompositionLocalOf { 0 }

@Composable
fun LocalNetworkPermissionHost(
    coordinator: LocalNetworkPermissionCoordinator,
    onChangeServer: () -> Unit,
    uiScale: UiScale = UiScale.Standard,
    content: @Composable () -> Unit,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        coordinator.onPermissionResult()
    }
    LaunchedEffect(state.requestId) {
        if (coordinator.claimPermissionRequest(state.requestId)) {
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
    LifecycleResumeEffect(coordinator) {
        coordinator.refresh()
        onPauseOrDispose { }
    }
    CompositionLocalProvider(LocalNetworkRecovery provides state.recovery, content = content)
    if (state.blockedHost != null && !state.requestPending) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            IglooTheme(uiScale = uiScale) {
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { retryFocus.requestFocusSafely() }
                AuthSurface(
                    title = "Allow local network access",
                    subtitle = "Connect to ${state.blockedHost}",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md)) {
                        IglooText(
                            text = "Igloo needs Nearby devices permission to reach your home server, artwork, and media. " +
                                "Your server address and saved profiles are kept. Public internet servers work without this permission.",
                            style = IglooTheme.typography.bodyLarge,
                            color = IglooTheme.colors.foreground,
                        )
                        IglooButton("Retry", coordinator::request, modifier = Modifier.focusRequester(retryFocus))
                        IglooButton("Open Settings", onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                            )
                        }, variant = IglooButtonVariant.Ghost)
                        IglooButton("Use another server", onClick = {
                            coordinator.cancelPending()
                            onChangeServer()
                        }, variant = IglooButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}
