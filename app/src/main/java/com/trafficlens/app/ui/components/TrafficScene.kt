package com.trafficlens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trafficlens.app.data.TrafficSimulator
import com.trafficlens.app.data.TrafficSnapshot
import com.trafficlens.app.data.VehicleSnapshot
import com.trafficlens.app.data.VehicleType
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val HORIZON_RATIO = 0.40f

private val PaintPalette = listOf(
    Color(0xFFDDE3F0),
    Color(0xFF6C7896),
    Color(0xFF2F4C7A),
    Color(0xFF8E2F3E),
    Color(0xFF276B64),
    Color(0xFF3A3F4F)
)

private val TruckPaints = listOf(
    Color(0xFF3B5A82),
    Color(0xFF7A3B2E),
    Color(0xFF3E4A5A),
    Color(0xFF2E5F57)
)

private val BusPaints = listOf(
    Color(0xFF2F6B8A),
    Color(0xFF8A4A2B),
    Color(0xFF4A3F7A),
    Color(0xFF2F6B4F)
)

private val MotoPaints = listOf(
    Color(0xFF8E2F3E),
    Color(0xFF2F4C7A),
    Color(0xFF276B64),
    Color(0xFF3A3F4F)
)

private fun paintFor(type: VehicleType, seed: Int): Color = when (type) {
    VehicleType.CAR -> PaintPalette[seed % PaintPalette.size]
    VehicleType.TRUCK -> TruckPaints[seed % TruckPaints.size]
    VehicleType.BUS -> BusPaints[seed % BusPaints.size]
    VehicleType.MOTORCYCLE -> MotoPaints[seed % MotoPaints.size]
}

/**
 * A night-time city avenue rendered in perspective with YOLO-style detector overlays.
 * Every pixel is drawn on one canvas so the panel behaves like a video frame.
 */
@Composable
fun TrafficScene(
    snapshot: TrafficSnapshot,
    clockLabel: String,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val city = remember { Cityscape.build() }
    val trees = remember { TreeLine.build() }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * HORIZON_RATIO

        drawSky(w, h, horizon)
        drawCity(city, w, h, horizon)
        drawTrees(trees, w, h, horizon)
        drawRoad(w, h, horizon)
        drawStreetLights(w, h, horizon)
        drawVehicles(snapshot.vehicles, w, h, horizon)
        drawVignette(w, h)
        drawDetectorOverlay(snapshot, measurer, w, h, horizon)
        drawHud(snapshot, clockLabel, measurer, w, h)
    }
}

// ---------------------------------------------------------------------------------------------
// Backdrop
// ---------------------------------------------------------------------------------------------

private data class Block(
    val left: Float,
    val width: Float,
    val height: Float,
    val windows: List<Offset>,
    val neon: Color?
)

private object Cityscape {
    fun build(): List<Block> {
        val random = Random(4242)
        val blocks = ArrayList<Block>(28)
        var cursor = -0.04f
        while (cursor < 1.04f) {
            val width = 0.045f + random.nextFloat() * 0.085f
            val height = 0.055f + random.nextFloat() * 0.215f
            val windows = ArrayList<Offset>()
            val columns = 3 + random.nextInt(3)
            val rows = 4 + random.nextInt(6)
            for (column in 0 until columns) {
                for (row in 0 until rows) {
                    if (random.nextFloat() < 0.45f) {
                        windows.add(
                            Offset(
                                (column + 0.55f) / columns * 0.82f + 0.09f,
                                (row + 0.6f) / rows * 0.86f + 0.05f
                            )
                        )
                    }
                }
            }
            val neon = when {
                random.nextFloat() < 0.10f -> Color(0xFF22D3EE)
                random.nextFloat() < 0.10f -> Color(0xFFA78BFA)
                random.nextFloat() < 0.07f -> Color(0xFFFB7185)
                else -> null
            }
            blocks.add(Block(cursor, width, height, windows, neon))
            cursor += width + 0.004f + random.nextFloat() * 0.014f
        }
        return blocks
    }
}

private data class Tree(val x: Float, val height: Float, val radius: Float)

private object TreeLine {
    fun build(): List<Tree> {
        val random = Random(99)
        return List(46) {
            Tree(
                x = -0.02f + random.nextFloat() * 1.04f,
                height = 0.018f + random.nextFloat() * 0.024f,
                radius = 0.012f + random.nextFloat() * 0.016f
            )
        }
    }
}

private fun DrawScope.drawSky(w: Float, h: Float, horizon: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF070A1A),
            0.5f to Color(0xFF191A3C),
            0.82f to Color(0xFF3A2A55),
            1f to Color(0xFF7B4256),
            startY = 0f,
            endY = horizon
        ),
        size = Size(w, horizon)
    )

    val glowCenter = Offset(w * 0.62f, horizon)
    val glowRadius = w * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0x66FF9E6D), Color(0x22C4638C), Color(0x00000000)),
            center = glowCenter,
            radius = glowRadius
        ),
        radius = glowRadius,
        center = glowCenter
    )

    drawCircle(Color(0x66FFFFFF), radius = 1.2f, center = Offset(w * 0.18f, h * 0.06f))
    drawCircle(Color(0x44FFFFFF), radius = 1f, center = Offset(w * 0.72f, h * 0.09f))
    drawCircle(Color(0x55FFFFFF), radius = 1.3f, center = Offset(w * 0.86f, h * 0.04f))
    drawCircle(Color(0x33FFFFFF), radius = 1f, center = Offset(w * 0.42f, h * 0.12f))
}

