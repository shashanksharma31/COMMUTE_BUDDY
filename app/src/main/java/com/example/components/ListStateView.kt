package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DataLoadState
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Standardized Loading, Empty, and Error list states required by AGENTS.md (using Lucide 24px stroke-2 icons)
@Composable
fun ListStateView(
    state: DataLoadState,
    emptyMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("list_state_${state.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                DataLoadState.LOADING -> {
                    CircularProgressIndicator(
                        color = MapsAccentBlue,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.state_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MapsTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                DataLoadState.EMPTY -> {
                    LucideInfoIcon(
                        tint = MapsTextSecondary,
                        iconSize = 24.dp,
                        contentDesc = emptyMessage
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.titleSmall,
                        color = MapsTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MapsBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MapsSelectedTint,
                            contentColor = MapsAccentBlue
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("reset_empty_button")
                    ) {
                        Text(
                            text = "Show all",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                DataLoadState.ERROR -> {
                    LucideInfoIcon(
                        tint = MapsTextSecondary,
                        iconSize = 24.dp,
                        contentDesc = stringResource(R.string.state_error)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.state_error),
                        style = MaterialTheme.typography.titleSmall,
                        color = MapsTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MapsAccentBlue),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MapsSelectedTint,
                            contentColor = MapsAccentBlue
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("retry_error_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_retry),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                DataLoadState.READY -> Unit
            }
        }
    }
}
