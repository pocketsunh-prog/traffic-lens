package com.trafficlens.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.trafficlens.app.data.FeedMode
import com.trafficlens.app.data.ServerStats
import com.trafficlens.app.data.TrafficApi
import com.trafficlens.app.data.TrafficSimulator
import com.trafficlens.app.data.TrafficSnapshot
import com.trafficlens.app.data.TypeCounts
import com.trafficlens.app.data.VehicleType
import com.trafficlens.app.ui.components.CameraDetectionPanel
import com.trafficlens.app.ui.components.CardHeader
import com.trafficlens.app.ui.components.GlassCard
import com.trafficlens.app.ui.components.StatusPill
import com.trafficlens.app.ui.components.StatCard
import com.trafficlens.app.ui.components.TechChip
import com.trafficlens.app.ui.components.TrafficScene
import com.trafficlens.app.ui.components.TrendChart
import com.trafficlens.app.ui.components.TrendLegend
import com.trafficlens.app.ui.theme.Tint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "traffic_lens"
private const val TREND_POINTS = 15

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var mode by remember {
        mutableStateOf(
            runCatching { FeedMode.valueOf(preferences.getString("feed_mode", "DEMO") ?: "DEMO") }
                .getOrDefault(FeedMode.DEMO)
        )
    }
    var serverUrl by remember {
        mutableStateOf(
            preferences.getString("server_url", "http://192.168.128.140:8080")
                ?: "http://192.168.128.140:8080"
        )
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var reconnectTick by remember { mutableStateOf(0) }

    val simulator = remember { TrafficSimulator() }
    var snapshot by remember { mutableStateOf(simulator.snapshot()) }
    var deviceSnapshot by remember { mutableStateOf(TrafficSnapshot()) }
    var deviceFps by remember { mutableIntStateOf(0) }

    var streamFrame by remember { mutableStateOf<Bitmap?>(null) }
    var serverStats by remember { mutableStateOf<ServerStats?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    val minuteLabels = remember(nowMillis) {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        (0 until TREND_POINTS).map { index ->
            format.format(Date(nowMillis - (TREND_POINTS - index) * 60_000L))
        }
    }
    val clockLabel = remember(nowMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMillis))
    }

    // Demo simulation drive loop, paused when the app leaves the foreground.
    LaunchedEffect(mode, paused) {
        if (mode != FeedMode.DEMO || paused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (isActive) {
                withFrameNanos { frameTime ->
                    if (previous == 0L) previous = frameTime
                    val delta = ((frameTime - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    previous = frameTime
                    if (delta > 0f) simulator.advance(delta)
                    snapshot = simulator.snapshot()
                }
            }
        }
    }

    // Live server stream + telemetry polling.
    LaunchedEffect(mode, serverUrl, reconnectTick) {
        if (mode != FeedMode.SERVER) return@LaunchedEffect
        streamFrame = null
        serverStats = null
        streamError = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                try {
                    TrafficApi.frames(serverUrl).collect { bitmap ->
                        streamFrame = bitmap
                        streamError = null
                    }
                    if (isActive) streamError = "The server closed the stream"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    streamError = TrafficApi.friendlyError(error)
                }
            }
            launch {
                while (isActive) {
                    val stats = TrafficApi.fetchStats(serverUrl)
                    if (stats != null) serverStats = stats
                    delay(2600)
                }
            }
        }
    }

    val serverMode = mode == FeedMode.SERVER
    val deviceMode = mode == FeedMode.ON_DEVICE
    val counts: TypeCounts? = when {
        serverMode -> serverStats?.counts
        deviceMode -> deviceSnapshot.counts
        else -> snapshot.counts
    }
    val deltas: TypeCounts = when {
        serverMode -> serverStats?.deltas ?: TypeCounts.Zero
        deviceMode -> deviceSnapshot.deltas
        else -> snapshot.deltas
    }
    val trend: List<TypeCounts> = when {
        serverMode -> (serverStats?.trend ?: emptyList()).takeLast(TREND_POINTS)
        deviceMode -> deviceSnapshot.trend
        else -> snapshot.trend
    }
    val fps = when {
        serverMode -> serverStats?.fps ?: 0
        deviceMode -> deviceFps
        else -> snapshot.fps
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Tint.BackgroundTop, Color(0xFF080A1A), Tint.BackgroundBottom)
                )
            )
    ) {
        AmbientGlow()

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Header(
                onSettings = { settingsOpen = true },
                showPause = mode == FeedMode.DEMO,
                paused = paused,
                onTogglePause = { paused = !paused }
            )

            Spacer(Modifier.height(16.dp))
            VideoCard(
                mode = mode,
                snapshot = snapshot,
                clockLabel = clockLabel,
                frame = streamFrame,
                error = streamError,
                fps = fps,
                onRetry = { reconnectTick++ },
                onOpenSettings = { settingsOpen = true },
                paused = paused,
                onDeviceSnapshot = { deviceSnapshot = it },
                onDeviceFps = { deviceFps = it }
            )

            Spacer(Modifier.height(12.dp))
            StatsGrid(counts = counts, deltas = deltas)

            Spacer(Modifier.height(12.dp))
            TrendCard(trend = trend, labels = minuteLabels, mode = mode)

            Spacer(Modifier.height(12.dp))
            Footer(mode = mode, serverUrl = serverUrl, hasTelemetry = serverStats != null)

            Spacer(Modifier.height(22.dp))
        }
    }

    if (settingsOpen) {
        ServerSheet(
            initialMode = mode,
            initialUrl = serverUrl,
            onDismiss = { settingsOpen = false },
            onApply = { newMode, url ->
                settingsOpen = false
                val normalized = TrafficApi.normalizeBase(url)
                preferences.edit()
                    .putString("feed_mode", newMode.name)
                    .putString("server_url", normalized)
                    .apply()
                serverUrl = normalized
                if (newMode == FeedMode.SERVER && mode == FeedMode.SERVER) {
                    reconnectTick++
                } else {
                    mode = newMode
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun Header(
    onSettings: () -> Unit,
    showPause: Boolean,
    paused: Boolean,
    onTogglePause: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF22D3EE))))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(25.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Traffic Lens",
                color = Tint.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "REAL-TIME TRAFFIC DETECTION",
                color = Tint.TextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp
            )
        }
        if (showPause) {
            RoundIconButton(
                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                tint = if (paused) Tint.Green else Tint.TextSecondary,
                description = if (paused) "Resume simulation" else "Pause simulation",
                onClick = onTogglePause
            )
            Spacer(Modifier.width(8.dp))
        }
        RoundIconButton(
            icon = Icons.Rounded.Settings,
            tint = Tint.TextPrimary,
            description = "Feed settings",
            onClick = onSettings
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    tint: Color,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(19.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Video panel
// ---------------------------------------------------------------------------------------------

@Composable
private fun VideoCard(
    mode: FeedMode,
    snapshot: TrafficSnapshot,
    clockLabel: String,
    frame: Bitmap?,
    error: String?,
    fps: Int,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    paused: Boolean,
    onDeviceSnapshot: (TrafficSnapshot) -> Unit,
    onDeviceFps: (Int) -> Unit
) {
    val live = mode == FeedMode.ON_DEVICE || (mode == FeedMode.SERVER && frame != null)
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardHeader(
                title = "Real-Time Traffic Detection",
                subtitle = when (mode) {
                    FeedMode.SERVER -> "Flask MJPEG · YOLOv8n"
                    FeedMode.ON_DEVICE -> "On-device TFLite · offline"
                    FeedMode.DEMO -> "Simulated night feed · YOLOv8n"
                },
                icon = Icons.Rounded.DirectionsCar,
                accent = VehicleType.CAR.color,
                trailing = { StatusPill(live = live) }
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.44f)
                .background(Color(0xFF05060F))
        ) {
            when {
                mode == FeedMode.SERVER && frame != null -> {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Live traffic camera feed",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                mode == FeedMode.SERVER -> ServerPlaceholder(
                    message = error,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings
                )

                mode == FeedMode.ON_DEVICE -> {
                    CameraDetectionPanel(
                        modifier = Modifier.fillMaxSize(),
                        onSnapshot = onDeviceSnapshot,
                        onFps = onDeviceFps
                    )
                }

                else -> {
                    TrafficScene(
                        snapshot = snapshot,
                        clockLabel = clockLabel,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (paused) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xCC05060F))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "PAUSED",
                                color = Tint.TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerPlaceholder(message: String?, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Tint.Rose.copy(alpha = 0.12f))
                .border(1.dp, Tint.Rose.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = Tint.Rose,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = message ?: "Connecting to the Flask server…",
            color = Tint.TextSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Retry", filled = true, onClick = onRetry)
            PillButton("Settings", filled = false, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (filled) Color.White else Tint.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (filled) Tint.Violet else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (filled) Color.Transparent else Color.White.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

// ---------------------------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatsGrid(counts: TypeCounts?, deltas: TypeCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "Total Vehicles",
                value = counts?.total,
                delta = deltas.total,
                icon = Icons.Rounded.DirectionsCar,
                accent = Tint.Cyan,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Trucks",
                value = counts?.trucks,
                delta = deltas.trucks,
                icon = Icons.Rounded.LocalShipping,
                accent = VehicleType.TRUCK.color,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "Motorcycles",
                value = counts?.motorcycles,
                delta = deltas.motorcycles,
                icon = Icons.Rounded.TwoWheeler,
                accent = VehicleType.MOTORCYCLE.color,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Buses",
                value = counts?.buses,
                delta = deltas.buses,
                icon = Icons.Rounded.DirectionsBus,
                accent = VehicleType.BUS.color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trend + footer
// ---------------------------------------------------------------------------------------------

@Composable
private fun TrendCard(trend: List<TypeCounts>, labels: List<String>, mode: FeedMode) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 15.dp) {
        CardHeader(
            title = "Traffic Trend",
            subtitle = "Vehicles detected per minute",
            icon = Icons.Rounded.QueryStats,
            accent = Tint.Violet,
            trailing = {
                Text(
                    text = "15 min",
                    color = Tint.TextTertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        )
        Spacer(Modifier.height(12.dp))
        TrendLegend()
        Spacer(Modifier.height(6.dp))
        TrendChart(
            trend = trend,
            labels = labels,
            modifier = Modifier.fillMaxWidth(),
            emptyHint = when (mode) {
                FeedMode.SERVER -> "No telemetry — server exposes /stats?"
                FeedMode.ON_DEVICE -> "Point the camera at traffic to build a trend…"
                FeedMode.DEMO -> "Warming up the simulation…"
            }
        )
    }
}

@Composable
private fun Footer(mode: FeedMode, serverUrl: String, hasTelemetry: Boolean) {
    Column {
        Text(
            text = when (mode) {
                FeedMode.SERVER ->
                    if (hasTelemetry) "Streaming from $serverUrl" else "Video linked · waiting for /stats telemetry"
                FeedMode.ON_DEVICE -> "On-device inference · SSD-MobileNet · works offline"
                FeedMode.DEMO -> "On-device simulation · counts are synthetic"
            },
            color = Tint.TextTertiary,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (mode) {
                FeedMode.ON_DEVICE -> {
                    TechChip("TFLite")
                    TechChip("SSD-MobileNet")
                    TechChip("CameraX")
                    TechChip("Offline")
                }

                else -> {
                    TechChip("Python")
                    TechChip("OpenCV")
                    TechChip("YOLOv8")
                    TechChip("Flask")
                    TechChip("MJPEG")
                }
            }
        }
    }
}

@Composable
private fun AmbientGlow() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x338B5CF6), Color(0x00000000))
                )
            )
    )
}
