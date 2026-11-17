package com.trafficlens.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/** One detected vehicle, normalised to the upright input frame. */
data class Detection(
    val type: VehicleType,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * SSD MobileNet v1 (COCO, quantised) running through TensorFlow Lite.
 *
 * Entirely on-device: the 4 MB model ships in the APK, so detection needs no network and no
 * Python server. Only the four vehicle classes from [VehicleType] are surfaced.
 */
class VehicleDetector(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val floatInput: Boolean
    private val boxesIndex: Int
    private val classesIndex: Int
    private val scoresIndex: Int
    private val countIndex: Int
    private val maxDetections: Int

    val labelsSummary: String

    init {
        interpreter = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(4))

        val inputShape = interpreter.getInputTensor(0).shape()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        floatInput = interpreter.getInputTensor(0).dataType() == DataType.FLOAT32

        labels = context.assets.open("labels.txt").bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trim() }

        var boxes = -1
        var count = -1
        val rankTwo = ArrayList<Int>(2)
        for (index in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(index).shape()
            when {
                shape.size == 3 && shape[2] == 4 -> boxes = index
                shape.size == 1 -> count = index
                shape.size == 2 -> rankTwo.add(index)
            }
        }
        require(boxes >= 0 && count >= 0 && rankTwo.size >= 2) {
            "Unexpected model outputs: boxes=$boxes count=$count rank2=$rankTwo"
        }
        boxesIndex = boxes
        countIndex = count
        // TF's SSD export orders the two [1, N] tensors as classes then scores.
        classesIndex = rankTwo[0]
        scoresIndex = rankTwo[1]
        maxDetections = interpreter.getOutputTensor(boxesIndex).shape()[1]
        labelsSummary = "SSD-MobileNet · ${inputWidth}×${inputHeight}"
    }

    /** Runs detection on an upright bitmap and returns vehicle detections in normalised coordinates. */
    @Synchronized
    fun detect(bitmap: Bitmap, threshold: Float = 0.5f): List<Detection> {
        if (bitmap.width == 0 || bitmap.height == 0) return emptyList()

        val scale = min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val offsetX = (inputWidth - drawWidth) / 2f
        val offsetY = (inputHeight - drawHeight) / 2f

        val input = buildInput(bitmap, scale, offsetX, offsetY, drawWidth, drawHeight)

        val boxes = Array(1) { Array(maxDetections) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxDetections) }
        val scores = Array(1) { FloatArray(maxDetections) }
        val count = FloatArray(1)

        val outputs = HashMap<Int, Any>(4)
        outputs[boxesIndex] = boxes
        outputs[classesIndex] = classes
        outputs[scoresIndex] = scores
        outputs[countIndex] = count

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val available = count[0].toInt().coerceIn(0, maxDetections)

        val results = ArrayList<Detection>()
        for (index in 0 until available) {
            val score = scores[0][index]
            if (score < threshold) continue
            val type = typeForClass(classes[0][index].toInt()) ?: continue

            val ymin = boxes[0][index][0]
            val xmin = boxes[0][index][1]
            val ymax = boxes[0][index][2]
            val xmax = boxes[0][index][3]

            val left = ((xmin * inputWidth - offsetX) / drawWidth).coerceIn(0f, 1f)
            val top = ((ymin * inputHeight - offsetY) / drawHeight).coerceIn(0f, 1f)
            val right = ((xmax * inputWidth - offsetX) / drawWidth).coerceIn(0f, 1f)
            val bottom = ((ymax * inputHeight - offsetY) / drawHeight).coerceIn(0f, 1f)

            if (right - left < 0.01f || bottom - top < 0.01f) continue
            // Reject near-full-frame boxes: single vehicles never span the whole scene, and SSD
            // occasionally hallucinates one on heavily textured tarmac.
            if ((right - left) > 0.94f && (bottom - top) > 0.45f) continue
            results.add(Detection(type, score, left, top, right, bottom))
        }
        return results.sortedByDescending { it.score }
    }

    private fun buildInput(
        bitmap: Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        drawWidth: Float,
        drawHeight: Float
    ): ByteBuffer {
        val scaled = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight),
            paint
        )

        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        scaled.recycle()

        return if (floatInput) {
            val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val floats = buffer.asFloatBuffer()
            for (pixel in pixels) {
                floats.put(((pixel shr 16) and 0xFF) / 255f)
                floats.put(((pixel shr 8) and 0xFF) / 255f)
                floats.put((pixel and 0xFF) / 255f)
            }
            floats.rewind()
            buffer
        } else {
            val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3)
                .order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xFF).toByte())
                buffer.put(((pixel shr 8) and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
            }
            buffer.rewind()
            buffer
        }
    }

    /**
     * The bundled COCO label map starts with a `???` background entry, while the exported graph
     * emits zero-based COCO ids — so class 2 has to resolve to the third real label, "car".
     */
    private fun typeForClass(classId: Int): VehicleType? {
        val backgroundOffset = if (labels.firstOrNull()?.startsWith("?") == true) 1 else 0
        return typeFor(labels.getOrNull(classId + backgroundOffset))
    }

    private fun typeFor(label: String?): VehicleType? = when (label?.trim()?.lowercase()) {
        "car" -> VehicleType.CAR
        "truck" -> VehicleType.TRUCK
        "bus" -> VehicleType.BUS
        "motorcycle", "motorbike" -> VehicleType.MOTORCYCLE
        else -> null
    }

    override fun close() {
        interpreter.close()
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
    }

    companion object {
        const val MODEL_ASSET = "coco_ssd_mobilenet.tflite"
        const val SAMPLE_ASSET = "sample_traffic.jpg"
    }
}