private fun DrawScope.drawCity(city: List<Block>, w: Float, h: Float, horizon: Float) {
    city.forEach { block ->
        val blockHeight = block.height * h
        val left = block.left * w
        val width = block.width * w
        val top = horizon - blockHeight

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF141631),
                1f to Color(0xFF0A0C1C),
                startY = top,
                endY = horizon
            ),
            topLeft = Offset(left, top),
            size = Size(width, blockHeight)
        )

        block.windows.forEach { window ->
            val lit = 0.18f + 0.38f * ((window.x * 13.7f) % 1f)
            drawRect(
                color = Color(0xFFF7C97A).copy(alpha = lit),
                topLeft = Offset(left + window.x * width, top + window.y * blockHeight),
                size = Size(width * 0.07f, blockHeight * 0.026f)
            )
        }

        block.neon?.let { neon ->
            drawRect(
                color = neon.copy(alpha = 0.5f),
                topLeft = Offset(left + width * 0.86f, top + blockHeight * 0.18f),
                size = Size(width * 0.028f, blockHeight * 0.42f)
            )
        }
    }

    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0x00101028),
            0.55f to Color(0x881A1430),
            1f to Color(0xCC241A3C),
            startY = horizon - h * 0.20f,
            endY = horizon
        ),
        topLeft = Offset(0f, horizon - h * 0.20f),
        size = Size(w, h * 0.20f)
    )
}

private fun DrawScope.drawTrees(trees: List<Tree>, w: Float, h: Float, horizon: Float) {
    trees.forEach { tree ->
        val radius = tree.radius * w
        val center = Offset(tree.x * w, horizon - tree.height * h * 0.45f)
        drawOval(
            color = Color(0xFF07110D).copy(alpha = 0.85f),
            topLeft = Offset(center.x - radius, center.y - radius * 0.72f),
            size = Size(radius * 2f, radius * 1.44f)
        )
    }
}

private fun DrawScope.drawRoad(w: Float, h: Float, horizon: Float) {
    val centreX = w * 0.5f
    val leftEdge = w * 0.045f
    val rightEdge = w * 0.955f

    // Sidewalks and forecourt.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF0C0D17), Color(0xFF06070D)),
            startY = horizon,
            endY = h
        ),
        topLeft = Offset(0f, horizon),
        size = Size(w, h - horizon)
    )

    // Road slab.
    val road = Path().apply {
        moveTo(centreX - w * 0.045f, horizon)
        lineTo(centreX + w * 0.045f, horizon)
        lineTo(rightEdge, h)
        lineTo(leftEdge, h)
        close()
    }
    drawPath(
        path = road,
        brush = Brush.verticalGradient(
            0f to Color(0xFF262840),
            0.32f to Color(0xFF1B1C2C),
            1f to Color(0xFF111220),
            startY = horizon,
            endY = h
        )
    )

    // Sheen down the middle of the carriageway.
    val sheen = Path().apply {
        moveTo(centreX - w * 0.02f, horizon)
        lineTo(centreX + w * 0.02f, horizon)
        lineTo(centreX + w * 0.33f, h)
        lineTo(centreX - w * 0.33f, h)
        close()
    }
    drawPath(
        path = sheen,
        brush = Brush.verticalGradient(
            listOf(Color(0x12A8B7FF), Color(0x00A8B7FF)),
            startY = h,
            endY = horizon
        )
    )

    // Kerbs.
    listOf(leftEdge, rightEdge).forEach { ratio ->
        drawLine(
            color = Color(0xFF3C4058).copy(alpha = 0.85f),
            start = project(ratio, 0.02f, w, h, horizon),
            end = project(ratio, 1f, w, h, horizon),
            strokeWidth = 2.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF8A90AC).copy(alpha = 0.35f),
            start = project(ratio, 0.02f, w, h, horizon),
            end = project(ratio, 1f, w, h, horizon),
            strokeWidth = 1.dp.toPx()
        )
    }

    // Lane dividers.
    listOf(0.285f, 0.5f, 0.715f).forEach { bottomRatio ->
        var z = 0.035f
        while (z < 1f) {
            val zEnd = min(z + 0.045f, 1f)
            drawLine(
                color = Color(0xFFDCE1F2).copy(alpha = 0.18f + 0.5f * z),
                start = project(bottomRatio, z, w, h, horizon),
                end = project(bottomRatio, zEnd, w, h, horizon),
                strokeWidth = (0.6f + 4.2f * z).dp.toPx() * 0.55f,
                cap = StrokeCap.Round
            )
            z += 0.115f
        }
    }

    // Edge lines.
    listOf(0.085f, 0.915f).forEach { bottomRatio ->
        drawLine(
            color = Color(0xFFDCE1F2).copy(alpha = 0.5f),
            start = project(bottomRatio, 0.035f, w, h, horizon),
            end = project(bottomRatio, 0.995f, w, h, horizon),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // Night haze sitting on the tarmac.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0x55301F44),
            1f to Color(0x00000000),
            startY = horizon,
            endY = horizon + h * 0.16f
        ),
        topLeft = Offset(0f, horizon),
        size = Size(w, h * 0.16f)
    )
}

