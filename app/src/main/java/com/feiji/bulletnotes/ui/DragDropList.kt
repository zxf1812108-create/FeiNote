package com.feiji.bulletnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DragDropState internal constructor(
    val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (Int, Int) -> Unit
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val draggingItemOffset = Animatable(0f)

    private var initialDraggingItem: LazyListItemInfo? = null

    fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                offset.y.toInt() in item.offset..(item.offset + item.size)
            }?.also { item ->
                initialDraggingItem = item
                draggingItemIndex = item.index
            }
    }

    fun onDragInterrupted() {
        draggingItemIndex = null
        initialDraggingItem = null
        scope.launch {
            draggingItemOffset.snapTo(0f)
        }
    }

    fun onDrag(dragAmount: Offset) {
        scope.launch {
            draggingItemOffset.snapTo(draggingItemOffset.value + dragAmount.y)

            val currentDraggingItem = initialDraggingItem ?: return@launch
            val startOffset = currentDraggingItem.offset + draggingItemOffset.value
            val endOffset = startOffset + currentDraggingItem.size
            val middleOffset = startOffset + (currentDraggingItem.size / 2)

            val targetItem = state.layoutInfo.visibleItemsInfo
                .find { item ->
                    middleOffset.toInt() in item.offset..(item.offset + item.size) &&
                            currentDraggingItem.index != item.index
                }

            if (targetItem != null) {
                val currentIndex = currentDraggingItem.index
                val targetIndex = targetItem.index
                onMove(currentIndex, targetIndex)
                draggingItemIndex = targetIndex
                initialDraggingItem = targetItem
                draggingItemOffset.snapTo(startOffset - targetItem.offset)
            }
        }
    }
}

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (Int, Int) -> Unit
): DragDropState {
    val scope = rememberCoroutineScope()
    return remember(lazyListState) {
        DragDropState(state = lazyListState, scope = scope, onMove = onMove)
    }
}

fun Modifier.dragContainer(dragDropState: DragDropState): Modifier {
    return this.pointerInput(dragDropState) {
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDropState.onDrag(offset)
            },
            onDragStart = { offset ->
                dragDropState.onDragStart(offset)
            },
            onDragEnd = {
                dragDropState.onDragInterrupted()
            },
            onDragCancel = {
                dragDropState.onDragInterrupted()
            }
        )
    }
}
