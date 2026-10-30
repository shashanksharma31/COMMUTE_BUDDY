package com.example.lib

// Picks the Recommended route by a combined time-and-cost score (lower score is better)
object Recommend {
    fun scoreRoute(route: Route, allRoutes: List<Route>): Double {
        val maxMinutes = allRoutes.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
        val maxCost = allRoutes.maxOfOrNull { it.costInr }?.coerceAtLeast(1) ?: 1

        val normalizedTime = route.minutes.toDouble() / maxMinutes.toDouble()
        val normalizedCost = if (maxCost == 0) 0.0 else route.costInr.toDouble() / maxCost.toDouble()
        val reliabilityBonus = (route.reliability.coerceIn(0, 10) / 10.0) * 0.08

        // Weighted 78% travel time + 14% INR cost, minus reliability bonus so fastest/most reliable wins
        return (normalizedTime * 0.78) + (normalizedCost * 0.14) - reliabilityBonus
    }

    fun recommendRoute(routes: List<Route>): Route? {
        if (routes.isEmpty()) return null
        return routes.minByOrNull { route -> scoreRoute(route, routes) }
    }
}