private fun DrawScope.drawStreetLights(w: Float, h: Float, horizon: Float) {
    val depths = listOf(0.10f, 0.36f, 0.72f)
    depths.forEach { z ->
        listOf(0.03f, 0.97f).forEach { edge ->
            val foot = project(edge, z, w, h, horizon)
            val poleHeight = h * 0.17f * (0.35f + z)
            val top = Offset(foot.x, foot.y - poleHeight)
            drawLine(
                color = Color(0xFF2A2C42),
                start = foot,
                end = top,
                strokeWidth = (1f + 1.8f * z).dp.toPx(),
                cap = StrokeCap.Round
            )
            val lampCenter = Offset(top.x + (if (edge < 0.5f) w * 0.014f else -w * 0.014f), top.y)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x77FFD9A0), Color(0x22FFC579), Color(0x00FFC579)),
                    center = lampCenter,
                    radius = w * 0.09f
                ),
                radius = w * 0.09f,
                center = lampCenter
            )
            drawCircle(Color(0xFFFFE7BD), radius = (1.2f + 1.6f * z).dp.toPx(), center = lampCenter)
        }
    }
}

private fun DrawScope.drawVignette(w: Float, h: Float) {
    val radius = hypot(w, h) * 0.62f
    drawRect(
        brush = Brush.radialGradient(
            listOf(Color(0x00000000), Color(0x00000000), Color(0x8A000000)),
            center = Offset(w * 0.5f, h * 0.52f),
            radius = radius
        )
    )
}

// ---------------------------------------------------------------------------------------------
// Vehicles
// ---------------------------------------------------------------------------------------------

private fun project(
    bottomRatio: Float,
    z: Float,
    w: Float,
    h: Float,
    horizon: Float
): Offset {
    val centreX = w * 0.5f
    val depth = 0.035f + 0.965f * z
    val x = centreX + (bottomRatio * w - centreX) * depth
    val y = horizon + (h - horizon) * TrafficSimulator.depth(z)
    return Offset(x, y)
}

private fun laneBottomRatio(lane: Int) = 0.155f + lane * 0.230f

private fun laneX(lane: Int, z: Float, w: Float): Float {
    val centreX = w * 0.5f
    val depth = 0.035f + 0.965f * z
    return centreX + (laneBottomRatio(lane) * w - centreX) * depth
}

private fun vehicleWidth(type: VehicleType, z: Float, w: Float): Float {
    val base = w * (0.020f + 0.180f * z.pow(1.45f))
    return when (type) {
        VehicleType.CAR -> base
        VehicleType.TRUCK -> base * 1.18f
        VehicleType.BUS -> base * 1.08f
        VehicleType.MOTORCYCLE -> base * 0.52f
    }
}

private fun vehicleHeight(type: VehicleType, width: Float): Float = when (type) {
    VehicleType.CAR -> width * 0.66f
    VehicleType.TRUCK -> width * 0.96f
    VehicleType.BUS -> width * 1.04f
    VehicleType.MOTORCYCLE -> width * 1.55f
}

private fun DrawScope.drawVehicles(
    vehicles: List<VehicleSnapshot>,
    w: Float,
    h: Float,
    horizon: Float
) {
    vehicles.sortedBy { it.z }.forEach { vehicle ->
        val z = vehicle.z.coerceIn(0f, 1.05f)
        val width = vehicleWidth(vehicle.type, z, w)
        val height = vehicleHeight(vehicle.type, width)
        val sway = sin(vehicle.sway) * width * 0.05f
        val x = laneX(vehicle.lane, z, w) + sway
        val y = horizon + (h - horizon) * TrafficSimulator.depth(z)
        val paint = paintFor(vehicle.type, vehicle.paint)

        drawVehicle(vehicle.type, x, y, width, height, paint, z)
    }
}

private fun DrawScope.drawVehicle(
    type: VehicleType,
    centreX: Float,
    bottomY: Float,
    width: Float,
    height: Float,
    paint: Color,
    z: Float
) {
    if (width < 3f) return
    val lampPower = (0.25f + 0.75f * z).coerceAtMost(1f)

    drawOval(
        brush = Brush.radialGradient(
            listOf(Color(0xCC000000), Color(0x00000000)),
            center = Offset(centreX, bottomY),
            radius = width * 0.72f
        ),
        topLeft = Offset(centreX - width * 0.72f, bottomY - height * 0.09f),
        size = Size(width * 1.44f, height * 0.22f)
    )

    when (type) {
        VehicleType.CAR -> drawCar(centreX, bottomY, width, height, paint, lampPower)
        VehicleType.TRUCK -> drawTruck(centreX, bottomY, width, height, paint, lampPower)
        VehicleType.BUS -> drawBus(centreX, bottomY, width, height, paint, lampPower)
        VehicleType.MOTORCYCLE -> drawMotorcycle(centreX, bottomY, width, height, paint, lampPower)
    }

    // Wet-road reflection of the body.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(paint.copy(alpha = 0.10f), Color.Transparent),
            startY = bottomY,
            endY = bottomY + height * 0.24f
        ),
        topLeft = Offset(centreX - width * 0.5f, bottomY),
        size = Size(width, height * 0.24f)
    )
}

