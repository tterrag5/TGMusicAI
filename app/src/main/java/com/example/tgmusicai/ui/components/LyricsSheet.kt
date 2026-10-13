package com.example.tgmusicai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.LyricLine
import com.example.tgmusicai.ui.util.FormatUtils

/**
 * How long the lyrics take to glide from one line to the next. Long enough to read as movement
 * rather than a jump, short enough that the line is in place while it is still being sung.
 */
private const val LYRICS_SCROLL_DURATION_MS = 420

/**
 * How far ahead of playback a line is treated as active: the glide takes about this long, and LRC
 * timestamps mark where a line sits in the file rather than the instant the vocal starts.
 */
private const val LYRICS_SYNC_LEAD_MS = 400L

/** Where the active line comes to rest, as a fraction down the lyrics viewport. */
private const val LYRICS_ACTIVE_LINE_ANCHOR = 0.3f

/** Auto-scroll stays out of the way this long after the user scrolls the lyrics by hand. */
private const val LYRICS_MANUAL_SCROLL_GRACE_MS = 3000L

/**
 * Full-height lyrics sheet modelled on YT Music Morphe: a compact player header (cover, title,
 * artist, previous / play / next), then big left-aligned lines where the one being sung is bright
 * and the rest are dimmed, and Copy / Translate / Refresh along the bottom. Tapping a timed line
 * seeks to it. The word-by-word fill only runs when the source timed each word.
 */
@Composable
fun LyricsSheet(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    lines: List<LyricLine>,
    translatedLines: List<String>?,
    isSearching: Boolean,
    isTranslating: Boolean,
    translationLanguage: String,
    tint: Color,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCopy: () -> Unit,
    onToggleTranslation: () -> Unit,
    onPickLanguage: (String) -> Unit,
    onSearchAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bright = Color.White
    val dim = Color.White.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, Color.Black)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Compact player header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (!song?.artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(song?.artworkUri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = dim)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title.orEmpty(),
                    color = bright,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.artist.orEmpty(),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Skip Previous", tint = bright)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = bright
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Skip Next", tint = bright)
            }
        }

        // Grab handle + title row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lyrics",
                color = bright,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close lyrics", tint = bright)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(color = bright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = if (isSearching) "Looking for lyrics..." else "No lyrics found for this song",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (!isSearching) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onSearchAgain,
                            colors = ButtonDefaults.buttonColors(containerColor = bright, contentColor = Color.Black)
                        ) { Text("Search again") }
                    }
                }
            } else {
                SyncedLyricsList(
                    lines = lines,
                    translatedLines = translatedLines,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    bright = bright,
                    dim = dim,
                    onSeek = onSeek,
                )
            }
        }

        if (lines.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LyricsActionButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy lyrics", tint = bright, modifier = Modifier.size(20.dp))
                }
                Box {
                    var showLanguages by remember { mutableStateOf(false) }
                    LyricsActionButton(onClick = { showLanguages = true }) {
                        if (isTranslating) {
                            CircularProgressIndicator(color = bright, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(
                                Icons.Rounded.Translate,
                                contentDescription = "Translate lyrics",
                                tint = if (translatedLines != null) MaterialTheme.colorScheme.primary else bright,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = showLanguages, onDismissRequest = { showLanguages = false }) {
                        DropdownMenuItem(
                            text = { Text(if (translatedLines != null) "Hide translation" else "Translate to $translationLanguage") },
                            onClick = { showLanguages = false; onToggleTranslation() }
                        )
                        HorizontalDivider()
                        TRANSLATION_LANGUAGES.forEach { language ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = language,
                                        fontWeight = if (language == translationLanguage) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = { showLanguages = false; onPickLanguage(language) }
                            )
                        }
                    }
                }
                LyricsActionButton(onClick = onSearchAgain) {
                    if (isSearching) {
                        CircularProgressIndicator(color = bright, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Search lyrics again", tint = bright, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private val TRANSLATION_LANGUAGES = listOf(
    "English", "Spanish", "French", "German", "Portuguese", "Italian",
    "Japanese", "Korean", "Chinese (Simplified)", "Arabic", "Hindi",
)

@Composable
private fun LyricsActionButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    translatedLines: List<String>?,
    positionMs: Long,
    isPlaying: Boolean,
    bright: Color,
    dim: Color,
    onSeek: (Long) -> Unit,
) {
    val cursorMs = positionMs + LYRICS_SYNC_LEAD_MS
    val activeIndex = remember(cursorMs, lines) {
        var index = -1
        for (i in lines.indices) {
            val ts = lines[i].timestampMs
            if (ts in 0..cursorMs) index = i else if (ts > cursorMs) break
        }
        index
    }
    val active = lines.getOrNull(activeIndex)
    val activeHasRealWords = active?.let { it.hasRealWordTimings && it.words.isNotEmpty() } == true
    // A finer clock than the position ticker, only while a word-timed line is being sung.
    val smoothedMs = rememberSmoothedPositionMs(cursorMs, isPlaying && activeHasRealWords)

    val listState = rememberLazyListState()
    var lastManualScrollMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) lastManualScrollMs = System.currentTimeMillis()
    }
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0 || listState.isScrollInProgress) return@LaunchedEffect
        if (System.currentTimeMillis() - lastManualScrollMs < LYRICS_MANUAL_SCROLL_GRACE_MS) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (item != null) {
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val restingPoint = info.viewportStartOffset + viewport * LYRICS_ACTIVE_LINE_ANCHOR
            listState.animateScrollBy(
                (item.offset + item.size / 2f) - restingPoint,
                animationSpec = tween(LYRICS_SCROLL_DURATION_MS, easing = FastOutSlowInEasing)
            )
        } else {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 200.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { index, line ->
            val isActive = index == activeIndex
            val color by animateColorAsState(if (isActive) bright else dim, tween(250), label = "lyricLine")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = line.timestampMs >= 0) { onSeek(line.timestampMs) }
                    .padding(vertical = 8.dp)
            ) {
                val karaoke = isActive && !line.isInstrumental && line.hasRealWordTimings && line.words.isNotEmpty()
                Text(
                    text = if (karaoke) karaokeLine(line, smoothedMs, bright, dim) else AnnotatedString(line.text),
                    color = color,
                    fontSize = if (line.isInstrumental) 28.sp else 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                translatedLines?.takeIf { !line.isInstrumental }?.getOrNull(index)?.let { translated ->
                    Text(
                        text = translated,
                        color = color.copy(alpha = color.alpha * 0.8f),
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
