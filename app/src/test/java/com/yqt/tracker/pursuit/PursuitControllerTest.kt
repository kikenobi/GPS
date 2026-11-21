package com.yqt.tracker.pursuit

import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.routing.GeoPoint
import com.yqt.tracker.routing.RouteRequest
import com.yqt.tracker.routing.RouteResult
import com.yqt.tracker.routing.RoutingProvider
import com.yqt.tracker.tracking.TrackerPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PursuitControllerTest {

    private class FakeRoutingProvider(
        var result: RouteResult = RouteResult.Success(
            RouteInfo(
                geometry = listOf(Pair(40.0, -3.0), Pair(40.001, -3.0)),
                distanceMeters = 100.0,
                durationSeconds = 60.0,
                steps = emptyList(),
            )
        )
    ) : RoutingProvider {
        var calls = 0
        override suspend fun calculateRoute(request: RouteRequest): RouteResult {
            calls++
            return result
        }
    }

    private fun trackerPos(
        id: String = "dev1",
        lat: Double,
        lng: Double,
        t: Long,
        speed: Double? = 1.0,
    ) = TrackerPosition(
        trackerId = id,
        latitude = lat,
        longitude = lng,
        speed = speed,
        heading = null,
        timestampMillis = t,
        receivedAtMillis = t,
    )

    private fun userPos(lat: Double, lng: Double, t: Long = 0L) =
        UserPosition(latitude = lat, longitude = lng, timestampMillis = t)

    @Test
    fun `llegar a 500m 100m 10m y 0m no finaliza la persecucion`() = runTest {
        val clock = arrayOf(1_000L)
        val controller = PursuitController(
            routingProvider = FakeRoutingProvider(),
            config = PursuitConfig(nearbyDistanceMeters = 50.0, signalLossTimeoutMillis = 30_000L),
            clock = { clock[0] },
        )
        val userLat = 40.0
        val userLng = -3.0
        controller.startPursuit("dev1")
        controller.updateUserPosition(userPos(userLat, userLng, clock[0]))

        val distances = listOf(
            0.0045 to 500.0,   // ~500m
            0.0009 to 100.0,   // ~100m
            0.00009 to 10.0,   // ~10m
            0.0 to 0.0,        // 0m
        )

        for ((deltaLat, approx) in distances) {
            clock[0] += 1_000L
            controller.onTrackerPosition(trackerPos(lat = userLat + deltaLat, lng = userLng, t = clock[0]))
            val info = controller.info.value
            assertNotEquals(
                "La persecución no debe finalizar a ~${approx}m",
                PursuitState.STOPPED,
                info.state,
            )
            assertTrue(
                "La persecución debe seguir activa a ~${approx}m",
                info.isActive,
            )
            assertNotNull("Debe conservar posición del tracker a ~${approx}m", info.lastKnownTrackerPosition)
        }

        // En 0m debe seguir activa (cercanía informativa, no terminal)
        assertEquals(PursuitState.TRACKER_NEARBY, controller.info.value.state)
        assertNotEquals(PursuitState.STOPPED, controller.info.value.state)
    }

    @Test
    fun `evento de llegada de OSRM o de futuro SDK no puede finalizar la persecucion`() = runTest {
        val clock = arrayOf(5_000L)
        val controller = PursuitController(
            routingProvider = FakeRoutingProvider(),
            config = PursuitConfig(nearbyDistanceMeters = 60.0),
            clock = { clock[0] },
        )
        controller.startPursuit("dev1")
        controller.updateUserPosition(userPos(40.0, -3.0, clock[0]))
        controller.onTrackerPosition(trackerPos(lat = 40.0, lng = -3.0, t = clock[0])) // 0m

        controller.onExternalArrivalEvent("osrm")
        var info = controller.info.value
        assertNotEquals(PursuitState.STOPPED, info.state)
        assertTrue(info.isActive)
        assertNotNull(info.lastExternalArrivalEventAt)

        clock[0] += 1_000L
        controller.onExternalArrivalEvent("maplibre-navigation-sdk")
        info = controller.info.value
        assertNotEquals(PursuitState.STOPPED, info.state)
        assertTrue(info.isActive)
        assertNotNull(info.lastKnownTrackerPosition)

        // Múltiples eventos no deben detenerla
        repeat(5) {
            clock[0] += 500L
            controller.onExternalArrivalEvent("sdk")
        }
        assertEquals(true, controller.info.value.isActive)
        assertNotEquals(PursuitState.STOPPED, controller.info.value.state)
    }

    @Test
    fun `si el tracker se mueve despues de haberlo alcanzado la persecucion continua`() = runTest {
        val clock = arrayOf(10_000L)
        val controller = PursuitController(
            routingProvider = FakeRoutingProvider(),
            config = PursuitConfig(nearbyDistanceMeters = 50.0),
            clock = { clock[0] },
        )
        controller.startPursuit("dev1")
        controller.updateUserPosition(userPos(40.0, -3.0, clock[0]))

        // Alcanzado (0m)
        controller.onTrackerPosition(trackerPos(lat = 40.0, lng = -3.0, t = clock[0], speed = 0.0))
        assertTrue(controller.info.value.isActive)
        assertNotEquals(PursuitState.STOPPED, controller.info.value.state)

        // Llega/simula evento de llegada externo en 0m
        controller.onExternalArrivalEvent("osrm")
        assertNotEquals(PursuitState.STOPPED, controller.info.value.state)

        // El tracker se aleja (~500m)
        clock[0] += 2_000L
        controller.onTrackerPosition(trackerPos(lat = 40.0045, lng = -3.0, t = clock[0], speed = 5.0))
        val info = controller.info.value
        assertTrue("Debe seguir activa tras alejarse", info.isActive)
        assertNotEquals(PursuitState.STOPPED, info.state)
        assertEquals(40.0045, info.lastKnownTrackerPosition?.latitude ?: 0.0, 0.000001)
        assertNotNull(info.straightDistanceMeters)
        assertTrue((info.straightDistanceMeters ?: 0.0) > 100.0)
    }

    @Test
    fun `si pierde senal conserva la ultima posicion y recupera automaticamente`() = runTest {
        var now = 20_000L
        val controller = PursuitController(
            routingProvider = FakeRoutingProvider(),
            config = PursuitConfig(signalLossTimeoutMillis = 5_000L, nearbyDistanceMeters = 50.0),
            clock = { now },
        )
        controller.startPursuit("dev1")
        controller.updateUserPosition(userPos(40.0, -3.0, now))

        controller.onTrackerPosition(trackerPos(lat = 40.002, lng = -3.0, t = now))
        val lastKnown = controller.info.value.lastKnownTrackerPosition
        assertNotNull(lastKnown)
        assertTrue(controller.info.value.isActive)

        // Pasa el tiempo sin nuevas posiciones -> pérdida de señal
        now += 6_000L
        controller.checkTimeouts(now)
        val lost = controller.info.value
        assertEquals(PursuitState.TRACKER_SIGNAL_LOST, lost.state)
        assertNotEquals(PursuitState.STOPPED, lost.state)
        assertNotNull("Debe conservar última posición", lost.lastKnownTrackerPosition)
        assertEquals(lastKnown?.latitude, lost.lastKnownTrackerPosition?.latitude)
        assertEquals(lastKnown?.longitude, lost.lastKnownTrackerPosition?.longitude)

        // Vuelve a transmitir -> recuperación automática (sin acción del usuario)
        now += 1_000L
        controller.onTrackerPosition(trackerPos(lat = 40.0025, lng = -3.0, t = now))
        val recovered = controller.info.value
        assertNotEquals(PursuitState.STOPPED, recovered.state)
        assertTrue(recovered.isActive)
        assertNotEquals(PursuitState.TRACKER_SIGNAL_LOST, recovered.state)
        assertEquals(40.0025, recovered.lastKnownTrackerPosition?.latitude ?: 0.0, 0.000001)
    }

    @Test
    fun `solo una accion explicita del usuario puede detener la persecucion`() = runTest {
        var now = 30_000L
        val controller = PursuitController(
            routingProvider = FakeRoutingProvider(),
            config = PursuitConfig(signalLossTimeoutMillis = 5_000L, nearbyDistanceMeters = 50.0),
            clock = { now },
        )
        controller.startPursuit("dev1")
        controller.updateUserPosition(userPos(40.0, -3.0, now))

        fun assertNotStopped(msg: String) {
            assertNotEquals(msg, PursuitState.STOPPED, controller.info.value.state)
        }

        // Actualizaciones normales
        controller.onTrackerPosition(trackerPos(lat = 40.0045, lng = -3.0, t = now)) // ~500m
        assertNotStopped("tras actualización a 500m")
        now += 1000
        controller.onTrackerPosition(trackerPos(lat = 40.0, lng = -3.0, t = now)) // 0m
        assertNotStopped("tras llegar a 0m")

        // Eventos automáticos/externos (llegada) no detienen
        controller.onExternalArrivalEvent("osrm")
        controller.onExternalArrivalEvent("sdk")
        assertNotStopped("tras eventos de llegada externos")

        // Pérdida y recuperación automática no detienen
        now += 6000
        controller.checkTimeouts(now)
        assertEquals(PursuitState.TRACKER_SIGNAL_LOST, controller.info.value.state)
        assertNotStopped("en pérdida de señal")
        now += 1000
        controller.onTrackerPosition(trackerPos(lat = 40.001, lng = -3.0, t = now))
        assertNotStopped("tras recuperación automática")

        // Más movimiento tras haber estado en 0m
        controller.onTrackerPosition(trackerPos(lat = 40.003, lng = -3.0, t = now + 500))
        assertNotStopped("tras moverse de nuevo")

        // Solo stop explícito
        controller.stopPursuit()
        assertEquals(PursuitState.STOPPED, controller.info.value.state)
        assertTrue(controller.info.value.isStoppedByUser)
        assertEquals(false, controller.info.value.isActive)
    }

    @Test
    fun `evento de llegada sin persecucion activa no altera estado detenido o inactivo`() {
        val controller = PursuitController(routingProvider = FakeRoutingProvider())
        assertEquals(PursuitState.IDLE, controller.info.value.state)
        controller.onExternalArrivalEvent("osrm")
        assertEquals(PursuitState.IDLE, controller.info.value.state)

        controller.startPursuit("dev1")
        controller.stopPursuit()
        assertEquals(PursuitState.STOPPED, controller.info.value.state)
        controller.onExternalArrivalEvent("sdk")
        assertEquals(PursuitState.STOPPED, controller.info.value.state)
    }
}
