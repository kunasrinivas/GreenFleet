package com.greenfleet.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface GeocodingService {
    suspend fun geocode(input: String, type: LocationType, id: String): Location
}
interface RoutingService {
    suspend fun matrix(locations: List<Location>): RoadMatrix
    suspend fun route(orderedLocations: List<Location>): RoadPath
}

class PlanRoute(
    private val geocoding: GeocodingService,
    private val routing: RoutingService,
    private val optimizer: RouteOptimizer = RouteOptimizer(),
) {
    suspend operator fun invoke(
        request: RouteRequest,
        vehicle: Vehicle = Vehicle(),
        isDemo: Boolean = false,
        progress: (String) -> Unit = {},
    ): Route = withContext(Dispatchers.Default) {
        request.validate(maxDropoffs = 50) // Presentation limits entry to 20; core supports 50.
        val inputs = listOf(request.source) + request.dropoffs + listOfNotNull(request.finalDestination?.takeIf { it.isNotBlank() })
        val locations = inputs.mapIndexed { index, input ->
            ensureActive()
            progress("Finding location ${index + 1} of ${inputs.size}")
            val type = when (index) {
                0 -> LocationType.SOURCE
                inputs.lastIndex -> if (inputs.size > request.dropoffs.size + 1) LocationType.FINAL_DESTINATION else LocationType.DROPOFF
                else -> LocationType.DROPOFF
            }
            try { geocoding.geocode(input.trim(), type, index.toString()) }
            catch (error: IllegalArgumentException) { throw IllegalArgumentException("Location ${index + 1}: ${error.message}") }
        }
        require(locations.map { it.coordinate }.distinct().size == locations.size) { "Two entries resolve to the same location. Remove duplicates." }
        progress("Building the driving-distance matrix")
        val matrix = routing.matrix(locations)
        require(matrix.size == locations.size) { "The road matrix is incomplete. Please retry." }
        val baseline = locations.indices.toList() + 0
        progress("Finding a shorter stop order")
        val context = currentCoroutineContext()
        var order = optimizer.optimize(matrix) { context.ensureActive() }
        progress("Checking the complete road routes")
        val baselinePath = routing.route(baseline.map(locations::get))
        require(baselinePath.legs.size == baseline.size - 1) { "The baseline road route is incomplete." }
        var path = if (order == baseline) baselinePath else routing.route(order.map(locations::get))
        require(path.legs.size == order.size - 1) { "The optimized road route is incomplete." }
        // The full route service can differ from matrix paths. Compare actual returned legs too.
        if (baselinePath.cost.isBetterThan(path.cost)) { order = baseline; path = baselinePath }
        Route(locations = locations, baselineOrder = baseline, optimizedOrder = order, path = path,
            baselineCost = baselinePath.cost, vehicle = vehicle, isDemo = isDemo)
    }
}

