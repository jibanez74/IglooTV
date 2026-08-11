package com.igloo.blindpenguincoder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.feature.auth.AppAuthState
import com.igloo.blindpenguincoder.feature.auth.AuthenticatedSessionViewModelStoreOwner
import com.igloo.blindpenguincoder.feature.auth.LoginViewModel
import com.igloo.blindpenguincoder.feature.auth.PinEntryScreen
import com.igloo.blindpenguincoder.feature.auth.PinEntryViewModel
import com.igloo.blindpenguincoder.feature.auth.ProfilePickerScreen
import com.igloo.blindpenguincoder.feature.auth.ProfilePickerViewModel
import com.igloo.blindpenguincoder.feature.auth.QuickConnectViewModel
import com.igloo.blindpenguincoder.feature.auth.ServerSetupScreen
import com.igloo.blindpenguincoder.feature.auth.SignInScreen
import com.igloo.blindpenguincoder.feature.auth.ServerSetupViewModel
import com.igloo.blindpenguincoder.feature.auth.WelcomeScreen
import com.igloo.blindpenguincoder.feature.boot.SplashScreen
import com.igloo.blindpenguincoder.feature.home.HomeViewModel
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IglooRoot(container: IglooAppContainer) {
    val uiScale by container.uiPreferencesStore.uiScale
        .collectAsStateWithLifecycle(initialValue = UiScale.Standard)

    IglooTheme(uiScale = uiScale) {
        val sessionManager = container.sessionManager
        val authState by sessionManager.state.collectAsStateWithLifecycle()
        val authenticatedSessionOwner = viewModel {
            AuthenticatedSessionViewModelStoreOwner(sessionManager.state)
        }

        LaunchedEffect(Unit) {
            sessionManager.restore()
        }

        // Gated on the boot rather than on `Loading`: once any other state has been seen the
        // launch is over, so a flow that ever returns to `Loading` gets a pending screen instead
        // of a full-screen brand moment. See docs/design-system.md section 11.1.-1.
        // Plain `remember`, not `rememberSaveable`: SessionManager dies with the process, so a
        // restore from the saved Bundle starts over at `Loading`. A saved `booted` would skip the
        // splash and leave the empty `Loading` arm on screen for the whole of `restore()`.
        var booted by remember { mutableStateOf(false) }
        var holdElapsed by remember { mutableStateOf(false) }
        LaunchedEffect(authState) {
            if (authState !is AppAuthState.Loading) booted = true
        }
        LaunchedEffect(Unit) {
            delay(IglooMotion.SPLASH_HOLD_MS.toLong())
            holdElapsed = true
        }

        val splashShown = splashVisible(booted = booted, holdElapsed = holdElapsed)
        val splashAlpha by animateFloatAsState(
            targetValue = if (splashShown) 1f else 0f,
            animationSpec = iglooTween(IglooMotion.PAGE_MS),
            label = "splashExit",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                // The screen below takes focus while the splash is still opaque, so an OK pressed
                // during the brand moment would activate a control nobody can see. Only the
                // confirm keys are swallowed: arrows merely move focus, and the user sees where
                // it landed the moment the splash lifts, whereas blocking those too would leave
                // the remote dead for the whole hold. Key events preview from the root down
                // through the focused node's ancestors, so this needs no focusable of its own.
                .onPreviewKeyEvent { splashShown && it.key in CONFIRM_KEYS },
        ) {
            when (val state = authState) {
                // Only ever the state before the first restore, which the splash covers. A
                // later re-entry would need a pending screen of its own (design system section 10).
                AppAuthState.Loading -> Unit

                is AppAuthState.NeedsServer -> {
                    var started by rememberSaveable { mutableStateOf(false) }
                    if (state.firstRun && !started) {
                        WelcomeScreen(onGetStarted = { started = true })
                    } else {
                        val setupViewModel = viewModel(key = "server-setup") {
                            ServerSetupViewModel(container.serverRepository, sessionManager)
                        }
                        LaunchedEffect(setupViewModel, state) {
                            setupViewModel.beginSetup(state.initialOrigin)
                        }
                        ServerSetupScreen(setupViewModel)
                    }
                }

                is AppAuthState.ChooseProfile -> {
                    val pickerViewModel = viewModel(key = "profile-picker") {
                        ProfilePickerViewModel(sessionManager)
                    }
                    // Both gates keep Activity-scoped ViewModels, so an attempt from an earlier
                    // visit has to be cleared as the gate opens. Keyed on the ViewModel and not
                    // on the state: a re-published gate carrying a notice must not wipe an error
                    // the user has not read yet.
                    LaunchedEffect(pickerViewModel) { pickerViewModel.reset() }
                    ProfilePickerScreen(viewModel = pickerViewModel, state = state)
                }

                is AppAuthState.NeedsPin -> {
                    val pinViewModel = viewModel(key = "pin-entry-${state.profile.userId}") {
                        PinEntryViewModel(container.authRepository, sessionManager)
                    }
                    LaunchedEffect(pinViewModel) { pinViewModel.reset() }
                    PinEntryScreen(viewModel = pinViewModel, state = state)
                }

                is AppAuthState.NeedsLogin -> {
                    val quickConnectViewModel = viewModel(key = "quick-connect") {
                        QuickConnectViewModel(
                            container.authRepository,
                            container.profileRepository,
                            sessionManager,
                        )
                    }
                    val loginViewModel = viewModel(key = "login") {
                        LoginViewModel(container.authRepository, sessionManager)
                    }
                    SignInScreen(
                        quickConnectViewModel = quickConnectViewModel,
                        loginViewModel = loginViewModel,
                        serverOrigin = state.serverAddress.origin,
                        restoreError = state.restoreError,
                        canCancel = state.canCancel,
                        notice = state.notice,
                    )
                }

                is AppAuthState.Authenticated -> {
                    val scope = rememberCoroutineScope()
                    // A ViewModel, not this arm's scope: the revoke has to survive the arm being
                    // disposed mid-request, or a cancelled logout leaves the credential on the TV.
                    val signOutViewModel = viewModel(key = "sign-out") {
                        SignOutViewModel(sessionManager)
                    }
                    val signOut by signOutViewModel.uiState.collectAsStateWithLifecycle()
                    val homeViewModel = viewModel(
                        viewModelStoreOwner = authenticatedSessionOwner,
                        key = "home",
                    ) {
                        HomeViewModel(
                            container.movieRepository,
                            container.musicRepository,
                            container.serverUrlProvider,
                        )
                    }
                    // Device tokens are revoked server-side after long disuse, so a session
                    // resumed from the background is re-checked before it is trusted — and the
                    // library is re-read, because a TV can sit on this screen for days. The
                    // first START is also the first load; the view model has no init fetch.
                    LifecycleStartEffect(homeViewModel) {
                        scope.launch { sessionManager.revalidateActive() }
                        homeViewModel.refresh()
                        onStopOrDispose { }
                    }
                    val home by homeViewModel.uiState.collectAsStateWithLifecycle()
                    IglooApp(
                        user = state.user,
                        signOut = signOut,
                        home = home,
                        onRetryRail = homeViewModel::retry,
                        // Null until the details screen lands: the cards stay focus targets, but
                        // must not announce an action nothing implements.
                        onMovieSelected = null,
                        onSwitchProfile = { scope.launch { sessionManager.switchProfile() } },
                        onSignOut = signOutViewModel::request,
                        onSignOutConfirm = signOutViewModel::confirm,
                        onSignOutDismiss = signOutViewModel::dismiss,
                    )
                }
            }

            // An overlay rather than a Crossfade: the screen beneath composes and requests focus
            // on its own schedule, and only the pixels above it fade. Nothing here is focusable.
            if (splashAlpha > 0f) {
                SplashScreen(
                    modifier = Modifier.graphicsLayer { alpha = splashAlpha },
                    announce = splashShown,
                )
            }
        }
    }
}

/**
 * The splash covers the boot: until the session has resolved once, and never for less than
 * [IglooMotion.SPLASH_HOLD_MS]. Extracted so the gate is testable without a device.
 */
internal fun splashVisible(booted: Boolean, holdElapsed: Boolean): Boolean =
    !booted || !holdElapsed

/** What `Modifier.clickable` treats as a press — the keys that would activate an unseen control. */
private val CONFIRM_KEYS = setOf(
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
    Key.Spacebar,
    Key.ButtonA,
)
