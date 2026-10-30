package com.example.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSurface

// Google Maps bottom sheet container with 16dp top radius, 48dp drag handle target, 180ms transition
@Composable
fun BottomSheetContainer(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    collapsedHeight: Dp = 304.dp,
    expandedHeight: Dp = 472.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val targetHeight by animateDpAsState(
        targetValue = if (isExpanded) expandedHeight else collapsedHeight,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "sheet_height"
    )

    val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    Surface(
        shape = topShape,
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = modifier
            .fillMaxWidth()
            .height(targetHeight)
            .shadow(elevation = 2.dp, shape = topShape, clip = false)
            .testTag("bottom_sheet_container")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 48dp minimum touch target handle area to toggle sheet expansion
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 12.dp)
                    .testTag("sheet_handle_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(color = MapsBorder, shape = CircleShape)
                )
            }

            content()
        }
    }
}
