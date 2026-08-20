package com.igloo.blindpenguincoder.core.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.design.IglooDarkColors
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeContinueMovie
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeMovie
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.testHomeMovies
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.inertDetailsActions

/** Pixel assertions for the modal's authored alpha reveal and single-scrim contract. */
@RunWith(AndroidJUnit4::class)
class IglooConfirmDialogMotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val density = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources.displayMetrics.density

    private val user = AuthUser(
        id = 1,
        name = "Jose",
        email = "jose@example.com",
        isAdmin = false,
        avatar = null,
        hasPin = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    // Five cards in the top (Continue Watching) rail, so the last one lies right of both the
    // expanded rail's overlay and the centered 480dp dialog card — the one place a scrimmed
    // content pixel stays visible through the modal's whole reveal. Small fractions keep the
    // sampled left sliver above the poster's bottom edge on the muted placeholder fill.
    private val continueMovies = (
        testHomeMovies + listOf(
            HomeMovie(id = 4, title = "Solaris", year = 1972, posterUrl = null),
            HomeMovie(id = 5, title = "Alien", year = 1979, posterUrl = null),
        )
        ).map { HomeContinueMovie(it, progressFraction = 0.2f, progressLabel = "90 min left") }

    @Test
    fun revealStartsTransparentAndReachesFullOpacityAfterStandardDuration() {
        composeRule.mainClock.autoAdvance = false
        setDialog(reducedMotion = false)

        // This frame runs LaunchedEffect and establishes animation play time zero.
        composeRule.mainClock.advanceTimeByFrame()
        val initial = revealFraction(captureCorner())

        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS / 2L)
        val middle = revealFraction(captureCorner())

        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS / 2L + FRAME_MILLIS)
        val full = revealFraction(captureCorner())

        assertTrue("reveal did not start below full opacity: $initial", initial < 0.10f)
        assertTrue("reveal did not progress by its midpoint: $middle", middle > initial + 0.10f)
        assertTrue("reveal reached full opacity too early: $middle", middle < 0.98f)
        assertTrue("reveal did not reach full opacity: $full", abs(full - 1f) < 0.03f)
    }

    @Test
    fun revealSnapsWhenReducedMotionIsEnabled() {
        composeRule.mainClock.autoAdvance = false
        setDialog(reducedMotion = true)

        // Two render frames are still far shorter than standard; snap must already be at 1.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        val reveal = revealFraction(captureCorner())
        assertTrue("reduced-motion reveal did not snap: $reveal", abs(reveal - 1f) < 0.03f)
    }

    @Test
    fun modalImmediatelyReplacesTheExpandedRailScrim() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            var signOut by remember { mutableStateOf(SignOutUiState()) }
            IglooTheme {
                CompositionLocalProvider(LocalIglooReducedMotion provides false) {
                    IglooApp(
                        // Pinned: the Shield test device runs TalkBack, and this suite
                        // asserts the focus chain without the reading stops.
                        spokenAccessibilityEnabled = false,
                        user = user,
                        serverOrigin = "http://igloo.test:8080",
                        signOut = signOut,
                        // Hero hidden — a legitimate 11.3.1 state — so the sampled rail-card
                        // bounds stay on the 540dp viewport; this test's subject is the scrim.
                        home = HomeUiState(
                            hero = HomeHeroState.Hidden,
                            continueWatching = IglooRailState.Loaded(continueMovies),
                            latestMovies = IglooRailState.Loaded(testHomeMovies),
                        ),
                        onRetryRail = {},
                        onMovieSelected = null,
                        onTheaterMovieSelected = null,
                        onCloseDetails = {},
                        details = MovieDetailsUiState(),
                        detailsActions = inertDetailsActions,
                        onSwitchProfile = {},
                        onSignOut = { signOut = SignOutUiState(confirming = true) },
                        onSignOutConfirm = { signOut = SignOutUiState() },
                        onSignOutDismiss = { signOut = SignOutUiState() },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val contentCard = composeRule.onNodeWithTag("continue_card_1")
        contentCard.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS.toLong() + FRAME_MILLIS)
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        // The rail is expanded because Home still owns focus. Semantics click invokes the same
        // row without making focus traversal geometry part of this pixel-only assertion.
        val signOut = composeRule.onNodeWithContentDescription("Sign out")

        // The last card's poster is the placeholder's muted fill; its left sliver stays left
        // of the centered film glyph and inside the screen even where the pane clips the
        // peeking card.
        val cardBounds = composeRule.onNodeWithTag("continue_card_5").getUnclippedBoundsInRoot()
        val railDimmed = blend(
            background = IglooDarkColors.muted.toArgb(),
            foreground = IglooDarkColors.background.toArgb(),
            alpha = SCRIM_ALPHA,
        )
        val baseline = captureRoot()
        val cardLeft = cardBounds.left.value * density
        val cardTop = cardBounds.top.value * density
        val cardWidth = (cardBounds.right.value - cardBounds.left.value) * density
        val posterBottom = cardTop + cardWidth * 1.5f
        val sample = findClosestPixel(
            bitmap = baseline,
            expected = railDimmed,
            left = (cardLeft + EDGE_INSET_PX).roundToInt(),
            top = (cardTop + EDGE_INSET_PX).roundToInt(),
            right = (cardLeft + cardWidth * 0.35f).roundToInt(),
            bottom = (posterBottom - EDGE_INSET_PX).roundToInt(),
        )
        assertTrue(
            "expanded rail did not render one 0.60 scrim at the sample: ${sample.distance}",
            sample.distance < COLOR_TOLERANCE,
        )

        signOut.performClick()
        // With autoAdvance disabled, publish the state change and establish the reveal's time zero.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Sign out of Igloo?").assertExists()

        // At animation time zero the modal is transparent. The rail scrim must already be
        // unmounted, leaving the original poster pixel rather than an animating second layer.
        val revealStart = captureRoot().getPixel(sample.x, sample.y)
        assertTrue(
            "rail scrim remained under the modal reveal",
            colorDistance(revealStart, IglooDarkColors.muted.toArgb()) < COLOR_TOLERANCE,
        )

        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS.toLong() + FRAME_MILLIS)
        val revealed = captureRoot().getPixel(sample.x, sample.y)
        assertTrue(
            "fully revealed modal did not produce exactly one 0.60 scrim",
            colorDistance(revealed, railDimmed) < COLOR_TOLERANCE,
        )
    }

    private fun setDialog(reducedMotion: Boolean) {
        composeRule.setContent {
            IglooTheme {
                CompositionLocalProvider(LocalIglooReducedMotion provides reducedMotion) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White),
                    ) {
                        IglooConfirmDialog(
                            title = "Sign out of Igloo?",
                            body = "Jose will be removed from this TV.",
                            confirmText = "Sign out",
                            dismissText = "Cancel",
                            onConfirm = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    private fun captureCorner(): Int {
        val bitmap = captureRoot()
        return bitmap.getPixel(bitmap.width - 2, 1)
    }

    private fun captureRoot(): Bitmap =
        composeRule.onRoot().captureToImage().asAndroidBitmap()

    /** The white canvas and dark scrim make the red channel a direct effective-alpha probe. */
    private fun revealFraction(pixel: Int): Float {
        val canvas = 255f
        val scrim = AndroidColor.red(IglooDarkColors.background.toArgb()).toFloat()
        val effectiveAlpha = (canvas - AndroidColor.red(pixel)) / (canvas - scrim)
        return effectiveAlpha / SCRIM_ALPHA
    }

    private fun blend(background: Int, foreground: Int, alpha: Float): Int =
        AndroidColor.rgb(
            blendChannel(AndroidColor.red(background), AndroidColor.red(foreground), alpha),
            blendChannel(AndroidColor.green(background), AndroidColor.green(foreground), alpha),
            blendChannel(AndroidColor.blue(background), AndroidColor.blue(foreground), alpha),
        )

    private fun blendChannel(background: Int, foreground: Int, alpha: Float): Int =
        (background * (1f - alpha) + foreground * alpha).roundToInt()

    private fun findClosestPixel(
        bitmap: Bitmap,
        expected: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): PixelSample {
        var closest = PixelSample(left, top, Float.MAX_VALUE)
        for (y in top until bottom) {
            for (x in left until right) {
                val distance = colorDistance(bitmap.getPixel(x, y), expected)
                if (distance < closest.distance) closest = PixelSample(x, y, distance)
            }
        }
        return closest
    }

    private fun colorDistance(first: Int, second: Int): Float =
        abs(AndroidColor.red(first) - AndroidColor.red(second)) +
            abs(AndroidColor.green(first) - AndroidColor.green(second)) +
            abs(AndroidColor.blue(first) - AndroidColor.blue(second)).toFloat()

    private data class PixelSample(val x: Int, val y: Int, val distance: Float)

    private companion object {
        const val FRAME_MILLIS = 16L
        const val EDGE_INSET_PX = 12f
        const val COLOR_TOLERANCE = 10f
    }
}
