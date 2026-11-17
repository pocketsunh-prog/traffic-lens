package com.trafficlens.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * Turns a stream of per-frame detections into the same dashboard aggregates the simulation and
 * the server use: rolling 15-minute counters, 20-second deltas and a per-minute trend.
 *
 * Objects are matched between frames with IoU so a parked car is counted once, not sixty times
 * per second. A track is only counted after it has been seen twice, which filters flicker.
 */
class DetectionStats(
    private val historyMinutes: Int = 15,
    private val deltaWindowMs: Long = 20_000L,
    private val trackTtlMs: Long = 1_500L,
    private val iouThreshold: Float = 0.22f
) {

    private class Track(
        val id: Int,
        val type: VehicleType,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        var lastSeen: Long,
        var hits: Int = 1,
        var counted: Boolean = false
    )

    private val tracks = ArrayList<Track>()
    private val buckets = ArrayDeque<TypeCounts>()
    private var currentMinute = TypeCounts.Zero
    private var minuteStarted = System.currentTimeMillis()
    private val events = ArrayDeque<Pair<Long, VehicleType>>()
    private var nextId = 1

    fun activeCount(): Int = tracks.size

    fun update(
        detections: List<Detection>,
        now: Long = System.currentTimeMillis(),
        fps: Int = 0
    ): TrafficSnapshot {
        val unmatchedTracks = ArrayList(tracks)
        val matched = HashSet<Int>()

        detections.sortedByDescending { it.score }.forEach { detection ->
            var best: Track? = null
            var bestScore = iouThreshold
            for (track in unmatchedTracks) {
                if (track.id in matched || track.type != detection.type) continue
                val score = iou(track, detection)
                if (score > bestScore) {
                    bestScore = score
                    best = track
                }
            }
            if (best != null) {
                best.left = detection.left
                best.top = detection.top
                best.right = detection.right
                best.bottom = detection.bottom
                best.lastSeen = now
                best.hits += 1
                matched.add(best.id)
                if (!best.counted && best.hits >= 2) {
                    best.counted = true
                    currentMinute = currentMinute.plus(best.type)
                    events.addLast(now to best.type)
                }
            } else {
                val track = Track(
                    id = nextId++,
                    type = detection.type,
                    left = detection.left,
                    top = detection.top,
                    right = detection.right,
                    bottom = detection.bottom,
                    lastSeen = now
                )
                tracks.add(track)
                unmatchedTracks.add(track)
                matched.add(track.id)
            }
        }

        tracks.removeAll { now - it.lastSeen > trackTtlMs }

        while (now - minuteStarted >= 60_000L) {
            buckets.addLast(currentMinute)
            while (buckets.size > historyMinutes) buckets.removeFirst()
            currentMinute = TypeCounts.Zero
            minuteStarted += 60_000L
        }

        while (events.isNotEmpty() && now - events.first().first > deltaWindowMs) {
            events.removeFirst()
        }

        var window = TypeCounts.Zero
        for (bucket in buckets) window += bucket
        window += currentMinute

        var deltas = TypeCounts.Zero
        for ((at, type) in events) {
            if (now - at <= deltaWindowMs) deltas = deltas.plus(type)
        }

        val trend = ArrayList<TypeCounts>(historyMinutes + 1)
        trend.addAll(buckets)
        while (trend.size < historyMinutes) trend.add(0, TypeCounts.Zero)

        return TrafficSnapshot(
            vehicles = emptyList(),
            counts = window,
            deltas = deltas,
            trend = trend,
            fps = fps
        )
    }

    fun reset(now: Long = System.currentTimeMillis()) {
        tracks.clear()
        buckets.clear()
        currentMinute = TypeCounts.Zero
        minuteStarted = now
        events.clear()
    }

    private fun iou(track: Track, detection: Detection): Float {
        val left = max(track.left, detection.left)
        val top = max(track.top, detection.top)
        val right = min(track.right, detection.right)
        val bottom = min(track.bottom, detection.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val trackArea = (track.right - track.left) * (track.bottom - track.top)
        val detectionArea = (detection.right - detection.left) * (detection.bottom - detection.top)
        val union = trackArea + detectionArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
