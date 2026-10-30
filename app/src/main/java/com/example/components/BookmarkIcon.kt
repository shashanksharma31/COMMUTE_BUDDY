package com.example.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsTextSecondary

// Lucide bookmark icon (24px viewBox, 2px stroke weight)
@Composable
fun BookmarkIcon(
    isSaved: Boolean,
    tint: Color = if (isSaved) MapsAccentBlue else MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Bookmark",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(6f * s, 4f * s)
            lineTo(18f * s, 4f * s)
            lineTo(18f * s, 21f * s)
            lineTo(12f * s, 16.5f * s)
            lineTo(6f * s, 21f * s)
            close()
        }

        if (isSaved) {
            drawPath(path = path, color = tint, style = Fill)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = 2f * s,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// Lucide trash-2 delete icon (24px viewBox, 2px stroke weight)
@Composable
fun TrashDeleteIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Delete saved route",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(
            width = 2f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        // Top lid bar: (3,6) -> (21,6)
        drawLine(tint, Offset(3f * s, 6f * s), Offset(21f * s, 6f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)

        // Handle: (8,6) -> (8,4) -> (16,4) -> (16,6)
        val handle = Path().apply {
            moveTo(8f * s, 6f * s)
            lineTo(8f * s, 4f * s)
            lineTo(16f * s, 4f * s)
            lineTo(16f * s, 6f * s)
        }
        drawPath(handle, color = tint, style = stroke)

        // Body: (5,6) -> (6,20) -> (18,20) -> (19,6)
        val body = Path().apply {
            moveTo(5f * s, 6f * s)
            lineTo(6f * s, 20f * s)
            lineTo(18f * s, 20f * s)
            lineTo(19f * s, 6f * s)
        }
        drawPath(body, color = tint, style = stroke)

        // Inner vertical lines
        drawLine(tint, Offset(10f * s, 11f * s), Offset(10f * s, 16f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
        drawLine(tint, Offset(14f * s, 11f * s), Offset(14f * s, 16f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
    }
}