private fun DrawScope.drawCar(
    cx: Float,
    bottom: Float,
    w: Float,
    h: Float,
    paint: Color,
    lamp: Float
) {
    val left = cx - w / 2f
    val bodyBottom = bottom - h * 0.05f
    val bodyTop = bottom - h * 0.60f
    val roofTop = bottom - h

    // Wheels peeking out from under the body.
    val wheelW = w * 0.115f
    val wheelH = h * 0.17f
    listOf(left + w * 0.005f, left + w - wheelW - w * 0.005f).forEach { wheelLeft ->
        drawRoundRect(
            color = Color(0xFF07080E),
            topLeft = Offset(wheelLeft, bottom - wheelH),
            size = Size(wheelW, wheelH),
            cornerRadius = CornerRadius(w * 0.035f)
        )
    }

    // Cabin.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(paint.lighten(0.18f), paint.darken(0.06f)),
            startY = roofTop,
            endY = bodyTop
        ),
        topLeft = Offset(left + w * 0.17f, roofTop),
        size = Size(w * 0.66f, h * 0.42f),
        cornerRadius = CornerRadius(w * 0.13f, w * 0.11f)
    )

    // Body.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(paint.lighten(0.10f), paint, paint.darken(0.38f)),
            startY = bodyTop,
            endY = bodyBottom
        ),
        topLeft = Offset(left, bodyTop),
        size = Size(w, bodyBottom - bodyTop),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.09f)
    )

    // Hood crease.
    drawLine(
        color = Color.White.copy(alpha = 0.18f),
        start = Offset(left + w * 0.13f, bodyTop + h * 0.06f),
        end = Offset(left + w * 0.87f, bodyTop + h * 0.06f),
        strokeWidth = w * 0.018f,
        cap = StrokeCap.Round
    )

    // Windscreen.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFFA9CBDF).copy(alpha = 0.9f), Color(0xFF22324A)),
            startY = roofTop + h * 0.08f,
            endY = bodyTop
        ),
        topLeft = Offset(left + w * 0.235f, roofTop + h * 0.09f),
        size = Size(w * 0.53f, h * 0.26f),
        cornerRadius = CornerRadius(w * 0.05f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.16f),
        start = Offset(left + w * 0.31f, roofTop + h * 0.11f),
        end = Offset(left + w * 0.43f, roofTop + h * 0.31f),
        strokeWidth = w * 0.028f,
        cap = StrokeCap.Round
    )

    // Grille.
    drawRoundRect(
        color = Color(0xFF0B0D15),
        topLeft = Offset(cx - w * 0.15f, bodyBottom - h * 0.26f),
        size = Size(w * 0.30f, h * 0.13f),
        cornerRadius = CornerRadius(w * 0.03f)
    )
    listOf(-0.045f, 0f, 0.045f).forEach { offset ->
        drawLine(
            color = Color(0xFF262A38),
            start = Offset(cx + w * offset, bodyBottom - h * 0.235f),
            end = Offset(cx + w * offset, bodyBottom - h * 0.135f),
            strokeWidth = w * 0.012f
        )
    }

    // Headlights.
    val lampTop = bodyBottom - h * 0.30f
    val lampW = w * 0.20f
    val lampH = h * 0.085f
    listOf(left + w * 0.05f, left + w * 0.75f).forEach { lampLeft ->
        val center = Offset(lampLeft + lampW / 2f, lampTop + lampH / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFF2C8).copy(alpha = 0.55f * lamp), Color(0x00FFF2C8)),
                center = center,
                radius = w * 0.30f
            ),
            radius = w * 0.30f,
            center = center
        )
        drawRoundRect(
            color = Color(0xFFFFF8E4),
            topLeft = Offset(lampLeft, lampTop),
            size = Size(lampW, lampH),
            cornerRadius = CornerRadius(lampH * 0.45f)
        )
    }

    // Bumper.
    drawRoundRect(
        color = Color(0xFF0A0B13).copy(alpha = 0.92f),
        topLeft = Offset(left + w * 0.03f, bottom - h * 0.115f),
        size = Size(w * 0.94f, h * 0.075f),
        cornerRadius = CornerRadius(w * 0.045f)
    )

    // Door mirrors.
    listOf(left - w * 0.035f, left + w * 0.935f).forEach { mirrorLeft ->
        drawRoundRect(
            color = paint.darken(0.25f),
            topLeft = Offset(mirrorLeft, bodyTop + h * 0.10f),
            size = Size(w * 0.10f, h * 0.065f),
            cornerRadius = CornerRadius(w * 0.025f)
        )
    }
}

