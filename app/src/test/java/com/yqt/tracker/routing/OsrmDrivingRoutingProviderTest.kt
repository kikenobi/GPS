package com.yqt.tracker.routing

import com.yqt.tracker.data.RouteInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmDrivingRoutingProviderTest {

    @Test
    fun `mapea perfiles a OSRM provisional y soporta los tres sin depender de red`() = runTest {
        val calls = mutableListOf<String>()
        val fakeRoute = RouteInfo(
            geometry = listOf(Pair(40.0, -3.0), Pair(40.1, -3.1)),
            distanceMeters = 1000.0,
            durationSeconds = 600.0,
            steps = emptyList(),
        )
        val provider = OsrmRoutingProvider { _, _, _, _, profile ->
            calls.add(profile)
            fakeRoute
        }
        val req = RouteRequest(GeoPoint(40.0, -3.0), GeoPoint(40.1, -3.1), TravelProfile.CAR)

        val car = provider.calculateRoute(req.copy(profile = TravelProfile.CAR))
        val moto = provider.calculateRoute(req.copy(profile = TravelProfile.MOTORCYCLE))
        val walk = provider.calculateRoute(req.copy(profile = TravelProfile.WALKING))

        assertTrue(car is RouteResult.Success)
        assertTrue(moto is RouteResult.Success)
        assertTrue(walk is RouteResult.Success)
        assertEquals(listOf("driving", "driving", "walking"), calls)
    }

    @Test
    fun `arquitectura expone los tres perfiles de desplazamiento`() {
        val profiles = TravelProfile.entries.map { it.name }.toSet()
        assertTrue(profiles.contains("CAR"))
        assertTrue(profiles.contains("MOTORCYCLE"))
        assertTrue(profiles.contains("WALKING"))
        assertEquals(3, profiles.size)
    }
}
