package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CommuteRoute
import com.example.lib.CommuteFormatters
import com.example.lib.Route
import com.example.lib.TravelMode
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary
import com.example.ui.theme.RobotoFontFamily

// Lucide chevron-right vector icon (24px viewBox, 2px stroke weight)
@Composable
fun ChevronRightIcon(
    tint: Color = MapsTextSecondary,
    iconSize: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(9f * s, 18f * s)
            lineTo(15f * s, 12f * s)
            lineTo(9f * s, 6f * s)
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

// Google Maps transit result card:
// - Selected card uses a blue (#1a73e8) 2px border
// - Shows Local official fare vs Current Ola/Uber trip price and live traffic analysis
// - Below the selected card: "Saves ₹20, adds 8 min vs fastest", "Reliable 8/10",
//   optional "Leave by 8:40", a bookmark button to save start/end/vehicle, and a full-width blue pill "Start" button.
@Composable
fun RouteCard(
    route: Route,
    allRoutes: List<Route>,
    reachByTime: String?,
    isRecommended: Boolean,
    isSelected: Boolean,
    isBookmarked: Boolean = false,
    isLiveRideActive: Boolean = false,
    onClick: () -> Unit,
    onStartClick: () -> Unit,
    onLiveViewClick: () -> Unit = onStartClick,
    onBookmarkClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val transferText = if (route.transfers == 1) "1 transfer" else "${route.transfers} transfers"
    val metaLine = "$transferText, ${route.walkMeters} m walk"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MapsSurface,
            border = BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MapsAccentBlue else MapsBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("route_card_${route.id}")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRecommended) {
                    Text(
                        text = "Recommended • Best for time & money",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MapsAccentBlue,
                        modifier = Modifier.testTag("recommended_badge_${route.id}")
                    )
                }

                // Top row: Big bold duration + accurate distance ("3.4 km • Fastest") on left, verified costDisplay on right
                val formattedDuration = CommuteFormatters.formatDuration(route.durationSeconds)
                val kmLabel = when {
                    route.distanceKm < 1.0 -> "${(route.distanceKm * 1000.0).toInt().coerceAtLeast(100)} m"
                    route.distanceKm < 10.0 -> "${((route.distanceKm * 10.0).toInt() / 10.0)} km"
                    else -> "${(route.distanceKm + 0.5).toInt()} km"
                }
                val subMetaText = "• $kmLabel • ${route.label.display}"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = formattedDuration,
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            color = MapsTextPrimary
                        )
                        Text(
                            text = subMetaText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MapsTextSecondary
                        )
                    }

                    Text(
                        text = route.costDisplay,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = MapsTextPrimary
                    )
                }

                // Middle row: One row of mode icons joined by chevrons + mode sequence summary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    route.steps.forEachIndexed { index, step ->
                        TravelModeIcon(
                            mode = step.mode,
                            tint = if (isSelected) MapsAccentBlue else MapsTextPrimary,
                            iconSize = 20.dp
                        )
                        if (index < route.steps.lastIndex) {
                            ChevronRightIcon(
                                tint = MapsTextSecondary,
                                iconSize = 16.dp
                            )
                        }
                    }

                    if (route.steps.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = route.steps.joinToString(" > ") { it.mode.label },
                            style = MaterialTheme.typography.bodySmall,
                            color = MapsTextSecondary
                        )
                    }
                }

                // Local fare vs Current Ola/Uber price comparison
                if (route.localFareDisplay.isNotBlank() || route.olaUberFareDisplay.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (route.localFareDisplay.isNotBlank()) {
                            Text(
                                text = route.localFareDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = MapsTextPrimary,
                                modifier = Modifier.testTag("local_fare_${route.id}")
                            )
                        }
                        if (route.olaUberFareDisplay.isNotBlank()) {
                            Text(
                                text = route.olaUberFareDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = MapsAccentBlue,
                                modifier = Modifier.testTag("ola_uber_fare_${route.id}")
                            )
                        }
                    }
                }

                // Bottom row: "2 transfers, 400 m walk", crowd text, and small grey outlined dataLabel tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$metaLine • Crowd: ${route.crowd.display}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MapsTextSecondary,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = MapsBorder,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("data_label_${route.id}")
                    ) {
                        Text(
                            text = route.dataLabel.display,
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MapsTextSecondary
                        )
                    }
                }
            }
        }

        // Details, Traffic Analysis, Bookmark button, and full-width blue pill "Start" + "Live Car View" buttons shown below the selected card
        if (isSelected) {
            val vsFastestText = CommuteFormatters.formatVsFastest(route, allRoutes)
            val reliabilityText = "Reliable ${route.reliability}/10"
            val leaveByText = CommuteFormatters.formatLeaveBy(reachByTime, route.minutes)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MapsSelectedTint, RoundedCornerShape(12.dp))
                    .border(1.dp, MapsBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("selected_route_details_${route.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vsFastestText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MapsTextPrimary,
                        modifier = Modifier.testTag("vs_fastest_text")
                    )
                    Text(
                        text = reliabilityText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MapsAccentBlue,
                        modifier = Modifier.testTag("reliability_text")
                    )
                }

                if (route.trafficAnalysis.isNotBlank()) {
                    Text(
                        text = route.trafficAnalysis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MapsTextSecondary,
                        modifier = Modifier.testTag("traffic_analysis_text")
                    )
                }

                if (route.recommendationReason.isNotBlank()) {
                    Text(
                        text = route.recommendationReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MapsTextPrimary,
                        modifier = Modifier.testTag("recommendation_reason_text")
                    )
                }

                if (leaveByText != null) {
                    Text(
                        text = leaveByText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MapsAccentBlue,
                        modifier = Modifier.testTag("leave_by_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartClick,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MapsAccentBlue,
                            contentColor = MapsSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("start_route_button")
                    ) {
                        Text(
                            text = "Start",
                            style = MaterialTheme.typography.labelLarge,
                            color = MapsSurface
                        )
                    }

                    Surface(
                        onClick = onLiveViewClick,
                        shape = CircleShape,
                        color = if (isLiveRideActive) MapsAccentBlue else MapsSurface,
                        border = BorderStroke(1.dp, MapsAccentBlue),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("route_live_car_view_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TravelModeIcon(
                                mode = TravelMode.CAR,
                                tint = if (isLiveRideActive) MapsSurface else MapsAccentBlue,
                                iconSize = 18.dp
                            )
                            Text(
                                text = if (isLiveRideActive) "Live Active" else "Live Road",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isLiveRideActive) MapsSurface else MapsAccentBlue
                            )
                        }
                    }

                    // Bookmark button on the selected route (48dp touch target)
                    IconButton(
                        onClick = onBookmarkClick,
                        modifier = Modifier
                            .size(48.dp)
                            .background(MapsSurface, CircleShape)
                            .border(
                                width = 1.dp,
                                color = if (isBookmarked) MapsAccentBlue else MapsBorder,
                                shape = CircleShape
                            )
                            .testTag("bookmark_route_button")
                    ) {
                        BookmarkIcon(
                            isSaved = isBookmarked,
                            tint = if (isBookmarked) MapsAccentBlue else MapsTextSecondary,
                            iconSize = 22.dp,
                            contentDesc = if (isBookmarked) "Remove bookmark" else "Bookmark route"
                        )
                    }
                }
            }
        }
    }
}

// Skeleton loader card shown while routes are loading (600ms delay)
@Composable
fun RouteCardSkeleton(
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("route_card_skeleton")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(20.dp)
                        .background(MapsBackground, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(20.dp)
                        .background(MapsBackground, RoundedCornerShape(4.dp))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(MapsBackground, RoundedCornerShape(4.dp))
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(14.dp)
                        .background(MapsBackground, RoundedCornerShape(4.dp))
                )
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(14.dp)
                        .background(MapsBackground, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

// Legacy overload kept for CommuteGoScreen compatibility
@Composable
fun RouteCard(
    route: CommuteRoute,
    isSelected: Boolean,
    isActiveTrip: Boolean,
    onSelect: () -> Unit,
    onStartTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MapsSelectedTint else MapsSurface,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MapsAccentBlue else MapsBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = route.title, style = MaterialTheme.typography.titleMedium, color = MapsTextPrimary)
            val durStr = CommuteFormatters.formatDuration(route.durationMin * 60)
            Text(text = "$durStr • ${route.fareText}", style = MaterialTheme.typography.bodySmall, color = MapsTextSecondary)
        }
    }
}