private fun DrawScope.drawTruck(
    cx: Float,
    bottom: Float,
    w: Float,
    h: Float,
    paint: Color,
    lamp: Float
) {
    val left = cx - w / 2f
    val bodyTop = bottom - h
    val bodyBottom = bottom - h * 0.04f

    // Twin wheels.
    val wheelW = w * 0.13f
    val wheelH = h * 0.15f
    listOf(left + w * 0.01f, left + w - wheelW - w * 0.01f).forEach { wheelLeft ->
        drawRoundRect(
            color = Color(0xFF06070C),
            topLeft = Offset(wheelLeft, bottom - wheelH),
            size = Size(wheelW, wheelH),
            cornerRadius = CornerRadius(w * 0.03f)
        )
    }

    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(paint.lighten(0.14f), paint, paint.darken(0.40f)),
            startY = bodyTop,
            endY = bodyBottom
        ),
        topLeft = Offset(left, bodyTop),
        size = Size(w, bodyBottom - bodyTop),
        cornerRadius = CornerRadius(w * 0.06f)
    )

    // Roof deflector.
    drawRoundRect(
        color = paint.darken(0.28f),
        topLeft = Offset(left + w * 0.12f, bodyTop + h * 0.015f),
        size = Size(w * 0.76f, h * 0.07f),
        cornerRadius = CornerRadius(w * 0.02f)
    )
    listOf(0.36f, 0.5f, 0.64f).forEach { marker ->
        drawCircle(
            color = Color(0xFFFFB347).copy(alpha = 0.95f),
            radius = w * 0.018f,
            center = Offset(left + w * marker, bodyTop + h * 0.055f)
        )
    }

    // Windscreen.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF9FC2D8).copy(alpha = 0.9f), Color(0xFF1E2E42)),
            startY = bodyTop + h * 0.13f,
            endY = bodyTop + h * 0.44f
        ),
        topLeft = Offset(left + w * 0.10f, bodyTop + h * 0.13f),
        size = Size(w * 0.80f, h * 0.30f),
        cornerRadius = CornerRadius(w * 0.03f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.14f),
        start = Offset(left + w * 0.16f, bodyTop + h * 0.16f),
        end = Offset(left + w * 0.34f, bodyTop + h * 0.40f),
        strokeWidth = w * 0.025f,
        cap = StrokeCap.Round
    )

    // Grille and chrome bars.
    drawRoundRect(
        color = Color(0xFF0C0E17),
        topLeft = Offset(left + w * 0.08f, bodyTop + h * 0.52f),
        size = Size(w * 0.84f, h * 0.24f),
        cornerRadius = CornerRadius(w * 0.03f)
    )
    for (index in 0 until 3) {
        val y = bodyTop + h * (0.56f + index * 0.055f)
        drawLine(
            color = Color(0xFF3A4052),
            start = Offset(left + w * 0.11f, y),
            end = Offset(left + w * 0.89f, y),
            strokeWidth = w * 0.014f,
            cap = StrokeCap.Round
        )
    }

    // Headlights.
    val lampTop = bodyTop + h * 0.80f
    listOf(left + w * 0.06f, left + w * 0.74f).forEach { lampLeft ->
        val center = Offset(lampLeft + w * 0.10f, lampTop + h * 0.03f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFE9B8).copy(alpha = 0.6f * lamp), Color(0x00FFE9B8)),
                center = center,
                radius = w * 0.32f
            ),
            radius = w * 0.32f,
            center = center
        )
        drawRoundRect(
            color = Color(0xFFFFF4D2),
            topLeft = Offset(lampLeft, lampTop),
            size = Size(w * 0.20f, h * 0.07f),
            cornerRadius = CornerRadius(h * 0.025f)
        )
    }

    // Bumper.
    drawRoundRect(
        color = Color(0xFF0D0E17),
        topLeft = Offset(left - w * 0.02f, bottom - h * 0.11f),
        size = Size(w * 1.04f, h * 0.10f),
        cornerRadius = CornerRadius(w * 0.03f)
    )

    // Mirrors.
    listOf(left - w * 0.06f, left + w * 0.96f).forEach { mirrorLeft ->
        drawLine(
            color = Color(0xFF15171F),
            start = Offset(if (mirrorLeft < cx) left else left + w, bodyTop + h * 0.34f),
            end = Offset(mirrorLeft, bodyTop + h * 0.28f),
            strokeWidth = w * 0.018f,
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = paint.darken(0.3f),
            topLeft = Offset(mirrorLeft, bodyTop + h * 0.22f),
            size = Size(w * 0.06f, h * 0.16f),
            cornerRadius = CornerRadius(w * 0.015f)
        )
    }
}

