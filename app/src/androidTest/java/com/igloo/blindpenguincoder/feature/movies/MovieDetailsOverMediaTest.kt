package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The section 3.2 branch, exercised with a genuinely decoded backdrop. Every other instrumented
 * suite runs the token branch on purpose — the fixtures null their image URLs so nothing hits
 * the network — which left `overMedia` with no coverage at all. A fake Coil engine serves a
 * solid-color image (or a failure) for the one URL this suite opts into, so the gate is driven
 * by real decode outcomes rather than by the URL being non-null: a URL whose load fails must
 * fall back to the token branch, not paint white over the token canvas.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class MovieDetailsOverMediaTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Before
    fun installFakeImageLoader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it == DECODING_BACKDROP }, ColorImage(BACKDROP_COLOR))
            .intercept({ it == FAILING_BACKDROP }) { chain ->
                ErrorResult(
                    image = null,
                    request = chain.request,
                    throwable = IllegalStateException("backdrop failed to load"),
                )
            }
            .default(ColorImage(BACKDROP_COLOR))
            .build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(engine) }.build(),
        )
    }

    @After
    fun resetImageLoader() {
        SingletonImageLoader.reset()
    }

    private fun setContent(backdropUrl: String) {
        composeRule.setContent {
            IglooTheme {
                MovieDetailsScreen(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    state = MovieDetailsState.Loaded(
                        testMovieDetails().copy(backdropUrl = backdropUrl),
                    ),
                    actions = inertDetailsActions,
                    isAdmin = false,
                    onPlay = {},
                    playReturnRequester = remember { FocusRequester() },
                    onPlayVideo = { _, _ -> },
                    extrasReturnRequester = remember { FocusRequester() },
                    heroTrailerReturnRequester = remember { FocusRequester() },
                    moreMenuOpen = false,
                    onOpenMoreMenu = {},
                    onDismissMoreMenu = {},
                    moreRequester = remember { FocusRequester() },
                    playbackSettingsOpen = false,
                    onOpenPlaybackSettings = {},
                    onDismissPlaybackSettings = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aDecodedBackdropTurnsTheOverMediaTreatmentOn() {
        setContent(DECODING_BACKDROP)

        // The scrim's tag appears only once `overMedia` is true, which requires the decode to
        // have actually landed — a non-null URL alone must not license the white treatment.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("details_backdrop_scrim")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("details_backdrop").assertExists()
    }

    @Test
    fun aFailedBackdropKeepsTheTokenBranch() {
        setContent(FAILING_BACKDROP)

        // The error path is observable as the backdrop leaving the tree: it composes while the
        // request is in flight and is dropped when the load fails.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("details_backdrop")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("details_backdrop_scrim").assertDoesNotExist()
    }

    private companion object {
        const val DECODING_BACKDROP = "https://igloo.test/backdrop.jpg"
        const val FAILING_BACKDROP = "https://igloo.test/missing.jpg"
        val BACKDROP_COLOR = 0xFF224466.toInt()
    }
}
