package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.TransitAlert
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Transit service alert card (12dp radius, strictly adhering to #1A73E8 / #202124 / #5F6368)
@Composable
fun ServiceAlertCard(
    alert: TransitAlert,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MapsSelectedTint else MapsSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) MapsAccentBlue else MapsBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("alert_card_${alert.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(MapsAccentBlue, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = alert.lineCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MapsSurface
                    )
                }

                Text(
                    text = alert.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MapsTextPrimary,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (alert.isDelayed) Icons.Outlined.Info else Icons.Outlined.CheckCircleOutline,
                    contentDescription = alert.title,
                    tint = MapsAccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = alert.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MapsTextPrimary
            )

            Text(
                text = alert.updatedAgo,
                style = MaterialTheme.typography.bodySmall,
                color = MapsTextSecondary
            )
        }
    }
}
