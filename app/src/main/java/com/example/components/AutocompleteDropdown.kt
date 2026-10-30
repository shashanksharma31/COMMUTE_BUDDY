package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.DataLoadState
import com.example.data.DelhiPlace
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Builds an AnnotatedString highlighting the matched query substring in bold
fun highlightMatchedText(fullText: String, query: String): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return AnnotatedString(fullText)
    return buildAnnotatedString {
        append(fullText)
        var startIndex = fullText.indexOf(trimmed, ignoreCase = true)
        while (startIndex >= 0) {
            val endIndex = (startIndex + trimmed.length).coerceAtMost(fullText.length)
            addStyle(
                style = SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = MapsTextPrimary
                ),
                start = startIndex,
                end = endIndex
            )
            startIndex = fullText.indexOf(trimmed, startIndex = endIndex, ignoreCase = true)
        }
    }
}

// Autocomplete dropdown under the focused input (max 5 results, bold highlight, Loading/Empty/Error states)
@Composable
fun AutocompleteDropdown(
    results: List<DelhiPlace>,
    query: String,
    loadState: DataLoadState,
    onSelectPlace: (DelhiPlace) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        results.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    if (effectiveState != DataLoadState.READY) {
        ListStateView(
            state = effectiveState,
            emptyMessage = "No matching places found in India",
            onRetry = onRetry,
            modifier = modifier
        )
        return
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .testTag("autocomplete_dropdown")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            results.take(5).forEachIndexed { index, place ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelectPlace(place) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("autocomplete_item_${place.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MapPinIcon(
                        tint = MapsTextSecondary,
                        iconSize = 24.dp,
                        contentDesc = place.category
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = highlightMatchedText(place.name, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MapsTextPrimary
                        )
                        Text(
                            text = highlightMatchedText("${place.area} • ${place.category}", query),
                            style = MaterialTheme.typography.bodySmall,
                            color = MapsTextSecondary
                        )
                    }
                }
                if (index < results.take(5).lastIndex) {
                    HorizontalDivider(
                        color = MapsBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                }
            }
        }
    }
}
