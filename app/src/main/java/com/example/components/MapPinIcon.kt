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

// Lucide map-pin vector icon (24px viewBox, 2px stroke weight)
@Composable
fun MapPinIcon(
    tint: Color = MapsAccentBlue,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Map pin",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val scaleX = size.width / 24f
        val scaleY = size.height / 24f
        val strokePx = 2f * scaleX

        // Outer pin path: M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0
        val pinPath = Path().apply {
            moveTo(12f * scaleX, 2f * scaleY)
            cubicTo(
                16.42f * scaleX, 2f * scaleY,
                20f * scaleX, 5.58f * scaleY,
                20f * scaleX, 10f * scaleY
            )
            cubicTo(
                20f * scaleX, 15.25f * scaleY,
                13.5f * scaleX, 20.5f * scaleY,
                12f * scaleX, 22f * scaleY
            )
            cubicTo(
                10.5f * scaleX, 20.5f * scaleY,
                4f * scaleX, 15.25f * scaleY,
                4f * scaleX, 10f * scaleY
            )
            cubicTo(
                4f * scaleX, 5.58f * scaleY,
                7.58f * scaleX, 2f * scaleY,
                12f * scaleX, 2f * scaleY
            )
            close()
        }

        drawPath(
            path = pinPath,
            color = tint,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Inner circle: cx="12" cy="10" r="3"
        drawCircle(
            color = tint,
            radius = 3f * scaleX,
            center = Offset(12f * scaleX, 10f * scaleY),
            style = Stroke(width = strokePx)
        )
    }
}
