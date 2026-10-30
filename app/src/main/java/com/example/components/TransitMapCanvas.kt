package com.example.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.TransitStop
import com.example.ui.theme.MapLandFill
import com.example.ui.theme.MapParkFill
import com.example.ui.theme.MapRoadOutline
import com.example.ui.theme.MapRoadStroke
import com.example.ui.theme.MapTransitBlue
import com.example.ui.theme.MapWaterFill
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import kotlin.math.hypot
import kotlin.math.roundToInt

// Interactive Google Maps style vector canvas with pan/zoom and clickable station pins
@Composable
fun TransitMapCanvas(
    stops: List<TransitStop>,
    selectedStopId: String?,
    highlightedStopIds: List<String>,
    onSelectStop: (TransitStop) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val animatedZoom by animateFloatAsState(
        targetValue = zoom,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "map_zoom"
    )

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapLandFill)
            .onSizeChanged { canvasSize = it }
            .pointerInput(stops, animatedZoom, panOffset) {
                detectTapGestures { tapOffset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w <= 0f || h <= 0f) return@detectTapGestures
                    val hitStop = stops.minByOrNull { stop ->
                        val sx = (stop.mapX - 0.5f) * w * animatedZoom + w * 0.5f + panOffset.x
                        val sy = (stop.mapY - 0.5f) * h * animatedZoom + h * 0.5f + panOffset.y
                        hypot(tapOffset.x - sx, tapOffset.y - sy)
                    }
                    if (hitStop != null) {
                        val sx = (hitStop.mapX - 0.5f) * w * animatedZoom + w * 0.5f + panOffset.x
                        val sy = (hitStop.mapY - 0.5f) * h * animatedZoom + h * 0.5f + panOffset.y
                        if (hypot(tapOffset.x - sx, tapOffset.y - sy) <= 96f) {
                            onSelectStop(hitStop)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    panOffset = Offset(
                        x = (panOffset.x + dragAmount.x).coerceIn(-320f, 320f),
                        y = (panOffset.y + dragAmount.y).coerceIn(-320f, 320f)
                    )
                }
            }
            .testTag("transit_map_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            fun project(normX: Float, normY: Float): Offset {
                val px = (normX - 0.5f) * w * animatedZoom + w * 0.5f + panOffset.x
                val py = (normY - 0.5f) * h * animatedZoom + h * 0.5f + panOffset.y
                return Offset(px, py)
            }

            // Bay water polygon on upper-right edge (Google Maps water #D2E3FC)
            val waterPath = Path().apply {
                val p1 = project(0.68f, -0.1f)
                val p2 = project(1.15f, -0.1f)
                val p3 = project(1.15f, 0.65f)
                val p4 = project(0.82f, 0.42f)
                moveTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                lineTo(p4.x, p4.y)
                close()
            }
            drawPath(path = waterPath, color = MapWaterFill)

            // City parks (#E6F4EA)
            val parkTopLeft = project(0.12f, 0.22f)
            val parkSize = Size(w * 0.18f * animatedZoom, h * 0.12f * animatedZoom)
            drawRect(color = MapParkFill, topLeft = parkTopLeft, size = parkSize)

            val southParkTopLeft = project(0.46f, 0.62f)
            val southParkSize = Size(w * 0.14f * animatedZoom, h * 0.09f * animatedZoom)
            drawRect(color = MapParkFill, topLeft = southParkTopLeft, size = southParkSize)

            // Secondary grid streets
            val gridSteps = listOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f)
            gridSteps.forEach { g ->
                val hStart = project(0.0f, g)
                val hEnd = project(0.92f, g)
                drawLine(
                    color = MapRoadOutline,
                    start = hStart,
                    end = hEnd,
                    strokeWidth = 8f * animatedZoom
                )
                drawLine(
                    color = MapRoadStroke,
                    start = hStart,
                    end = hEnd,
                    strokeWidth = 6f * animatedZoom
                )

                val vStart = project(g, 0.0f)
                val vEnd = project(g, 0.95f)
                drawLine(
                    color = MapRoadOutline,
                    start = vStart,
                    end = vEnd,
                    strokeWidth = 8f * animatedZoom
                )
                drawLine(
                    color = MapRoadStroke,
                    start = vStart,
                    end = vEnd,
                    strokeWidth = 6f * animatedZoom
                )
            }

            // Diagonal arterial (Market Street corridor)
            val mStart = project(0.08f, 0.78f)
            val mEnd = project(0.78f, 0.25f)
            drawLine(
                color = MapRoadOutline,
                start = mStart,
                end = mEnd,
                strokeWidth = 16f * animatedZoom,
                cap = StrokeCap.Round
            )
            drawLine(
                color = MapRoadStroke,
                start = mStart,
                end = mEnd,
                strokeWidth = 12f * animatedZoom,
                cap = StrokeCap.Round
            )

            // Active transit line polyline (#1A73E8)
            val routeStops = if (highlightedStopIds.size >= 2) {
                highlightedStopIds.mapNotNull { id -> stops.find { it.id == id } }
            } else {
                stops
            }

            if (routeStops.size >= 2) {
                val transitPath = Path()
                routeStops.forEachIndexed { index, stop ->
                    val pt = project(stop.mapX, stop.mapY)
                    if (index == 0) transitPath.moveTo(pt.x, pt.y)
                    else transitPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = transitPath,
                    color = MapTransitBlue,
                    style = Stroke(
                        width = 8f * animatedZoom,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Station nodes on the map
            stops.forEach { stop ->
                val pt = project(stop.mapX, stop.mapY)
                val isSelected = stop.id == selectedStopId
                val outerRadius = if (isSelected) 16f else 11f
                if (isSelected) {
                    drawCircle(
                        color = MapsSelectedTint,
                        radius = 26f,
                        center = pt
                    )
                }
                drawCircle(
                    color = MapsAccentBlue,
                    radius = outerRadius,
                    center = pt
                )
                drawCircle(
                    color = MapsSurface,
                    radius = outerRadius - 4f,
                    center = pt
                )
            }
        }

        // Station label pills anchored near each stop
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            stops.forEach { stop ->
                val px = (stop.mapX - 0.5f) * canvasSize.width * animatedZoom +
                    canvasSize.width * 0.5f + panOffset.x
                val py = (stop.mapY - 0.5f) * canvasSize.height * animatedZoom +
                    canvasSize.height * 0.5f + panOffset.y
                val isSelected = stop.id == selectedStopId

                Surface(
                    onClick = { onSelectStop(stop) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MapsSelectedTint else MapsSurface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) MapsAccentBlue else MapsBorder
                    ),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (px - with(density) { 44.dp.toPx() }).roundToInt(),
                                y = (py - with(density) { 42.dp.toPx() }).roundToInt()
                            )
                        }
                        .shadow(1.dp, RoundedCornerShape(8.dp))
                        .testTag("map_pin_${stop.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(MapsAccentBlue, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stop.lineCode,
                                style = MaterialTheme.typography.labelMedium,
                                color = MapsSurface
                            )
                        }
                        Text(
                            text = "${stop.nextArrivalMin} min",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MapsAccentBlue else MapsTextPrimary
                        )
                    }
                }
            }
        }

        // Map zoom and recenter controls (48dp touch targets, right side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MapsSurface,
                border = BorderStroke(1.dp, MapsBorder),
                modifier = Modifier.shadow(2.dp, RoundedCornerShape(12.dp))
            ) {
                Column {
                    IconButton(
                        onClick = { zoom = (zoom + 0.2f).coerceAtMost(1.8f) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("map_zoom_in_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.zoom_in),
                            tint = MapsTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(
                        onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.75f) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("map_zoom_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.zoom_out),
                            tint = MapsTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = MapsSurface,
                border = BorderStroke(1.dp, MapsBorder),
                modifier = Modifier.shadow(2.dp, CircleShape)
            ) {
                IconButton(
                    onClick = {
                        zoom = 1.0f
                        panOffset = Offset.Zero
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("map_recenter_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MyLocation,
                        contentDescription = stringResource(R.string.recenter_map),
                        tint = MapsAccentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
