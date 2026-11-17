package com.trafficlens.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trafficlens.app.data.Detection
import com.trafficlens.app.data.DetectionStats
import com.trafficlens.app.data.TrafficSnapshot
import com.trafficlens.app.data.VehicleDetector
import com.trafficlens.app.ui.theme.Tint
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera preview plus on-device TFLite detection. Everything here works with no network:
 * the model and the sample frame are packaged in the APK.
 */
@Composable
fun CameraDetectionPanel(
    modifier: Modifier = Modifier,
    onSnapshot: (TrafficSnapshot) -> Unit,
    onFps: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { androidx.core.content.ContextCompat.getMainExecutor(context) }

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var sampleMode by remember { mutableStateOf(false) }
    var sampleFrame by remember { mutableStateOf<Bitmap?>(null) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var frameAspect by remember { mutableFloatStateOf(4f / 3f) }
    var fps by remember { mutableIntStateOf(0) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Camera controls: lens facing and zoom.
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var hasFrontCamera by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var zoomObserver by remember { mutableStateOf<Pair<LiveData<ZoomState>, Observer<ZoomState>>?>(null) }

    val detectorLoad = remember { runCatching { VehicleDetector(context) } }
    val detector = detectorLoad.getOrNull()
    val modelError = detectorLoad.exceptionOrNull()?.let { "Model failed to load: ${it.message}" }
    val stats = remember { DetectionStats() }
    val inferenceExecutor = remember { Executors.newSingleThreadExecutor() }
    val busy = remember { AtomicBoolean(false) }
    val frameTimes = remember { ArrayDeque<Long>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) cameraError = null
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            inferenceExecutor.shutdownNow()
            detector?.close()
        }
    }

    DisposableEffect(permissionGranted, sampleMode, previewView, lensFacing) {
        val view = previewView
        if (!permissionGranted || sampleMode || view == null) {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            camera = null
            return@DisposableEffect onDispose { }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
            val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            hasFrontCamera = hasFront
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                // Requested lens is unavailable: fall back once, or report a camera-less device.
                mainExecutor.execute {
                    when {
                        lensFacing == CameraSelector.LENS_FACING_BACK && hasFront ->
                            lensFacing = CameraSelector.LENS_FACING_FRONT

                        lensFacing == CameraSelector.LENS_FACING_FRONT && hasBack ->
                            lensFacing = CameraSelector.LENS_FACING_BACK

                        else -> cameraError = "No camera available on this device"
                    }
                }
                return@Runnable
            }
            try {
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(view.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(640, 480))
                    .build()

                analysis.setAnalyzer(inferenceExecutor) { image ->
                    analyze(
                        image = image,
                        detector = detector,
                        busy = busy,
                        onResult = { detected, aspect ->
                            mainExecutor.execute {
                                val now = System.currentTimeMillis()
                                frameTimes.addLast(now)
                                while (frameTimes.size > 6) frameTimes.removeFirst()
                                val measured = if (frameTimes.size >= 2) {
                                    val span = frameTimes.last() - frameTimes.first()
                                    if (span > 0) ((frameTimes.size - 1) * 1000L / span).toInt() else 0
                                } else {
                                    0
                                }
                                val snapshot = stats.update(detected, now, measured)
                                detections = detected
                                frameAspect = aspect
                                fps = measured
                                onFps(measured)
                                onSnapshot(snapshot)
                            }
                        }
                    )
                }

                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    analysis
                )

                camera = bound
                zoomRatio = 1f
                val liveZoom = bound.cameraInfo.zoomState
                val observer = Observer<ZoomState> { state ->
                    minZoom = state.minZoomRatio
                    maxZoom = state.maxZoomRatio
                    zoomRatio = state.zoomRatio
                }
                zoomObserver?.let { (previousLive, previousObserver) ->
                    previousLive.removeObserver(previousObserver)
                }
                liveZoom.observeForever(observer)
                zoomObserver = liveZoom to observer
                cameraError = null
            } catch (error: Exception) {
                cameraError = error.message ?: "Camera unavailable"
            }
        }
        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
            zoomObserver?.let { (live, observer) -> live.removeObserver(observer) }
            zoomObserver = null
            camera = null
        }
    }

    fun applyZoom(target: Float) {
        val bound = camera ?: return
        val clamped = target.coerceIn(minZoom, maxZoom)
        bound.cameraControl.setZoomRatio(clamped)
        zoomRatio = clamped
    }

    fun zoomBy(factor: Float) = applyZoom(zoomRatio * factor)

    val pinchToZoom = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val factor = event.calculateZoom()
                    val bound = camera
                    if (factor != 1f && bound != null) {
                        val target = (zoomRatio * factor).coerceIn(minZoom, maxZoom)
                        bound.cameraControl.setZoomRatio(target)
                        zoomRatio = target
                        event.changes.forEach { it.consume() }
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }

    fun scanSampleFrame() {
        inferenceExecutor.execute {
            val bitmap = runCatching {
                context.assets.open(VehicleDetector.SAMPLE_ASSET).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            val detected = runCatching { detector?.detect(bitmap).orEmpty() }
                .onFailure { android.util.Log.e("TrafficLens", "Sample detection failed", it) }
                .getOrDefault(emptyList())
            mainExecutor.execute {
                sampleMode = true
                sampleFrame = bitmap
                detections = detected
                frameAspect = bitmap.width.toFloat() / bitmap.height
                stats.reset()
                val now = System.currentTimeMillis()
                stats.update(detected, now, 0)
                onSnapshot(stats.update(detected, now + 80, 0))
                fps = 0
            }
        }
    }

    Box(modifier.background(Color(0xFF05060F)).then(pinchToZoom)) {
        when {
            sampleMode && sampleFrame != null -> Image(
                bitmap = sampleFrame!!.asImageBitmap(),
                contentDescription = "Sample traffic frame analysed offline",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            permissionGranted && cameraError == null -> AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }.also { previewView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            else -> CameraPlaceholder(
                error = cameraError ?: modelError,
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSample = { scanSampleFrame() }
            )
        }

        DetectionsOverlay(
            detections = detections,
            frameAspect = frameAspect,
            mirror = !sampleMode && lensFacing == CameraSelector.LENS_FACING_FRONT,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudChip(
                text = if (sampleMode) "SAMPLE FRAME" else "ON-DEVICE",
                dot = Tint.Green
            )
            Spacer(Modifier.weight(1f))
            HudChip(
                text = if (sampleMode) "TFLITE · OFFLINE" else "SSD · $fps FPS",
                dot = null
            )
        }

        if (sampleMode) {
            Text(
                text = "Back to camera",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable {
                        sampleMode = false
                        detections = emptyList()
                        stats.reset()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        } else if (permissionGranted && cameraError == null) {
            Text(
                text = "Point the camera at traffic",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 8.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CameraControlButton(
                    icon = Icons.Rounded.FlipCameraAndroid,
                    description = "Switch front or back camera",
                    enabled = hasFrontCamera
                ) {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }
                CameraControlButton(
                    icon = Icons.Rounded.Remove,
                    description = "Zoom out",
                    enabled = zoomRatio > minZoom + 0.01f
                ) {
                    zoomBy(1f / 1.35f)
                }
                HudChip(
                    text = String.format(Locale.US, "%.1f×", zoomRatio),
                    dot = null
                )
                CameraControlButton(
                    icon = Icons.Rounded.Add,
                    description = "Zoom in",
                    enabled = zoomRatio < maxZoom - 0.01f
                ) {
                    zoomBy(1.35f)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------------------------

private fun analyze(
    image: ImageProxy,
    detector: VehicleDetector?,
    busy: AtomicBoolean,
    onResult: (List<Detection>, Float) -> Unit
) {
    if (detector == null || !busy.compareAndSet(false, true)) {
        image.close()
        return
    }
    val bitmap = try {
        image.toArgbBitmap()
    } catch (error: Throwable) {
        image.close()
        busy.set(false)
        return
    }
    val rotation = image.imageInfo.rotationDegrees
    image.close()

    val upright = try {
        if (rotation == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true
            ).also { if (it !== bitmap) bitmap.recycle() }
        }
    } catch (error: Throwable) {
        bitmap.recycle()
        busy.set(false)
        return
    }

    // Analysis already runs on the inference executor.
    try {
        val detected = detector.detect(upright)
        onResult(detected, upright.width.toFloat() / upright.height.toFloat())
    } catch (error: Throwable) {
        onResult(emptyList(), upright.width.toFloat() / upright.height.toFloat())
    } finally {
        upright.recycle()
        busy.set(false)
    }
}

/** Converts an RGBA_8888 ImageProxy into an ARGB bitmap, accounting for row padding. */
private fun ImageProxy.toArgbBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + rowPadding / pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)
    if (paddedWidth == width) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}

// ---------------------------------------------------------------------------------------------
// Overlay + chrome
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetectionsOverlay(
    detections: List<Detection>,
    frameAspect: Float,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (detections.isEmpty() || frameAspect <= 0f) return@Canvas
        val width = size.width
        val height = size.height
        var drawWidth = height * frameAspect
        var drawHeight = height
        if (drawWidth > width) {
            drawWidth = width
            drawHeight = width / frameAspect
        }
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        detections.forEach { detection ->
            val color = detection.type.color
            // The front preview is mirrored by PreviewView while analysis frames are not.
            val detectionLeft = if (mirror) 1f - detection.right else detection.left
            val detectionRight = if (mirror) 1f - detection.left else detection.right
            val boxLeft = left + detectionLeft * drawWidth
            val boxTop = top + detection.top * drawHeight
            val boxWidth = (detectionRight - detectionLeft) * drawWidth
            val boxHeight = (detection.bottom - detection.top) * drawHeight

            drawRoundRect(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(boxLeft, boxTop),
                size = GeometrySize(boxWidth, boxHeight),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )

            val label = "${detection.type.key} ${String.format(Locale.US, "%.2f", detection.score)}"
            val measured = measurer.measure(
                text = label,
                style = TextStyle(
                    color = Color(0xFF07090F),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            val chipWidth = measured.size.width + 6.dp.toPx()
            val chipHeight = measured.size.height + 2.dp.toPx()
            val chipLeft = boxLeft.coerceIn(0f, (width - chipWidth).coerceAtLeast(0f))
            val chipTop = (boxTop - chipHeight - 1.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                color = color,
                topLeft = Offset(chipLeft, chipTop),
                size = GeometrySize(chipWidth, chipHeight),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(chipLeft + 3.dp.toPx(), chipTop + 1.dp.toPx())
            )
        }
    }
}

@Composable
private fun HudChip(text: String, dot: Color?) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun CameraPlaceholder(
    error: String?,
    onGrant: () -> Unit,
    onSample: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Tint.Green.copy(alpha = 0.10f))
                .border(1.dp, Tint.Green.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = null,
                tint = Tint.Green,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (error != null) "Camera unavailable" else "Camera access needed",
            color = Tint.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = error ?: "Detection runs entirely on this device — the model ships inside the app, no network required.",
            color = Tint.TextTertiary,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Grant camera",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Tint.Violet)
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
            Text(
                text = "Scan sample frame",
                color = Tint.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                    .clickable(onClick = onSample)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun CameraControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xB3000000) else Color(0x66000000))
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.18f else 0.07f), CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.3f),
            modifier = Modifier.size(17.dp)
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
