package com.greenfleet.domain

/** Multi-start nearest neighbor + directed 2-opt; depot and final stop remain anchored.
 * Full candidate costs deliberately account for EVERY reversed edge on asymmetric roads.
 * The entered order is always a candidate, so the result cannot be worse under this objective.
 */
class RouteOptimizer {
    fun optimize(matrix: RoadMatrix, checkCancelled: () -> Unit = {}): List<Int> {
        val final = matrix.size - 1
        val movable = (1 until final).toList()
        var best = (0..final).toList() + 0
        var bestCost = matrix.cost(best)
        val starts = movable.sortedWith(compareBy({ matrix[0, it].distanceMeters }, { matrix[0, it].durationSeconds })).take(8)
        val seeds = mutableListOf(best)
        for (start in starts) {
            checkCancelled()
            val remaining = movable.toMutableSet().apply { remove(start) }
            val order = mutableListOf(0, start)
            while (remaining.isNotEmpty()) {
                val current = order.last()
                val next = remaining.minWith(compareBy({ matrix[current, it].distanceMeters }, { matrix[current, it].durationSeconds }, { it }))
                order += next
                remaining.remove(next)
            }
            seeds += order + listOf(final, 0)
        }
        for (seed in seeds.distinct()) {
            var order = seed
            var cost = matrix.cost(order)
            for (pass in 0 until 30) {
                checkCancelled()
                var improved = false
                for (i in 1 until order.size - 3) {
                    checkCancelled()
                    for (j in i + 1 until order.size - 2) {
                        val candidate = order.take(i) + order.subList(i, j + 1).reversed() + order.drop(j + 1)
                        val candidateCost = matrix.cost(candidate)
                        if (candidateCost.isBetterThan(cost)) {
                            order = candidate; cost = candidateCost; improved = true
                        }
                    }
                }
                if (!improved) break
            }
            if (cost.isBetterThan(bestCost)) { best = order; bestCost = cost }
        }
        return best
    }
}

