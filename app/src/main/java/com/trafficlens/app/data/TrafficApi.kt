package com.trafficlens.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Aggregated telemetry published by the companion Flask server. */
data class ServerStats(
    val counts: TypeCounts,
    val deltas: TypeCounts,
    val trend: List<TypeCounts>,
    val fps: Int
)

/**
 * Minimal MJPEG / JSON client for the `python server/app.py` companion.
 *
 * The server exposes `GET /video_feed` (multipart JPEG) and `GET /stats` (JSON), which is
 * exactly the contract from the reference implementation.
 */
object TrafficApi {

    fun normalizeBase(raw: String): String {
        var base = raw.trim()
        if (base.isEmpty()) return ""
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        base = base.trimEnd('/')
        if (base.endsWith("/video_feed")) base = base.removeSuffix("/video_feed")
        if (base.endsWith("/stats")) base = base.removeSuffix("/stats")
        return base
    }

    fun frames(baseUrl: String): Flow<Bitmap> = flow {
        val endpoint = "${normalizeBase(baseUrl)}/video_feed"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 15000
                setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg")
                setRequestProperty("User-Agent", "TrafficLens/1.0 (Android)")
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server answered HTTP $code")

            val input = BufferedInputStream(connection.inputStream, 64 * 1024)
            val frame = ByteArrayOutputStream(256 * 1024)
            var previous = -1
            var inImage = false

            while (currentCoroutineContext().isActive) {
                val byte = input.read()
                if (byte == -1) break
                if (!inImage) {
                    if (previous == 0xFF && byte == 0xD8) {
                        inImage = true
                        frame.reset()
                        frame.write(0xFF)
                        frame.write(0xD8)
                    }
                } else {
                    frame.write(byte)
                    if (previous == 0xFF && byte == 0xD9) {
                        val bytes = frame.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { emit(it) }
                        frame.reset()
                        inImage = false
                    }
                }
                previous = byte
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun fetchStats(baseUrl: String): ServerStats? = withContext(Dispatchers.IO) {
        val endpoint = "${normalizeBase(baseUrl)}/stats"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseStats(body)
        } catch (error: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseStats(body: String): ServerStats? {
        return runCatching {
            val root = JSONObject(body)
            val cars = root.optInt("cars", 0)
            val trucks = root.optInt("trucks", 0)
            val buses = root.optInt("buses", 0)
            val motorcycles = root.optInt("motorcycles", 0)
            val counts = TypeCounts(cars, trucks, buses, motorcycles)
            val deltas = TypeCounts(
                root.optJSONObject("delta")?.optInt("cars", 0) ?: 0,
                root.optJSONObject("delta")?.optInt("trucks", 0) ?: 0,
                root.optJSONObject("delta")?.optInt("buses", 0) ?: 0,
                root.optJSONObject("delta")?.optInt("motorcycles", 0) ?: 0
            )
            val trend = mutableListOf<TypeCounts>()
            val array: JSONArray? = root.optJSONArray("trend")
            if (array != null) {
                for (index in 0 until minOf(array.length(), 32)) {
                    val item = array.optJSONObject(index) ?: continue
                    trend.add(
                        TypeCounts(
                            item.optInt("cars", 0),
                            item.optInt("trucks", 0),
                            item.optInt("buses", 0),
                            item.optInt("motorcycles", 0)
                        )
                    )
                }
            }
            ServerStats(
                counts = counts,
                deltas = deltas,
                trend = trend.takeLast(16),
                fps = root.optInt("fps", 30)
            )
        }.getOrNull()
    }

    fun friendlyError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Host not found — check the IP address"
        is java.net.ConnectException -> "Connection refused — is the Flask server running?"
        is java.net.SocketTimeoutException -> "Timed out waiting for the stream"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Could not reach the server"
    }
}
