package com.example.tgmusicai.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The grab handle that reorders a list by dragging, shared by the queue, a playlist's songs, and
 * the list of playlists itself.
 *
 * Reordering happens a row at a time: once the finger has travelled half a row, [onMove] is called
 * for a single-step swap and the accumulated offset is wound back by one row, so the list stays in
 * step with the finger without the handle needing to know where anything is on screen. That keeps
 * this usable inside a `LazyColumn`, where most items have no layout information at all.
 *
 * [stableKey] must identify the item, **not** its index. `pointerInput` restarts whenever its key
 * changes, and the index changes on the very first swap -- keying on it cancels the gesture
 * mid-drag, which is the classic way this pattern breaks. Pass a row id or URI.
 */
@Composable
fun ReorderDragHandle(
    index: Int,
    itemCount: Int,
    stableKey: Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 64.dp,
    contentDescription: String = "Drag to reorder",
    onDraggingChange: (Boolean) -> Unit = {},
) {
    // The running gesture must read the row's *current* position, which changes under it as
    // reordering moves this row around.
    val latestIndex = rememberUpdatedState(index)
    val latestCount = rememberUpdatedState(itemCount)
    val latestOnMove = rememberUpdatedState(onMove)
    val latestOnDraggingChange = rememberUpdatedState(onDraggingChange)

    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }

    Icon(
        imageVector = Icons.Rounded.DragHandle,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.pointerInput(stableKey) {
            var accumulated = 0f
            detectDragGestures(
                onDragStart = {
                    accumulated = 0f
                    latestOnDraggingChange.value(true)
                },
                onDragEnd = {
                    accumulated = 0f
                    latestOnDraggingChange.value(false)
                },
                onDragCancel = {
                    accumulated = 0f
                    latestOnDraggingChange.value(false)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount.y
                    val current = latestIndex.value
                    val count = latestCount.value
                    if (accumulated > rowHeightPx / 2 && current < count - 1) {
                        latestOnMove.value(current, current + 1)
                        accumulated -= rowHeightPx
                    } else if (accumulated < -rowHeightPx / 2 && current > 0) {
                        latestOnMove.value(current, current - 1)
                        accumulated += rowHeightPx
                    }
                },
            )
        },
    )
}
