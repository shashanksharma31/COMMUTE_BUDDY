package com.example.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.lib.TravelMode

// Lucide 24px stroke-2 geometric vector icons for Mix, Metro, Bus, Train, Car, Bike, Walk, and Clock
@Composable
fun TravelModeIcon(
    mode: TravelMode,
    tint: Color,
    iconSize: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = mode.label }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(
            width = 2f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        when (mode) {
            TravelMode.MIX -> {
                // Lucide shuffle / multi-mode arrows
                val path = Path().apply {
                    moveTo(3f * s, 17f * s)
                    lineTo(8f * s, 17f * s)
                    lineTo(15f * s, 7f * s)
                    lineTo(21f * s, 7f * s)

                    moveTo(18f * s, 4f * s)
                    lineTo(21f * s, 7f * s)
                    lineTo(18f * s, 10f * s)

                    moveTo(3f * s, 7f * s)
                    lineTo(8f * s, 7f * s)
                    lineTo(15f * s, 17f * s)
                    lineTo(21f * s, 17f * s)

                    moveTo(18f * s, 14f * s)
                    lineTo(21f * s, 17f * s)
                    lineTo(18f * s, 20f * s)
                }
                drawPath(path = path, color = tint, style = stroke)
            }

            TravelMode.METRO -> {
                // Lucide tram-front
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(5f * s, 4f * s),
                    size = Size(14f * s, 14f * s),
                    cornerRadius = CornerRadius(3f * s, 3f * s),
                    style = stroke
                )
                drawLine(tint, Offset(5f * s, 11f * s), Offset(19f * s, 11f * s), strokeWidth = 2f * s)
                drawLine(tint, Offset(9f * s, 2f * s), Offset(15f * s, 2f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawLine(tint, Offset(12f * s, 2f * s), Offset(12f * s, 4f * s), strokeWidth = 2f * s)
                drawLine(tint, Offset(7f * s, 21f * s), Offset(9f * s, 18f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawLine(tint, Offset(17f * s, 21f * s), Offset(15f * s, 18f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 1f * s, center = Offset(9f * s, 15f * s))
                drawCircle(tint, radius = 1f * s, center = Offset(15f * s, 15f * s))
            }

            TravelMode.BUS -> {
                // Lucide bus
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4f * s, 4f * s),
                    size = Size(16f * s, 14f * s),
                    cornerRadius = CornerRadius(2f * s, 2f * s),
                    style = stroke
                )
                drawLine(tint, Offset(4f * s, 11f * s), Offset(20f * s, 11f * s), strokeWidth = 2f * s)
                drawLine(tint, Offset(7f * s, 18f * s), Offset(7f * s, 21f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawLine(tint, Offset(17f * s, 18f * s), Offset(17f * s, 21f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 1f * s, center = Offset(8f * s, 15f * s))
                drawCircle(tint, radius = 1f * s, center = Offset(16f * s, 15f * s))
            }

            TravelMode.TRAIN -> {
                // Lucide train-front
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(5f * s, 3f * s),
                    size = Size(14f * s, 15f * s),
                    cornerRadius = CornerRadius(4f * s, 4f * s),
                    style = stroke
                )
                drawLine(tint, Offset(5f * s, 10f * s), Offset(19f * s, 10f * s), strokeWidth = 2f * s)
                drawLine(tint, Offset(12f * s, 3f * s), Offset(12f * s, 10f * s), strokeWidth = 2f * s)
                drawLine(tint, Offset(6f * s, 21f * s), Offset(9f * s, 18f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
                drawLine(tint, Offset(18f * s, 21f * s), Offset(15f * s, 18f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
            }

            TravelMode.FLIGHT -> {
                // Lucide plane geometric vector icon (24px viewBox, 2px stroke)
                val plane = Path().apply {
                    moveTo(12f * s, 3f * s)
                    lineTo(14f * s, 9f * s)
                    lineTo(21f * s, 12f * s)
                    lineTo(14f * s, 14f * s)
                    lineTo(13.5f * s, 19f * s)
                    lineTo(16f * s, 21f * s)
                    lineTo(12f * s, 20f * s)
                    lineTo(8f * s, 21f * s)
                    lineTo(10.5f * s, 19f * s)
                    lineTo(10f * s, 14f * s)
                    lineTo(3f * s, 12f * s)
                    lineTo(10f * s, 9f * s)
                    close()
                }
                drawPath(plane, color = tint, style = stroke)
            }

            TravelMode.CAR -> {
                // Lucide car
                val body = Path().apply {
                    moveTo(5f * s, 11f * s)
                    lineTo(7f * s, 6f * s)
                    lineTo(17f * s, 6f * s)
                    lineTo(19f * s, 11f * s)
                    lineTo(21f * s, 12f * s)
                    lineTo(21f * s, 17f * s)
                    lineTo(3f * s, 17f * s)
                    lineTo(3f * s, 12f * s)
                    close()
                }
                drawPath(body, color = tint, style = stroke)
                drawCircle(tint, radius = 2f * s, center = Offset(7.5f * s, 17f * s), style = stroke)
                drawCircle(tint, radius = 2f * s, center = Offset(16.5f * s, 17f * s), style = stroke)
            }

            TravelMode.BIKE -> {
                // Lucide bike
                drawCircle(tint, radius = 3.5f * s, center = Offset(5.5f * s, 17.5f * s), style = stroke)
                drawCircle(tint, radius = 3.5f * s, center = Offset(18.5f * s, 17.5f * s), style = stroke)
                val frame = Path().apply {
                    moveTo(5.5f * s, 17.5f * s)
                    lineTo(10f * s, 11f * s)
                    lineTo(15f * s, 11f * s)
                    lineTo(12f * s, 17.5f * s)
                    lineTo(5.5f * s, 17.5f * s)
                    moveTo(15f * s, 11f * s)
                    lineTo(18.5f * s, 17.5f * s)
                    moveTo(14f * s, 7f * s)
                    lineTo(16.5f * s, 7f * s)
                }
                drawPath(frame, color = tint, style = stroke)
            }

            TravelMode.WALK -> {
                // Lucide person-standing / walk
                drawCircle(tint, radius = 2f * s, center = Offset(12f * s, 5f * s), style = stroke)
                val walkPath = Path().apply {
                    moveTo(12f * s, 7f * s)
                    lineTo(12f * s, 14f * s)
                    moveTo(8f * s, 10f * s)
                    lineTo(12f * s, 8.5f * s)
                    lineTo(16f * s, 10f * s)
                    moveTo(9f * s, 21f * s)
                    lineTo(12f * s, 14f * s)
                    lineTo(15f * s, 21f * s)
                }
                drawPath(walkPath, color = tint, style = stroke)
            }
        }
    }
}

// Lucide clock icon (24px viewBox, 2px stroke weight) for "Reach by" chip
@Composable
fun ClockIcon(
    tint: Color,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Reach by time",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 9f * s,
            center = Offset(12f * s, 12f * s),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(12f * s, 7f * s),
            end = Offset(12f * s, 12f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(12f * s, 12f * s),
            end = Offset(15.5f * s, 14f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}
