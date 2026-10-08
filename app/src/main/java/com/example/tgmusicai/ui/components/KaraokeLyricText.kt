package com.example.tgmusicai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.tgmusicai.data.repository.LyricLine

/**
 * Colours a lyric line word by word as it is sung, for the karaoke highlight on the active line.
 *
 * Returns a single [AnnotatedString] rather than laying each word out separately. A row of
 * per-word composables is the obvious approach and it breaks two things that already work here:
 * long lines stop wrapping the way a plain `Text` wraps them, and any per-word size change reflows
 * the line's height mid-scroll -- the exact stutter that
 * [com.example.tgmusicai.ui.screens.NowPlayingScreen] already notes it had to remove from the
 * active-line animation. Styling spans inside one `Text` leaves the layout byte-for-byte identical
 * to the non-karaoke path, so the highlight can only ever change colour.
 *
 * The rendered text is [LyricLine.text] exactly: segments are matched back into the original line
 * in order, and the whitespace between them is appended unstyled, so nothing is dropped or
 * reordered even if a segment somehow fails to match.
 */
fun karaokeLine(
    line: LyricLine,
    positionMs: Long,
    sungColor: Color,
    unsungColor: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (word in line.words) {
        val start = line.text.indexOf(word.text, cursor)
        if (start < 0) continue
        if (start > cursor) append(line.text.substring(cursor, start))
        withStyle(SpanStyle(color = sungFraction(word.startMs, word.durationMs, positionMs).let {
            lerp(unsungColor, sungColor, it)
        })) {
            append(word.text)
        }
        cursor = start + word.text.length
    }
    if (cursor < line.text.length) append(line.text.substring(cursor))
}

/**
 * How much of a word has been sung at [positionMs], from 0 to 1.
 *
 * Words already passed stay at 1 rather than reverting, so a line reads as filling up left to
 * right instead of a single dot travelling along it.
 */
private fun sungFraction(startMs: Long, durationMs: Long, positionMs: Long): Float = when {
    positionMs >= startMs + durationMs -> 1f
    positionMs <= startMs -> 0f
    durationMs <= 0L -> 1f
    else -> ((positionMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * [reportedPositionMs], advanced on every frame between the updates it actually arrives on.
 *
 * The player's position reaches the UI from a 500ms ticker, which is ample for a progress bar and
 * far too coarse here: a sung word lasts a few hundred milliseconds, so highlighting straight from
 * the ticker lights words up two or three at a time in visible steps. This anchors on each reported
 * value and interpolates forward with the frame clock, so the fill moves smoothly while staying
 * tied to the real playback position -- every tick re-anchors it, so it can never drift.
 *
 * Returns [reportedPositionMs] unchanged while paused, so the highlight holds where the music did.
 */
@Composable
fun rememberSmoothedPositionMs(reportedPositionMs: Long, isPlaying: Boolean): Long {
    var smoothed by remember { mutableLongStateOf(reportedPositionMs) }
    LaunchedEffect(reportedPositionMs, isPlaying) {
        smoothed = reportedPositionMs
        if (!isPlaying) return@LaunchedEffect
        val anchor = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame -> smoothed = reportedPositionMs + (frame - anchor) }
        }
    }
    return smoothed
}
