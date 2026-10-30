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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MapsAccentBlue

// Lucide sparkle vector icon (24px viewBox, 2px stroke weight)
@Composable
fun SparkleIcon(
    tint: Color = MapsAccentBlue,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Ask about your trip",
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

        // Four-point main star centered at (12, 12)
        val starPath = Path().apply {
            moveTo(12f * s, 3f * s)
            lineTo(14.2f * s, 9.8f * s)
            lineTo(21f * s, 12f * s)
            lineTo(14.2f * s, 14.2f * s)
            lineTo(12f * s, 21f * s)
            lineTo(9.8f * s, 14.2f * s)
            lineTo(3f * s, 12f * s)
            lineTo(9.8f * s, 9.8f * s)
            close()
        }
        drawPath(path = starPath, color = tint, style = stroke)

        // Small accent cross at top-right (19, 4)
        drawLine(
            color = tint,
            start = Offset(19f * s, 2.5f * s),
            end = Offset(19f * s, 5.5f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(17.5f * s, 4f * s),
            end = Offset(20.5f * s, 4f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}
