package com.trafficlens.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trafficlens.app.ui.theme.Tint

/** Rounded, softly lit container used for every panel on the dashboard. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Tint.CardTop, Tint.CardBottom)))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Tint.CardBorderStart.copy(alpha = 0.9f),
                        Tint.CardBorderEnd.copy(alpha = 0.55f)
                    )
                ),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

/** Small rounded square that holds a category glyph. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Green "Live" / amber "Simulated" status pill with a breathing dot. */
@Composable
fun StatusPill(live: Boolean, modifier: Modifier = Modifier) {
    val tint = if (live) Tint.Green else Tint.Amber
    val transition = rememberInfiniteTransition(label = "pill")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dot"
    )
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = if (live) dotAlpha else 1f))
        )
        Text(
            text = if (live) "LIVE" else "DEMO",
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        )
    }
}

/** "+2" style chip; hidden-looking when nothing changed. */
@Composable
fun DeltaChip(value: Int, tint: Color = Tint.Green, modifier: Modifier = Modifier) {
    val idle = value <= 0
    val color = if (idle) Tint.TextTertiary else tint
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (idle) 0.08f else 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (idle) "0" else "+$value",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Tiny technology tag, e.g. "OpenCV". */
@Composable
fun TechChip(text: String, tint: Color = Tint.TextSecondary, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = tint,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp)
    )
}
