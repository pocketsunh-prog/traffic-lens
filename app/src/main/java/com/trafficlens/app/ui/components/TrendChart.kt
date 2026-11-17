package com.trafficlens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trafficlens.app.data.TypeCounts
import com.trafficlens.app.data.VehicleType
import com.trafficlens.app.ui.theme.Tint
import kotlin.math.max
import kotlin.math.min

/** Four-series smoothed line chart of vehicles detected per minute. */
@Composable
fun TrendChart(
    trend: List<TypeCounts>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    yMax: Float = 30f,
    emptyHint: String? = null
) {
    val measurer = rememberTextMeasurer()

    Box(modifier) {
        if (trend.size < 2) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyHint ?: "Waiting for telemetry…",
                    color = Tint.TextTertiary,
                    fontSize = 11.sp
                )
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(158.dp)) {
                val left = 22.dp.toPx()
                val bottom = 17.dp.toPx()
                val top = 6.dp.toPx()
                val right = 8.dp.toPx()
                val plotWidth = size.width - left - right
                val plotHeight = size.height - top - bottom

                drawGrid(measurer, left, top, plotWidth, plotHeight, yMax)
                drawXLabels(measurer, labels, trend.size, left, top, plotWidth, plotHeight, bottom)

                VehicleType.entries.forEach { type ->
                    val values = trend.map { it.of(type).toFloat() }
                    if (values.all { it == 0f }) return@forEach
                    val points = values.mapIndexed { index, value ->
                        val x = left + plotWidth * index / (values.size - 1f)
                        val y = top + plotHeight * (1f - (value.coerceIn(0f, yMax) / yMax))
                        Offset(x, y)
                    }
                    val path = smoothPath(points)
                    val color = type.color

                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.16f),
                        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val last = points.last()
                    drawCircle(color.copy(alpha = 0.22f), radius = 6.dp.toPx(), center = last)
                    drawCircle(Color(0xFF0B0D1E), radius = 3.dp.toPx(), center = last)
                    drawCircle(color, radius = 2.dp.toPx(), center = last)
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    left: Float,
    top: Float,
    plotWidth: Float,
    plotHeight: Float,
    yMax: Float
) {
    val rows = 3
    for (row in 0..rows) {
        val y = top + plotHeight * row / rows
        drawLine(
            color = Color.White.copy(alpha = if (row == rows) 0.12f else 0.055f),
            start = Offset(left, y),
            end = Offset(left + plotWidth, y),
            strokeWidth = 1f
        )
        val label = ((yMax * (rows - row) / rows).toInt()).toString()
        val measured = measurer.measure(
            text = label,
            style = TextStyle(color = Tint.TextTertiary, fontSize = 8.sp, fontWeight = FontWeight.Medium)
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(left - measured.size.width - 5.dp.toPx(), y - measured.size.height / 2f)
        )
    }
}

private fun DrawScope.drawXLabels(
    measurer: TextMeasurer,
    labels: List<String>,
    count: Int,
    left: Float,
    top: Float,
    plotWidth: Float,
    plotHeight: Float,
    bottom: Float
) {
    if (labels.isEmpty()) return
    val positions = listOf(0, count / 3, 2 * count / 3, count - 1)
    positions.distinct().forEach { index ->
        val label = labels.getOrNull(index) ?: return@forEach
        val measured = measurer.measure(
            text = label,
            style = TextStyle(color = Tint.TextTertiary, fontSize = 8.sp, fontWeight = FontWeight.Medium)
        )
        val x = left + plotWidth * index / (count - 1f)
        val clamped = x.coerceIn(left, left + plotWidth - measured.size.width)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(clamped, top + plotHeight + bottom - measured.size.height - 1.dp.toPx())
        )
    }
}

private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (index in 0 until points.size - 1) {
        val p0 = points[max(0, index - 1)]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = points[min(points.size - 1, index + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/** Legend chips for the four detector classes. */
@Composable
fun TrendLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VehicleType.entries.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(type.color)
                )
                Text(
                    text = type.plural,
                    color = Tint.TextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 0.5.dp)
                )
            }
        }
    }
}
