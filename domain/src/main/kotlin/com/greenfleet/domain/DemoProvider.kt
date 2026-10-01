package com.greenfleet.domain

/** Synthetic road-network fixture, NOT measured Amsterdam driving data. No network calls. */
class DemoProvider : GeocodingService, RoutingService {
    override suspend fun geocode(input: String, type: LocationType, id: String): Location {
        val index = addresses.indexOf(input)
        require(index >= 0) { "Demo mode supports the sample addresses only. Load the demo or switch to live routing." }
        return Location(id, names[index], input, coordinates[index], type)
    }
    private fun edge(a: Location, b: Location): RoadCost {
        val i = coordinates.indexOf(a.coordinate)
        val j = coordinates.indexOf(b.coordinate)
        require(i >= 0 && j >= 0)
        val km = kotlin.math.abs(positions[i] - positions[j]).toDouble()
        return RoadCost(km * 1000, km * 120)
    }
    override suspend fun matrix(locations: List<Location>) = RoadMatrix(locations.map { a -> locations.map { b -> edge(a, b) } })
    override suspend fun route(orderedLocations: List<Location>) = RoadPath(
        orderedLocations.map { it.coordinate }, orderedLocations.zipWithNext { a, b -> edge(a, b) })
    companion object {
        val addresses = listOf("De Ruijterkade 34, Amsterdam", "Museumstraat 1, Amsterdam",
            "Prinsengracht 263, Amsterdam", "Europaplein 24, Amsterdam", "Amstel 1, Amsterdam",
            "Linnaeusstraat 2, Amsterdam", "Oosterdokskade 143, Amsterdam")
        val names = listOf("Central depot", "Museum delivery", "Canal delivery", "South delivery", "Amstel delivery", "East delivery", "Final destination")
        private val positions = listOf(0, 3, 1, 4, 2, 5, 6)
        private val coordinates = listOf(Coordinate(52.3791, 4.9003), Coordinate(52.3599, 4.8852),
            Coordinate(52.3752, 4.8840), Coordinate(52.3415, 4.8880), Coordinate(52.3676, 4.9000),
            Coordinate(52.3626, 4.9226), Coordinate(52.3758, 4.9087))
        val request get() = RouteRequest(addresses[0], addresses.subList(1, 6), addresses[6])
    }
}