private fun DrawScope.drawBus(
    cx: Float,
    bottom: Float,
    w: Float,
    h: Float,
    paint: Color,
    lamp: Float
) {
    val left = cx - w / 2f
    val bodyTop = bottom - h
    val bodyBottom = bottom - h * 0.04f

    val wheelW = w * 0.12f
    val wheelH = h * 0.10f
    listOf(left + w * 0.02f, left + w - wheelW - w * 0.02f).forEach { wheelLeft ->
        drawRoundRect(
            color = Color(0xFF06070C),
            topLeft = Offset(wheelLeft, bottom - wheelH),
            size = Size(wheelW, wheelH),
            cornerRadius = CornerRadius(w * 0.025f)
        )
    }

    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(paint.lighten(0.16f), paint, paint.darken(0.34f)),
            startY = bodyTop,
            endY = bodyBottom
        ),
        topLeft = Offset(left, bodyTop),
        size = Size(w, bodyBottom - bodyTop),
        cornerRadius = CornerRadius(w * 0.07f)
    )

    // Destination sign.
    drawRoundRect(
        color = Color(0xFF0A0B13),
        topLeft = Offset(left + w * 0.10f, bodyTop + h * 0.045f),
        size = Size(w * 0.80f, h * 0.085f),
        cornerRadius = CornerRadius(w * 0.015f)
    )
    drawRoundRect(
        color = Color(0xFFFFB347).copy(alpha = 0.85f),
        topLeft = Offset(left + w * 0.135f, bodyTop + h * 0.068f),
        size = Size(w * 0.44f, h * 0.030f),
        cornerRadius = CornerRadius(h * 0.012f)
    )

    // Windscreen.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF9FC6DC).copy(alpha = 0.9f), Color(0xFF1D2C40)),
            startY = bodyTop + h * 0.17f,
            endY = bodyTop + h * 0.56f
        ),
        topLeft = Offset(left + w * 0.07f, bodyTop + h * 0.17f),
        size = Size(w * 0.86f, h * 0.40f),
        cornerRadius = CornerRadius(w * 0.04f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(left + w * 0.15f, bodyTop + h * 0.21f),
        end = Offset(left + w * 0.48f, bodyTop + h * 0.52f),
        strokeWidth = w * 0.035f,
        cap = StrokeCap.Round
    )
    // Wipers.
    listOf(0.30f, 0.58f).forEach { start ->
        drawLine(
            color = Color(0xFF0B0D15).copy(alpha = 0.8f),
            start = Offset(left + w * start, bodyTop + h * 0.55f),
            end = Offset(left + w * (start + 0.13f), bodyTop + h * 0.42f),
            strokeWidth = w * 0.012f,
            cap = StrokeCap.Round
        )
    }

    // Headlights.
    val lampTop = bottom - h * 0.17f
    listOf(left + w * 0.05f, left + w * 0.76f).forEach { lampLeft ->
        val center = Offset(lampLeft + w * 0.09f, lampTop + h * 0.03f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFF0C8).copy(alpha = 0.55f * lamp), Color(0x00FFF0C8)),
                center = center,
                radius = w * 0.28f
            ),
            radius = w * 0.28f,
            center = center
        )
        drawRoundRect(
            color = Color(0xFFFFF7DE),
            topLeft = Offset(lampLeft, lampTop),
            size = Size(w * 0.19f, h * 0.055f),
            cornerRadius = CornerRadius(h * 0.02f)
        )
    }

    // Skirt and bumper.
    drawRoundRect(
        color = Color(0xFF0A0B13),
        topLeft = Offset(left, bottom - h * 0.075f),
        size = Size(w, h * 0.055f),
        cornerRadius = CornerRadius(w * 0.02f)
    )
    drawRoundRect(
        color = Color(0xFF14161F),
        topLeft = Offset(left + w * 0.03f, bottom - h * 0.14f),
        size = Size(w * 0.94f, h * 0.045f),
        cornerRadius = CornerRadius(w * 0.02f)
    )

    // Mirrors on stalks.
    listOf(left - w * 0.02f, left + w * 0.95f).forEach { mirrorLeft ->
        drawLine(
            color = Color(0xFF15171F),
            start = Offset(if (mirrorLeft < cx) left + w * 0.04f else left + w * 0.96f, bodyTop + h * 0.30f),
            end = Offset(mirrorLeft, bodyTop + h * 0.24f),
            strokeWidth = w * 0.016f,
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = paint.darken(0.3f),
            topLeft = Offset(mirrorLeft, bodyTop + h * 0.20f),
            size = Size(w * 0.055f, h * 0.13f),
            cornerRadius = CornerRadius(w * 0.012f)
        )
    }
}

private fun DrawScope.drawMotorcycle(
    cx: Float,
    bottom: Float,
    w: Float,
    h: Float,
    paint: Color,
    lamp: Float
) {
    val jacket = paint.darken(0.45f)

    // Headlight bloom, behind the rider.
    val lampCenter = Offset(cx, bottom - h * 0.52f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFD9A0).copy(alpha = 0.32f * lamp), Color(0x00FFD9A0)),
            center = lampCenter,
            radius = w * 0.42f
        ),
        radius = w * 0.42f,
        center = lampCenter
    )

    // Front wheel with rim and fender.
    drawRoundRect(
        color = Color(0xFF07070C),
        topLeft = Offset(cx - w * 0.20f, bottom - h * 0.30f),
        size = Size(w * 0.40f, h * 0.30f),
        cornerRadius = CornerRadius(w * 0.18f)
    )
    drawRoundRect(
        color = Color(0xFF33374A),
        topLeft = Offset(cx - w * 0.11f, bottom - h * 0.25f),
        size = Size(w * 0.22f, h * 0.20f),
        cornerRadius = CornerRadius(w * 0.10f)
    )
    drawRoundRect(
        color = jacket.darken(0.25f),
        topLeft = Offset(cx - w * 0.14f, bottom - h * 0.35f),
        size = Size(w * 0.28f, h * 0.06f),
        cornerRadius = CornerRadius(w * 0.03f)
    )

    // Legs hugging the tank.
    listOf(-1f, 1f).forEach { side ->
        drawRoundRect(
            color = Color(0xFF161B27),
            topLeft = Offset(cx + side * w * 0.26f - w * 0.07f, bottom - h * 0.58f),
            size = Size(w * 0.14f, h * 0.30f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
    }

    // Torso.
    val torso = Path().apply {
        moveTo(cx - w * 0.24f, bottom - h * 0.58f)
        lineTo(cx - w * 0.20f, bottom - h * 0.84f)
        lineTo(cx + w * 0.20f, bottom - h * 0.84f)
        lineTo(cx + w * 0.24f, bottom - h * 0.58f)
        close()
    }
    drawPath(
        path = torso,
        brush = Brush.verticalGradient(
            listOf(jacket.lighten(0.18f), jacket.darken(0.35f)),
            startY = bottom - h * 0.86f,
            endY = bottom - h * 0.56f
        )
    )
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(cx, bottom - h * 0.80f),
        end = Offset(cx, bottom - h * 0.62f),
        strokeWidth = w * 0.05f,
        cap = StrokeCap.Round
    )

    // Arms reaching for the bars.
    listOf(-1f, 1f).forEach { side ->
        drawLine(
            color = jacket.lighten(0.06f),
            start = Offset(cx + side * w * 0.20f, bottom - h * 0.80f),
            end = Offset(cx + side * w * 0.40f, bottom - h * 0.65f),
            strokeWidth = w * 0.10f,
            cap = StrokeCap.Round
        )
    }

    // Handlebar with mirrors.
    drawLine(
        color = Color(0xFF0C0D15),
        start = Offset(cx - w * 0.44f, bottom - h * 0.64f),
        end = Offset(cx + w * 0.44f, bottom - h * 0.64f),
        strokeWidth = w * 0.04f,
        cap = StrokeCap.Round
    )
    listOf(-1f, 1f).forEach { side ->
        drawLine(
            color = Color(0xFF1A1D27),
            start = Offset(cx + side * w * 0.36f, bottom - h * 0.64f),
            end = Offset(cx + side * w * 0.42f, bottom - h * 0.72f),
            strokeWidth = w * 0.025f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF23283A),
            radius = w * 0.035f,
            center = Offset(cx + side * w * 0.43f, bottom - h * 0.73f)
        )
    }

    // Helmet.
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(paint.lighten(0.22f), paint.darken(0.40f)),
            startY = bottom - h * 1.02f,
            endY = bottom - h * 0.78f
        ),
        radius = w * 0.19f,
        center = Offset(cx, bottom - h * 0.90f)
    )
    drawRoundRect(
        color = Color(0xFF0B1220),
        topLeft = Offset(cx - w * 0.145f, bottom - h * 0.925f),
        size = Size(w * 0.29f, h * 0.075f),
        cornerRadius = CornerRadius(w * 0.04f, w * 0.03f)
    )
    drawRoundRect(
        color = jacket.darken(0.2f),
        topLeft = Offset(cx - w * 0.12f, bottom - h * 0.835f),
        size = Size(w * 0.24f, h * 0.05f),
        cornerRadius = CornerRadius(w * 0.03f)
    )

    // Headlight.
    drawCircle(color = Color(0xFFFFF4D6), radius = w * 0.095f, center = Offset(cx, bottom - h * 0.52f))
    drawCircle(color = Color(0xFFFFE1A8).copy(alpha = 0.8f), radius = w * 0.055f, center = Offset(cx, bottom - h * 0.52f))
}

