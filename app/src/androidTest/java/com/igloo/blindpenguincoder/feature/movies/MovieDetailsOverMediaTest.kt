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
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.test.FakeImageLoaderEngine
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.testMovieDetails
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A movie page's hero with real decode outcomes. Every other instrumented suite nulls its image
 * URLs so nothing hits the network; here a fake Coil engine serves a solid-color image, or a
 * failure, for the URLs this suite opts into.
 *
 * The hero stands on the missing-media tile (section 3.2), so the over-media treatment — read
 * here as the side scrim's tag — is on from the first frame whatever the picture does. What the
 * picture's outcome decides is what lies under it: the backdrop, the poster standing in for a
 * backdrop that failed, or the tile alone.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class MovieDetailsOverMediaTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    /** Requests for the poster as the backdrop's blurred stand-in; the engine answers off the UI thread. */
    private val standInLoads = AtomicInteger()

    /** Successful answers for the backdrop, so a test can wait for a decode it then relies on. */
    private val backdropDecodes = AtomicInteger()

    @Before
    fun installFakeImageLoader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        standInLoads.set(0)
        backdropDecodes.set(0)
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it == DECODING_BACKDROP }) { chain ->
                backdropDecodes.incrementAndGet()
                SuccessResult(
                    image = ColorImage(BACKDROP_COLOR),
                    request = chain.request,
                    dataSource = DataSource.MEMORY,
                )
            }
            // The page's own poster figure asks for the same URL; only the stand-in's request
            // carries the blur.
            .intercept({ it == POSTER }) { chain ->
                if (chain.request.transformations.isNotEmpty()) standInLoads.incrementAndGet()
                SuccessResult(
                    image = ColorImage(BACKDROP_COLOR),
                    request = chain.request,
                    dataSource = DataSource.MEMORY,
                )
            }
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

    private fun setContent(backdropUrl: String, posterUrl: String? = null) {
        composeRule.setContent {
            IglooTheme {
                MovieDetailsScreen(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    state = DetailsState.Loaded(
                        testMovieDetails().copy(backdropUrl = backdropUrl, posterUrl = posterUrl),
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
    fun aDecodedBackdropShowsUnderTheOverMediaTreatment() {
        setContent(DECODING_BACKDROP, posterUrl = POSTER)

        composeRule.onNodeWithTag("details_backdrop_scrim").assertExists()
        // Wait for the backdrop's own answer: before it, nothing has had the chance to go wrong.
        composeRule.waitUntil(timeoutMillis = 5_000) { backdropDecodes.get() > 0 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("details_backdrop").assertExists()
        // The poster is only a stand-in: with a backdrop on screen it is never asked for.
        assertEquals(0, standInLoads.get())
    }

    @Test
    fun aFailedBackdropHandsItsPlaceToThePoster() {
        setContent(FAILING_BACKDROP, posterUrl = POSTER)

        // The stand-in is drawn in the backdrop's place, under the same tag, once the backdrop's
        // load has failed; the request for it is what tells the two apart.
        composeRule.waitUntil(timeoutMillis = 5_000) { standInLoads.get() > 0 }
        composeRule.onNodeWithTag("details_backdrop").assertExists()
        composeRule.onNodeWithTag("details_backdrop_scrim").assertExists()
    }

    @Test
    fun withNoPictureAtAllTheHeroStandsOnTheTileUnderTheSameTreatment() {
        setContent(FAILING_BACKDROP)

        // The error path is observable as the backdrop leaving the tree: it composes while the
        // request is in flight and is dropped when the load fails.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("details_backdrop")
                .fetchSemanticsNodes().isEmpty()
        }
        // The tile is still there, so the scrim and the white words are too.
        composeRule.onNodeWithTag("details_backdrop_scrim").assertExists()
    }

    private companion object {
        const val DECODING_BACKDROP = "https://igloo.test/backdrop.jpg"
        const val FAILING_BACKDROP = "https://igloo.test/missing.jpg"
        const val POSTER = "https://igloo.test/poster.jpg"
        val BACKDROP_COLOR = 0xFF224466.toInt()
    }
}
