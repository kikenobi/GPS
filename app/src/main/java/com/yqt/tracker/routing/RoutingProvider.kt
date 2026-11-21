package com.yqt.tracker.routing

/**
 * Abstracción del motor de rutas. El controlador de persecución depende de esta interfaz
 * y no de un proveedor/perfil concreto (OSRM, Valhalla, etc.).
 */
interface RoutingProvider {
    suspend fun calculateRoute(request: RouteRequest): RouteResult
}
