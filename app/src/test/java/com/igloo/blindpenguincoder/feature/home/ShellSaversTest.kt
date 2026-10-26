package com.igloo.blindpenguincoder.feature.home

import androidx.compose.runtime.saveable.SaverScope
import com.igloo.blindpenguincoder.feature.movies.VideoLaunchSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shell's own savers come back from a bundle an older or newer build wrote: a rail or launch
 * site that no longer exists restores as no origin and no overlay, never as a crash.
 */
class ShellSaversTest {

    private val scope = SaverScope { true }

    @Test
    fun `a details origin round-trips through its saved string`() {
        for (origin in listOf(
            DetailsOrigin.Hero,
            DetailsOrigin.MoviesGrid,
            DetailsOrigin.ShowsGrid,
            DetailsOrigin.Music,
            DetailsOrigin.Search,
            DetailsOrigin.Rail(HomeRail.LatestAlbums),
            null,
        )) {
            val saved = with(DetailsOrigin.Saver) { scope.save(origin) }
            assertEquals(origin, DetailsOrigin.Saver.restore(checkNotNull(saved)))
        }
    }

    @Test
    fun `an unknown rail name restores as no origin`() {
        assertNull(DetailsOrigin.Saver.restore("RenamedRail"))
    }

    @Test
    fun `a trailer request round-trips through its slots`() {
        val request = TrailerRequest(
            key = "dQw4w9WgXcQ",
            title = "Heat",
            typeLabel = "Trailer",
            origin = VideoLaunchSite.ExtrasRail,
        )
        val saved = with(TrailerRequest.Saver) { scope.save(request) }
        assertEquals(request, TrailerRequest.Saver.restore(checkNotNull(saved)))
        assertNull(TrailerRequest.Saver.restore(emptyList()))
    }

    @Test
    fun `a trailer request with an unknown launch site or a short bundle restores as no overlay`() {
        assertNull(TrailerRequest.Saver.restore(listOf("key", "Heat", "Trailer", "Billboard")))
        assertNull(TrailerRequest.Saver.restore(listOf("key", "Heat", "Trailer")))
    }
}
