package com.igloo.blindpenguincoder.feature.player

import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter

/**
 * The chapter the playhead is in: the last one starting at or before [currentTimeSec], or -1
 * before the first chapter begins. Assumes the list is ascending by start time — the request
 * mapping sorts it. Web parity: ChapterMenu.tsx scans the same way.
 */
internal fun activeChapterIndex(chapters: List<PlaybackChapter>, currentTimeSec: Double): Int =
    chapters.indexOfLast { it.startTimeSec <= currentTimeSec }

/** The row text; file metadata often leaves titles blank, so "Chapter N" stands in. */
internal fun chapterLabel(chapter: PlaybackChapter, index: Int): String =
    chapter.title.trim().ifEmpty { "Chapter ${index + 1}" }

/**
 * The row's spoken sentence: position in the list, the title when there is one, and the start
 * spoken as time words. No "current chapter" suffix — the row's radio semantics already carry
 * the selected state.
 */
internal fun chapterSpokenLabel(chapter: PlaybackChapter, index: Int, count: Int): String {
    val title = chapter.title.trim()
    return listOfNotNull(
        "Chapter ${index + 1} of $count",
        title.ifEmpty { null },
        "starts at ${formatSpokenTime(chapter.startTimeSec)}",
    ).joinToString(", ")
}
