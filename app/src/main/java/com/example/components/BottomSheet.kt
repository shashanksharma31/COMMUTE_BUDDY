package com.example.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSurface
import kotlin.math.abs

enum class SheetSnapPoint {
    PEEK, // 96px
    HALF, // 50%
    FULL  // 92%
}

// White bottom sheet, top radius 16px, grey 32x4 drag handle, 3 snap points (peek 96px, half 50%, full 92%),
// draggable with pointer events, snaps with a 200ms transition, no external library.
@Composable
fun BottomSheet(
    snapPoint: SheetSnapPoint,
    onSnapPointChange: (SheetSnapPoint) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val containerHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val peekPx = with(density) { 96.dp.toPx() }
        val halfPx = containerHeightPx * 0.50f
        val fullPx = containerHeightPx * 0.92f

        val targetHeightPx = when (snapPoint) {
            SheetSnapPoint.PEEK -> peekPx
            SheetSnapPoint.HALF -> halfPx
            SheetSnapPoint.FULL -> fullPx
        }

        // Track live drag offset (in px) during active pointer gesture
        var dragDeltaPx by remember(snapPoint) { mutableFloatStateOf(0f) }

        val animatedBaseHeightPx by animateFloatAsState(
            targetValue = targetHeightPx,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "bottom_sheet_snap_200ms"
        )

        val currentHeightPx = (animatedBaseHeightPx - dragDeltaPx).coerceIn(peekPx, fullPx)
        val currentHeightDp = with(density) { currentHeightPx.toDp() }
        val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

        Surface(
            shape = topShape,
            color = MapsSurface,
            border = BorderStroke(1.dp, MapsBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(currentHeightDp)
                .shadow(elevation = 2.dp, shape = topShape, clip = false)
                .testTag("routes_bottom_sheet")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle bar (48dp touch target, grey 32x4 pill, pointer drag + tap to cycle)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .pointerInput(peekPx, halfPx, fullPx, snapPoint) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    val finalPx = (targetHeightPx - dragDeltaPx).coerceIn(peekPx, fullPx)
                                    val candidates = listOf(
                                        SheetSnapPoint.PEEK to peekPx,
                                        SheetSnapPoint.HALF to halfPx,
                                        SheetSnapPoint.FULL to fullPx
                                    )
                                    val nearest = candidates.minByOrNull { (_, px) ->
                                        abs(finalPx - px)
                                    }?.first ?: SheetSnapPoint.HALF
                                    dragDeltaPx = 0f
                                    onSnapPointChange(nearest)
                                },
                                onDragCancel = {
                                    dragDeltaPx = 0f
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDeltaPx += dragAmount
                                }
                            )
                        }
                        .clickable {
                            val next = when (snapPoint) {
                                SheetSnapPoint.PEEK -> SheetSnapPoint.HALF
                                SheetSnapPoint.HALF -> SheetSnapPoint.FULL
                                SheetSnapPoint.FULL -> SheetSnapPoint.HALF
                            }
                            onSnapPointChange(next)
                        }
                        .testTag("bottom_sheet_drag_handle"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(color = MapsBorder, shape = CircleShape)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    content()
                }
            }
        }
    }
}
