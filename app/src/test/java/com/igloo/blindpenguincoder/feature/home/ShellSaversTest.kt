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
    fun `a trailer request round-trips`() {
        val saved = with(TrailerRequestSaver) { scope.save(trailer) }
        assertEquals(trailer, TrailerRequestSaver.restore(checkNotNull(saved)))
        assertNull(TrailerRequestSaver.restore(""))
    }

    @Test
    fun `a trailer request with an unknown launch site or an old slot list restores as no overlay`() {
        val saved = with(TrailerRequestSaver) { scope.save(trailer) } as String
        assertNull(TrailerRequestSaver.restore(saved.replace("ExtrasRail", "Billboard")))
        assertNull(TrailerRequestSaver.restore(listOf("key", "Heat", "Trailer", "ExtrasRail")))
    }

    private val trailer = TrailerRequest(
        key = "dQw4w9WgXcQ",
        title = "Heat",
        typeLabel = "Trailer",
        origin = VideoLaunchSite.ExtrasRail,
    )
}
