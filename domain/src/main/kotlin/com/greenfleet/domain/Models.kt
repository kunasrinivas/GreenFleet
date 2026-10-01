package com.greenfleet.domain

import java.time.Instant
import java.util.UUID

enum class LocationType { SOURCE, DROPOFF, FINAL_DESTINATION }
enum class FuelType { PETROL }

data class Coordinate(val lat: Double, val lng: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(lng.isFinite() && lng in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }
    companion object {
        fun parse(text: String): Coordinate? {
            val parts = text.trim().split(',')
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lng = parts[1].trim().toDoubleOrNull() ?: return null
            return Coordinate(lat, lng)
        }
    }
}

data class Location(
    val id: String,
    val name: String,
    val address: String,
    val coordinate: Coordinate,
    val type: LocationType,
) {
    init { require(id.isNotBlank() && name.isNotBlank() && address.length <= 300) }
}

data class Vehicle(
    val id: String = "petrol-car",
    val name: String = "Standard petrol car",
    val fuelType: FuelType = FuelType.PETROL,
    val emissionFactorGPerKm: Double = 180.0,
) {
    init { require(emissionFactorGPerKm.isFinite() && emissionFactorGPerKm in 1.0..1000.0) }
}

data class RoadCost(val distanceMeters: Double, val durationSeconds: Double) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0) { "Invalid road distance." }
        require(durationSeconds.isFinite() && durationSeconds >= 0) { "Invalid road duration." }
        require(distanceMeters == 0.0 || durationSeconds > 0.0) { "Missing road duration." }
    }
    operator fun plus(other: RoadCost) = RoadCost(distanceMeters + other.distanceMeters, durationSeconds + other.durationSeconds)
    val distanceKm get() = distanceMeters / 1000.0
    val durationMinutes get() = durationSeconds / 60.0
    val averageSpeedKmh get() = if (durationSeconds == 0.0) 0.0 else distanceKm / (durationSeconds / 3600)
    fun isBetterThan(other: RoadCost): Boolean =
        distanceMeters < other.distanceMeters ||
            (distanceMeters == other.distanceMeters && durationSeconds < other.durationSeconds)
    companion object { val ZERO = RoadCost(0.0, 0.0) }
}

/** Directed: matrix[a,b] need not equal matrix[b,a] (one-way roads). No geodesic fallback. */
class RoadMatrix(private val rows: List<List<RoadCost>>) {
    val size = rows.size
    init {
        require(size in 3..52 && rows.all { it.size == size }) { "Incomplete road matrix." }
        require(rows.indices.all { rows[it][it] == RoadCost.ZERO }) { "Matrix diagonal must be zero." }
    }
    operator fun get(from: Int, to: Int) = rows[from][to]
    fun cost(order: List<Int>): RoadCost = order.zipWithNext().fold(RoadCost.ZERO) { sum, (a, b) -> sum + get(a, b) }
}

data class RouteRequest(val source: String, val dropoffs: List<String>, val finalDestination: String? = null) {
    fun validate(maxDropoffs: Int = 20) {
        require(dropoffs.size in 2..maxDropoffs) { "Enter between 2 and $maxDropoffs drop-offs." }
        val inputs = listOf(source) + dropoffs + listOfNotNull(finalDestination?.takeIf { it.isNotBlank() })
        require(inputs.all { it.trim().length in 3..300 && it.none(Char::isISOControl) }) {
            "Use an address or latitude,longitude (3–300 characters) for every location."
        }
        require(inputs.map { it.trim().lowercase() }.distinct().size == inputs.size) { "Each location must be unique." }
        inputs.forEach { Coordinate.parse(it) } // Reject numeric out-of-range coordinates before networking.
    }
}

data class RoadPath(val geometry: List<Coordinate>, val legs: List<RoadCost>) {
    init { require(geometry.size in 2..200000 && legs.isNotEmpty()) { "Missing route geometry or legs." } }
    val cost get() = legs.fold(RoadCost.ZERO, RoadCost::plus)
}

data class Route(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    val locations: List<Location>,
    val baselineOrder: List<Int>,
    val optimizedOrder: List<Int>,
    val path: RoadPath,
    val baselineCost: RoadCost,
    val vehicle: Vehicle,
    val isDemo: Boolean,
) {
    val sourceLocationId get() = locations.first().id
    val finalDestinationLocationId get() = locations.last().id
    val stopLocationIds get() = locations.filter { it.type == LocationType.DROPOFF }.map { it.id }
    val totalDistanceKm get() = path.cost.distanceKm
    val totalDurationMinutes get() = path.cost.durationMinutes
    val totalCo2Grams get() = Co2Calculator.grams(totalDistanceKm, vehicle)
    val co2SavedGrams get() = Co2Calculator.grams(baselineCost.distanceKm, vehicle) - totalCo2Grams
    val distanceSavedKm get() = baselineCost.distanceKm - totalDistanceKm
    val timeSavedMinutes get() = baselineCost.durationMinutes - totalDurationMinutes
    val orderedLocations get() = optimizedOrder.map(locations::get)
}

object Co2Calculator {
    /** Tailpipe estimate only. Speed is reported, not used to invent an unvalidated correction. */
    fun grams(distanceKm: Double, vehicle: Vehicle): Double {
        require(distanceKm.isFinite() && distanceKm >= 0) { "Distance must be finite and non-negative." }
        return (distanceKm * vehicle.emissionFactorGPerKm).also { require(it.isFinite()) }
    }
}
