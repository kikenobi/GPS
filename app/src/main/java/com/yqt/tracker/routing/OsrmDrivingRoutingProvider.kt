package com.yqt.tracker.routing

import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Proveedor provisional basado en OSRM público, desacoplado de OSRM en la interfaz del controlador.
 * - Coche: driving
 * - Caminando: walking
 */
class OsrmRoutingProvider(
    private val fetcher: (Double, Double, Double, Double, String) -> RouteInfo? =
        { fromLat, fromLon, toLat, toLon, profile ->
            GeoUtils.fetchRoute(fromLat, fromLon, toLat, toLon, profile)
        },
) : RoutingProvider {

    fun osrmProfileFor(profile: TravelProfile): String = when (profile) {
        TravelProfile.CAR -> "driving"
        TravelProfile.WALKING -> "walking"
    }

    override suspend fun calculateRoute(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        val osrmProfile = osrmProfileFor(request.profile)
        try {
            val route = fetcher(
                request.from.latitude,
                request.from.longitude,
                request.to.latitude,
                request.to.longitude,
                osrmProfile,
            )
            if (route != null) RouteResult.Success(route) else RouteResult.Failure("No se pudo calcular la ruta")
        } catch (e: Exception) {
            RouteResult.Failure(e.message, e)
        }
    }
}

/** Alias de compatibilidad para referencias previas de Fase 2. */
@Deprecated("Usar OsrmRoutingProvider", ReplaceWith("OsrmRoutingProvider"))
typealias OsrmDrivingRoutingProvider = OsrmRoutingProvider

