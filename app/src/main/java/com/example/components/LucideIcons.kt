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
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Lucide search icon (24px, stroke 2)
@Composable
fun LucideSearchIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Search",
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
            radius = 8f * s,
            center = Offset(11f * s, 11f * s),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(21f * s, 21f * s),
            end = Offset(16.65f * s, 16.65f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

// Lucide arrow-left icon (24px, stroke 2)
@Composable
fun LucideArrowLeftIcon(
    tint: Color = MapsTextPrimary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Back",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(12f * s, 19f * s)
            lineTo(5f * s, 12f * s)
            lineTo(12f * s, 5f * s)
            moveTo(19f * s, 12f * s)
            lineTo(5f * s, 12f * s)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

// Lucide arrow-up-down swap icon (24px, stroke 2)
@Composable
fun LucideArrowUpDownIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Swap start and end",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            // Left arrow down
            moveTo(7f * s, 4f * s)
            lineTo(7f * s, 20f * s)
            moveTo(3f * s, 16f * s)
            lineTo(7f * s, 20f * s)
            lineTo(11f * s, 16f * s)
            // Right arrow up
            moveTo(17f * s, 20f * s)
            lineTo(17f * s, 4f * s)
            moveTo(13f * s, 8f * s)
            lineTo(17f * s, 4f * s)
            lineTo(21f * s, 8f * s)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

// Lucide x (close/clear) icon (24px, stroke 2)
@Composable
fun LucideXIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Close",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        drawLine(
            color = tint,
            start = Offset(18f * s, 6f * s),
            end = Offset(6f * s, 18f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(6f * s, 6f * s),
            end = Offset(18f * s, 18f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

// Lucide send-horizontal icon (24px, stroke 2)
@Composable
fun LucideSendIcon(
    tint: Color = MapsTextPrimary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Send",
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = contentDesc }
    ) {
        val s = size.width / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(3f * s, 3f * s)
            lineTo(21f * s, 12f * s)
            lineTo(3f * s, 21f * s)
            lineTo(6f * s, 12f * s)
            close()
            moveTo(6f * s, 12f * s)
            lineTo(13f * s, 12f * s)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

// Lucide info / alert-circle icon (24px, stroke 2)
@Composable
fun LucideInfoIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 24.dp,
    contentDesc: String = "Information",
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
            radius = 10f * s,
            center = Offset(12f * s, 12f * s),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(12f * s, 16f * s),
            end = Offset(12f * s, 12f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = tint,
            radius = 1f * s,
            center = Offset(12f * s, 8f * s)
        )
    }
}
