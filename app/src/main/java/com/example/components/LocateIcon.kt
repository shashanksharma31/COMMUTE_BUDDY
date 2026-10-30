package com.example.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MapsAccentBlue

// Lucide locate-fixed vector icon (24px viewBox, 2px stroke weight)
@Composable
fun LocateIcon(
    tint: Color = MapsAccentBlue,
    iconSize: Dp = 24.dp,
    contentDesc: String = "My location",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val scale = size.width / 24f
        val strokePx = 2f * scale
        val center = Offset(12f * scale, 12f * scale)

        // Outer ring (r=7)
        drawCircle(
            color = tint,
            radius = 7f * scale,
            center = center,
            style = Stroke(width = strokePx)
        )

        // Inner center dot (r=3)
        drawCircle(
            color = tint,
            radius = 3f * scale,
            center = center,
            style = Stroke(width = strokePx)
        )

        // Top tick (12,2) -> (12,5)
        drawLine(
            color = tint,
            start = Offset(12f * scale, 2f * scale),
            end = Offset(12f * scale, 5f * scale),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
        // Bottom tick (12,19) -> (12,22)
        drawLine(
            color = tint,
            start = Offset(12f * scale, 19f * scale),
            end = Offset(12f * scale, 22f * scale),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
        // Left tick (2,12) -> (5,12)
        drawLine(
            color = tint,
            start = Offset(2f * scale, 12f * scale),
            end = Offset(5f * scale, 12f * scale),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
        // Right tick (19,12) -> (22,12)
        drawLine(
            color = tint,
            start = Offset(19f * scale, 12f * scale),
            end = Offset(22f * scale, 12f * scale),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
    }
}