// ---------------------------------------------------------------------------------------------
// Detector overlay
// ---------------------------------------------------------------------------------------------

private data class BoxInfo(
    val vehicle: VehicleSnapshot,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

private fun DrawScope.drawDetectorOverlay(
    snapshot: TrafficSnapshot,
    measurer: TextMeasurer,
    w: Float,
    h: Float,
    horizon: Float
) {
    val thin = 1.1.dp.toPx()
    val bracket = 2.0.dp.toPx()
    val pad = 2.5.dp.toPx()
    val labelled = snapshot.vehicles.sortedByDescending { it.z }.take(6).map { it.id }.toHashSet()

    val boxes = snapshot.vehicles.mapNotNull { vehicle ->
        val z = vehicle.z.coerceIn(0f, 1.05f)
        val width = vehicleWidth(vehicle.type, z, w)
        val height = vehicleHeight(vehicle.type, width)
        val sway = sin(vehicle.sway) * width * 0.05f
        val cx = laneX(vehicle.lane, z, w) + sway
        val bottom = horizon + (h - horizon) * TrafficSimulator.depth(z)
        if (width + pad * 2f < 12.dp.toPx()) return@mapNotNull null
        BoxInfo(vehicle, cx - width / 2f - pad, bottom - height - pad, width + pad * 2f, height + pad * 2f)
    }.sortedBy { it.vehicle.z }

    // Pass 1: every box and its corner brackets.
    boxes.forEach { box ->
        val color = box.vehicle.type.color
        drawRoundRect(
            color = color.copy(alpha = 0.26f),
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(width = thin)
        )
        val corner = min(box.width * 0.30f, 12.dp.toPx())
        drawLine(color, Offset(box.left, box.top), Offset(box.left + corner, box.top), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left, box.top), Offset(box.left, box.top + corner), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left + box.width, box.top), Offset(box.left + box.width - corner, box.top), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left + box.width, box.top), Offset(box.left + box.width, box.top + corner), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left, box.top + box.height), Offset(box.left + corner, box.top + box.height), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left, box.top + box.height), Offset(box.left, box.top + box.height - corner), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left + box.width, box.top + box.height), Offset(box.left + box.width - corner, box.top + box.height), bracket, StrokeCap.Round)
        drawLine(color, Offset(box.left + box.width, box.top + box.height), Offset(box.left + box.width, box.top + box.height - corner), bracket, StrokeCap.Round)
    }

    // Pass 2: labels for the closest vehicles, nudged so they never cover each other.
    val occupied = ArrayList<Rect>()
    boxes.sortedByDescending { it.vehicle.z }.forEach { box ->
        if (box.vehicle.id !in labelled) return@forEach
        val label = "${box.vehicle.type.key} ${String.format(Locale.US, "%.2f", box.vehicle.confidence)}"
        val fontSize = if (box.width > w * 0.15f) 9.sp else 7.5.sp
        val measured = measurer.measure(
            text = label,
            style = TextStyle(
                color = Color(0xFF07090F),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
        val chipWidth = measured.size.width + 6.dp.toPx()
        val chipHeight = measured.size.height + 2.dp.toPx()
        val chipLeft = box.left.coerceIn(0f, (w - chipWidth).coerceAtLeast(0f))
        val gap = 1.dp.toPx()
        val margin = 1.5.dp.toPx()

        val candidates = listOf(
            box.top - chipHeight - gap,
            box.top + gap,
            box.top + box.height - chipHeight - gap,
            box.top - chipHeight * 2f - gap * 2f
        )
        var chipTop: Float? = null
        for (candidate in candidates) {
            if (candidate < 1.dp.toPx() || candidate + chipHeight > h - 1.dp.toPx()) continue
            val rect = Rect(chipLeft, candidate, chipLeft + chipWidth, candidate + chipHeight)
            if (occupied.none { it.overlaps(rect.inflate(margin)) }) {
                chipTop = candidate
                break
            }
        }
        if (chipTop == null) return@forEach

        occupied.add(Rect(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight))
        drawRoundRect(
            color = box.vehicle.type.color.copy(alpha = 0.94f),
            topLeft = Offset(chipLeft, chipTop),
            size = Size(chipWidth, chipHeight),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(chipLeft + 3.dp.toPx(), chipTop + 1.dp.toPx())
        )
    }
}

// ---------------------------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawHud(
    snapshot: TrafficSnapshot,
    clockLabel: String,
    measurer: TextMeasurer,
    w: Float,
    h: Float
) {
    // Top scrim so the chips stay legible over the skyline.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0x66000000), Color(0x00000000)),
            startY = 0f,
            endY = h * 0.18f
        ),
        size = Size(w, h * 0.18f)
    )

    drawChip(
        measurer = measurer,
        text = "CAM 01",
        topLeft = Offset(8.dp.toPx(), 8.dp.toPx()),
        background = Color(0x99000000),
        foreground = Color.White.copy(alpha = 0.86f),
        dot = Color(0xFFFB7185)
    )

    val engineText = "YOLOv8n \u00B7 ${snapshot.fps} FPS"
    val engineLayout = measurer.measure(
        text = engineText,
        style = TextStyle(
            color = Color.White.copy(alpha = 0.86f),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp
        )
    )
    val padX = 5.dp.toPx()
    val padY = 2.4.dp.toPx()
    val dotSpace = 8.dp.toPx()
    val engineChipWidth = engineLayout.size.width + padX * 2 + dotSpace
    val engineChipHeight = engineLayout.size.height + padY * 2
    val engineLeft = w - engineChipWidth - 8.dp.toPx()
    val engineTop = 8.dp.toPx()

    drawRoundRect(
        color = Color(0x99000000),
        topLeft = Offset(engineLeft, engineTop),
        size = Size(engineChipWidth, engineChipHeight),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    drawCircle(
        color = Color(0xFF34D399),
        radius = 2.dp.toPx(),
        center = Offset(engineLeft + padX + 2.dp.toPx(), engineTop + engineChipHeight / 2f)
    )
    drawText(
        textLayoutResult = engineLayout,
        topLeft = Offset(engineLeft + padX + dotSpace, engineTop + padY)
    )

    // Bottom scrim.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, Color(0xB3000000)),
            startY = h - h * 0.20f,
            endY = h
        ),
        topLeft = Offset(0f, h - h * 0.20f),
        size = Size(w, h * 0.20f)
    )

    drawText(
        textMeasurer = measurer,
        text = "${snapshot.vehicles.size} TRACKED",
        topLeft = Offset(10.dp.toPx(), h - 26.dp.toPx()),
        style = TextStyle(
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
    )
    drawText(
        textMeasurer = measurer,
        text = clockLabel,
        topLeft = Offset(10.dp.toPx(), h - 14.dp.toPx()),
        style = TextStyle(
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    )
}

private fun DrawScope.drawChip(
    measurer: TextMeasurer,
    text: String,
    topLeft: Offset,
    background: Color,
    foreground: Color,
    dot: Color? = null
) {
    val measured = measurer.measure(
        text = text,
        style = TextStyle(
            color = foreground,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    )
    val padX = 5.dp.toPx()
    val padY = 2.4.dp.toPx()
    val dotSize = 3.dp.toPx()
    val dotSpace = if (dot != null) dotSize + 3.5.dp.toPx() else 0f
    val chipWidth = measured.size.width + padX * 2 + dotSpace
    val chipHeight = measured.size.height + padY * 2

    drawRoundRect(
        color = background,
        topLeft = topLeft,
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    if (dot != null) {
        drawCircle(
            color = dot,
            radius = dotSize / 2f,
            center = Offset(topLeft.x + padX + dotSize / 2f, topLeft.y + chipHeight / 2f)
        )
    }
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(topLeft.x + padX + dotSpace, topLeft.y + padY)
    )
}

// ---------------------------------------------------------------------------------------------
// Colour helpers
// ---------------------------------------------------------------------------------------------

private fun Color.mix(other: Color, amount: Float) = Color(
    red = red + (other.red - red) * amount,
    green = green + (other.green - green) * amount,
    blue = blue + (other.blue - blue) * amount,
    alpha = alpha
)

internal fun Color.lighten(amount: Float = 0.2f): Color = mix(Color.White, amount)

internal fun Color.darken(amount: Float = 0.3f): Color = mix(Color.Black, amount)
