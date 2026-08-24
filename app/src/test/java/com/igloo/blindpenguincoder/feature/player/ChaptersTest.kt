package com.igloo.blindpenguincoder.feature.player

import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChaptersTest {

    private val chapters = listOf(
        PlaybackChapter(title = "Opening Credits", startTimeSec = 10.0),
        PlaybackChapter(title = "", startTimeSec = 600.0),
        PlaybackChapter(title = "The Heist", startTimeSec = 1800.0),
    )

    @Test
    fun `before the first chapter no chapter is active`() {
        assertEquals(-1, activeChapterIndex(chapters, 0.0))
        assertEquals(-1, activeChapterIndex(chapters, 9.9))
    }

    @Test
    fun `a chapter is active from exactly its start time`() {
        assertEquals(0, activeChapterIndex(chapters, 10.0))
        assertEquals(1, activeChapterIndex(chapters, 600.0))
    }

    @Test
    fun `between two starts the earlier chapter is active`() {
        assertEquals(0, activeChapterIndex(chapters, 599.9))
        assertEquals(1, activeChapterIndex(chapters, 1799.0))
    }

    @Test
    fun `past the last start the last chapter is active`() {
        assertEquals(2, activeChapterIndex(chapters, 7200.0))
    }

    @Test
    fun `an empty chapter list has no active chapter`() {
        assertEquals(-1, activeChapterIndex(emptyList(), 100.0))
    }

    @Test
    fun `a real title passes through and a blank one falls back to its number`() {
        assertEquals("Opening Credits", chapterLabel(chapters[0], 0))
        assertEquals("Chapter 2", chapterLabel(chapters[1], 1))
        assertEquals(
            "Chapter 3",
            chapterLabel(PlaybackChapter(title = "   ", startTimeSec = 0.0), 2),
        )
    }

    @Test
    fun `the spoken label names position, title, and spoken start time`() {
        assertEquals(
            "Chapter 3 of 3, The Heist, starts at 30 minutes",
            chapterSpokenLabel(chapters[2], 2, chapters.size),
        )
    }

    @Test
    fun `a blank title drops its segment instead of repeating the chapter number`() {
        assertEquals(
            "Chapter 2 of 3, starts at 10 minutes",
            chapterSpokenLabel(chapters[1], 1, chapters.size),
        )
    }

    /** The row's radio semantics already announce selection; the sentence must not repeat it. */
    @Test
    fun `the spoken label never claims to be the current chapter`() {
        chapters.forEachIndexed { index, chapter ->
            val spoken = chapterSpokenLabel(chapter, index, chapters.size)
            assertFalse(spoken.contains("current", ignoreCase = true))
        }
    }
}
