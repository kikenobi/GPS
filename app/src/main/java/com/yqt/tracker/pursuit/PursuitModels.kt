package com.yqt.tracker.pursuit

import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.routing.TravelProfile
import com.yqt.tracker.tracking.TrackerPosition

data class UserPosition(
    val latitude: Double,
    val longitude: Double,
    val speed: Float? = null,
    val heading: Float? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class PursuitConfig(
    val nearbyDistanceMeters: Double = 50.0,
    val stationarySpeedThreshold: Double = 0.3, // unidades de speed reportada; heurístico
    val stationaryMinDurationMillis: Long = 30_000L,
    val signalLossTimeoutMillis: Long = 30_000L,
    /** Recalcular si el tracker se alejó al menos esta distancia desde el destino usado en la última ruta. */
    val routeRecalcTrackerMoveMeters: Double = 30.0,
    /** Recalcular si el usuario se desvió de la ruta más de esta distancia. */
    val routeRecalcUserDeviationMeters: Double = 60.0,
    /** Intervalo mínimo entre recálculos (control de coste). */
    val minRouteRecalcIntervalMillis: Long = 5_000L,
)

data class PursuitInfo(
    val trackerId: String? = null,
    val profile: TravelProfile = TravelProfile.CAR,
    val state: PursuitState = PursuitState.IDLE,
    val trackerPosition: TrackerPosition? = null,
    val lastKnownTrackerPosition: TrackerPosition? = null,
    val userPosition: UserPosition? = null,
    val straightDistanceMeters: Double? = null,
    val routeDistanceMeters: Double? = null,
    val estimatedTimeSeconds: Long? = null,
    val lastUpdateTimestamp: Long? = null,
    val route: RouteInfo? = null,
    val isRouteCalculating: Boolean = false,
    val lastExternalArrivalEventAt: Long? = null,
) {
    /** La persecución solo puede considerarse detenida por acción explícita del usuario. */
    val isStoppedByUser: Boolean get() = state == PursuitState.STOPPED
    val isActive: Boolean get() = state != PursuitState.IDLE && state != PursuitState.STOPPED
}
