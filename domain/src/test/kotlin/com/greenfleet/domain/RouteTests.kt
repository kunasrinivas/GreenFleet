package com.greenfleet.domain

import kotlinx.coroutines.*
import org.junit.Test
import kotlin.random.Random
import kotlin.test.*

class RouteTests {
    @Test fun `CO2 units and configurable factor are consistent`() {
        assertEquals(1800.0, Co2Calculator.grams(10.0, Vehicle()))
        assertEquals(1200.0, Co2Calculator.grams(10.0, Vehicle(emissionFactorGPerKm = 120.0)))
        assertEquals(0.0, Co2Calculator.grams(0.0, Vehicle()))
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertFailsWith<IllegalArgumentException> { Co2Calculator.grams(value, Vehicle()) }
        }
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { factor ->
            assertFailsWith<IllegalArgumentException> { Vehicle(emissionFactorGPerKm = factor) }
        }
    }
    @Test fun `demo has five stops fixed final and a depot return`() = runBlocking {
        val provider = DemoProvider()
        val route = PlanRoute(provider, provider)(DemoProvider.request, isDemo = true)
        assertEquals(listOf(0, 2, 4, 1, 3, 5, 6, 0), route.optimizedOrder)
        assertEquals(20.0, route.baselineCost.distanceKm)
        assertEquals(12.0, route.totalDistanceKm)
        assertEquals(24.0, route.totalDurationMinutes)
        assertEquals(2160.0, route.totalCo2Grams)
        assertEquals(1440.0, route.co2SavedGrams)
        assertEquals(16.0, route.timeSavedMinutes)
        assertEquals(5, route.stopLocationIds.size)
        assertEquals(route.sourceLocationId, route.orderedLocations.last().id)
        println("DEMO: ${route.orderedLocations.joinToString(" → ") { it.name }}; 20 → 12 km; 3.60 → 2.16 kg CO2; saved 1.44 kg")
    }
    @Test fun `last entered dropoff is pinned when separate final is omitted`() = runBlocking {
        val provider = DemoProvider()
        val route = PlanRoute(provider, provider)(DemoProvider.request.copy(finalDestination = null))
        assertEquals(5, route.optimizedOrder[route.optimizedOrder.lastIndex - 1])
        assertEquals(LocationType.DROPOFF, route.locations.last().type)
        assertEquals(0, route.optimizedOrder.last())
        assertTrue(route.totalDistanceKm <= route.baselineCost.distanceKm)
    }
    @Test fun `directed 2-opt never substitutes symmetric edge deltas`() {
        val random = Random(73519)
        repeat(80) {
            val n = random.nextInt(4, 12)
            val matrix = RoadMatrix(List(n) { i -> List(n) { j ->
                if (i == j) RoadCost.ZERO else RoadCost(random.nextInt(1, 9000).toDouble(), random.nextInt(1, 1000).toDouble())
            } })
            val order = RouteOptimizer().optimize(matrix)
            assertEquals(0, order.first()); assertEquals(0, order.last())
            assertEquals(n - 1, order[order.lastIndex - 1])
            assertEquals((0 until n).toSet(), order.dropLast(1).toSet())
            assertEquals(n + 1, order.size)
            assertFalse(matrix.cost((0 until n).toList() + 0).isBetterThan(matrix.cost(order)))
        }
    }
    @Test fun `duration breaks equal-distance ties`() {
        val matrix = RoadMatrix(List(4) { i -> List(4) { j ->
            if (i == j) RoadCost.ZERO else RoadCost(1000.0, if ((i to j) in setOf(0 to 2, 2 to 1, 1 to 3, 3 to 0)) 60.0 else 600.0)
        } })
        assertEquals(listOf(0, 2, 1, 3, 0), RouteOptimizer().optimize(matrix))
    }
    @Test fun `two dropoffs and no separate final remain a complete trip`() = runBlocking {
        val provider = DemoProvider()
        val route = PlanRoute(provider, provider)(RouteRequest(DemoProvider.addresses[0], DemoProvider.addresses.subList(1, 3)))
        assertEquals(listOf(0, 1, 2, 0), route.optimizedOrder)
    }
    @Test fun `matrix malformed values and input errors fail safely`() {
        assertFailsWith<IllegalArgumentException> { RoadMatrix(listOf(listOf(RoadCost.ZERO))) }
        assertFailsWith<IllegalArgumentException> { RoadCost(Double.NaN, 1.0) }
        assertFailsWith<IllegalArgumentException> { RoadCost(100.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Coordinate.parse("91,4") }
        assertFailsWith<IllegalArgumentException> { Coordinate.parse("NaN,4") }
        assertFailsWith<IllegalArgumentException> { RouteRequest("Depot", listOf("same", "SAME")).validate() }
        assertFailsWith<IllegalArgumentException> { RouteRequest("Depot", listOf("only one")).validate() }
        assertFailsWith<IllegalArgumentException> { RouteRequest("Depot\n", listOf("Stop A", "Stop B")).validate() }
    }
    @Test fun `actual road geometry costs can trigger baseline fallback`() = runBlocking {
        val demo = DemoProvider()
        val routing = object : RoutingService {
            override suspend fun matrix(locations: List<Location>) = demo.matrix(locations)
            override suspend fun route(orderedLocations: List<Location>): RoadPath {
                val path = demo.route(orderedLocations)
                return if (orderedLocations[1].id == "2") path.copy(legs = path.legs.map { RoadCost(it.distanceMeters * 3, it.durationSeconds * 3) }) else path
            }
        }
        val route = PlanRoute(demo, routing)(DemoProvider.request)
        assertEquals(route.baselineOrder, route.optimizedOrder)
        assertEquals(0.0, route.co2SavedGrams)
    }
    @Test(timeout = 5000) fun `50 dropoffs are optimized within responsive background budget`() {
        val random = Random(31)
        val matrix = RoadMatrix(List(52) { i -> List(52) { j ->
            if (i == j) RoadCost.ZERO else RoadCost(random.nextInt(1, 50000).toDouble(), random.nextInt(1, 3600).toDouble())
        } })
        val start = System.nanoTime()
        val order = RouteOptimizer().optimize(matrix)
        assertEquals(53, order.size)
        assertEquals(51, order[order.lastIndex - 1])
        assertEquals(52, order.dropLast(1).distinct().size)
        println("50 drop-offs plus separate final: ${(System.nanoTime() - start) / 1_000_000} ms")
    }
    @Test fun `optimization is cooperatively cancellable`() {
        val matrix = RoadMatrix(List(5) { i -> List(5) { j -> if (i == j) RoadCost.ZERO else RoadCost(1.0, 1.0) } })
        assertFailsWith<CancellationException> { RouteOptimizer().optimize(matrix) { throw CancellationException() } }
    }
}

