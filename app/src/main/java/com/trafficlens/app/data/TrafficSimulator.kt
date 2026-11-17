package com.trafficlens.app.data

import kotlin.math.pow
import kotlin.random.Random

/**
 * Deterministic micro-traffic simulator that stands in for the YOLO detector.
 *
 * It models four lanes of oncoming traffic in perspective, spawns vehicles at the horizon,
 * retires them at the camera, and keeps the same aggregate bookkeeping a real detector would
 * publish: per-class counts, recent deltas, and a 15-minute flow history.
 */
class TrafficSimulator(
    seed: Long = 0x7A3F11,
    private val historyMinutes: Int = 15,
    private val laneCount: Int = 4
) {

    private class SimVehicle(
        val id: Int,
        val type: VehicleType,
        val lane: Int,
        var z: Float,
        val speed: Float,
        val confidence: Float,
        val paint: Int,
        var sway: Float
    )

    private val random = Random(seed)
    private val active = ArrayList<SimVehicle>(20)
    private val buckets = ArrayDeque<TypeCounts>()
    private var currentMinute = TypeCounts.Zero
    private var minuteClock = 0f
    private val recentExits = ArrayDeque<Pair<Float, VehicleType>>()
    private var clock = 0f
    private var nextId = 1
    private var spawnCountdown = 0.9f

    private val deltaWindowSeconds = 20f

    init {
        // Pre-roll a full window so the dashboard opens with a populated chart and counters.
        val warmup = (historyMinutes + 1) * 60f
        var t = 0f
        while (t < warmup) {
            advance(0.05f)
            t += 0.05f
        }
    }

    /** Advances the simulation by [dt] seconds. */
    fun advance(dt: Float) {
        if (dt <= 0f) return
        clock += dt

        spawnCountdown -= dt
        if (spawnCountdown <= 0f) {
            spawnCountdown = 1.25f + random.nextFloat() * 1.65f
            trySpawn()
        }

        var i = active.size - 1
        while (i >= 0) {
            val vehicle = active[i]
            vehicle.z += vehicle.speed * dt
            vehicle.sway = (vehicle.sway + dt * 1.7f) % 6.2832f
            if (vehicle.z >= 1.04f) {
                active.removeAt(i)
                currentMinute = currentMinute.plus(vehicle.type)
                recentExits.addLast(clock to vehicle.type)
            }
            i--
        }

        minuteClock += dt
        if (minuteClock >= 60f) {
            minuteClock -= 60f
            buckets.addLast(currentMinute)
            while (buckets.size > historyMinutes) buckets.removeFirst()
            currentMinute = TypeCounts.Zero
        }

        while (recentExits.isNotEmpty() && clock - recentExits.first().first > deltaWindowSeconds) {
            recentExits.removeFirst()
        }
    }

    private fun trySpawn() {
        if (active.size >= 18) return
        for (lane in (0 until laneCount).shuffled(random)) {
            if (active.any { it.lane == lane && it.z < 0.16f }) continue
            val type = pickType()
            active.add(
                SimVehicle(
                    id = nextId++,
                    type = type,
                    lane = lane,
                    z = 0.012f + random.nextFloat() * 0.02f,
                    speed = speedFor(type),
                    confidence = 0.80f + random.nextFloat() * 0.18f,
                    paint = random.nextInt(6),
                    sway = random.nextFloat() * 6.2832f
                )
            )
            return
        }
    }

    private fun pickType(): VehicleType {
        var roll = random.nextFloat()
        for (type in VehicleType.entries) {
            if (roll < type.distribution) return type
            roll -= type.distribution
        }
        return VehicleType.CAR
    }

    private fun speedFor(type: VehicleType): Float = when (type) {
        VehicleType.CAR -> 0.060f + random.nextFloat() * 0.030f
        VehicleType.TRUCK -> 0.048f + random.nextFloat() * 0.018f
        VehicleType.BUS -> 0.045f + random.nextFloat() * 0.015f
        VehicleType.MOTORCYCLE -> 0.075f + random.nextFloat() * 0.030f
    }

    fun snapshot(): TrafficSnapshot {
        val vehicles = active
            .sortedBy { it.z }
            .map {
                VehicleSnapshot(
                    id = it.id,
                    type = it.type,
                    lane = it.lane,
                    z = it.z,
                    confidence = it.confidence,
                    paint = it.paint,
                    sway = it.sway
                )
            }

        var windowCounts = TypeCounts.Zero
        for (bucket in buckets) windowCounts += bucket
        windowCounts += currentMinute

        var deltas = TypeCounts.Zero
        for ((at, type) in recentExits) {
            if (clock - at <= deltaWindowSeconds) deltas = deltas.plus(type)
        }

        val trend = ArrayList<TypeCounts>(historyMinutes)
        trend.addAll(buckets)
        while (trend.size < historyMinutes) trend.add(0, TypeCounts.Zero)

        return TrafficSnapshot(
            vehicles = vehicles,
            counts = windowCounts,
            deltas = deltas,
            trend = trend,
            fps = 30
        )
    }

    /** Perspective depth curve shared with the renderer. */
    companion object {
        fun depth(z: Float): Float = z.coerceIn(0f, 1f).pow(1.55f)
    }
}
