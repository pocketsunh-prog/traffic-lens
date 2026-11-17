package com.trafficlens.app.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trafficlens.app.ui.theme.Tint

/** Compact KPI tile: icon, label, big number, and a recent-delta chip. */
@Composable
fun StatCard(
    title: String,
    value: Int?,
    delta: Int,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val animated by animateIntAsState(
        targetValue = value ?: 0,
        animationSpec = tween(durationMillis = 700),
        label = "stat"
    )

    GlassCard(modifier = modifier, contentPadding = 13.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = icon, tint = accent, size = 34.dp, iconSize = 18.dp)
            Spacer(Modifier.weight(1f))
            DeltaChip(value = delta, tint = Tint.Green)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = Tint.TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (value == null) "—" else animated.toString(),
                color = Tint.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )
            Text(
                text = " veh",
                color = Tint.TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** Header row used by the video and chart cards. */
@Composable
fun CardHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconTile(icon = icon, tint = accent, size = 34.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Tint.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = Tint.TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
                maxLines = 1
            )
        }
        trailing()
    }
}
