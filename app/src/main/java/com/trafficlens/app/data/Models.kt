package com.trafficlens.app.data

import androidx.compose.ui.graphics.Color

/** The four detector classes used by the YOLO pipeline, with a stable accent color each. */
enum class VehicleType(
    val key: String,
    val label: String,
    val plural: String,
    val color: Color,
    val distribution: Float
) {
    CAR("car", "Car", "Cars", Color(0xFF38BDF8), 0.50f),
    TRUCK("truck", "Truck", "Trucks", Color(0xFFFB923C), 0.24f),
    BUS("bus", "Bus", "Buses", Color(0xFF34D399), 0.13f),
    MOTORCYCLE("motorcycle", "Motorcycle", "Motorcycles", Color(0xFFA78BFA), 0.13f);

    companion object {
        fun fromKey(key: String): VehicleType = entries.firstOrNull { it.key == key } ?: CAR
    }
}

/** Bucket of per-class counts. */
data class TypeCounts(
    val cars: Int = 0,
    val trucks: Int = 0,
    val buses: Int = 0,
    val motorcycles: Int = 0
) {
    val total: Int get() = cars + trucks + buses + motorcycles

    fun of(type: VehicleType): Int = when (type) {
        VehicleType.CAR -> cars
        VehicleType.TRUCK -> trucks
        VehicleType.BUS -> buses
        VehicleType.MOTORCYCLE -> motorcycles
    }

    fun plus(type: VehicleType): TypeCounts = when (type) {
        VehicleType.CAR -> copy(cars = cars + 1)
        VehicleType.TRUCK -> copy(trucks = trucks + 1)
        VehicleType.BUS -> copy(buses = buses + 1)
        VehicleType.MOTORCYCLE -> copy(motorcycles = motorcycles + 1)
    }

    operator fun plus(other: TypeCounts) = TypeCounts(
        cars + other.cars,
        trucks + other.trucks,
        buses + other.buses,
        motorcycles + other.motorcycles
    )

    companion object {
        val Zero = TypeCounts()
    }
}

/** One tracked vehicle as seen by the detector. */
data class VehicleSnapshot(
    val id: Int,
    val type: VehicleType,
    val lane: Int,
    /** 0 = horizon, 1 = camera. */
    val z: Float,
    val confidence: Float,
    val paint: Int,
    val sway: Float
)

/** Immutable frame handed to the UI. */
data class TrafficSnapshot(
    val vehicles: List<VehicleSnapshot> = emptyList(),
    val counts: TypeCounts = TypeCounts.Zero,
    val deltas: TypeCounts = TypeCounts.Zero,
    val trend: List<TypeCounts> = emptyList(),
    val fps: Int = 30
)
