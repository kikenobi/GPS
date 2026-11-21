package com.yqt.tracker.pursuit

import com.yqt.tracker.routing.GeoPoint
import com.yqt.tracker.routing.RouteRequest
import com.yqt.tracker.routing.RouteResult
import com.yqt.tracker.routing.RoutingProvider
import com.yqt.tracker.routing.TravelProfile
import com.yqt.tracker.tracking.TrackerPosition
import com.yqt.tracker.tracking.TrackerPositionProvider
import com.yqt.tracker.util.GeoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controlador de persecución independiente del motor/SDK de navegación.
 *
 * Invariantes:
 * - La persecución NO finaliza por proximidad, distancia 0, llegada del SDK/motor ni eventos automáticos.
 * - Solo [stopPursuit] (acción explícita del usuario) puede llevar al estado [PursuitState.STOPPED].
 * - La actualización de coordenadas del tracker está separada del recálculo de rutas.
 */
class PursuitController(
    private val routingProvider: RoutingProvider,
    private val config: PursuitConfig = PursuitConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _info = MutableStateFlow(PursuitInfo())
    val info: StateFlow<PursuitInfo> = _info.asStateFlow()

    private var controllerScope: CoroutineScope? = null
    private var trackingJob: Job? = null
    private var timeoutJob: Job? = null
    private var routeJob: Job? = null

    private var routeTargetLat: Double? = null
    private var routeTargetLng: Double? = null
    private var lastRouteCalcAt: Long = 0L
    private var lastMovementAt: Long = 0L

    /**
     * Inicia una persecución. Solo el usuario puede finalizarla mediante [stopPursuit].
     * Si se proporciona [positionProvider] y [scope], se observa el tracker y se ejecutan
     * comprobaciones periódicas/recálculos asíncronos.
     */
    fun startPursuit(
        trackerId: String,
        profile: TravelProfile = TravelProfile.CAR,
        scope: CoroutineScope? = null,
        positionProvider: TrackerPositionProvider? = null,
        initialUserPosition: UserPosition? = null,
    ) {
        stopInternalJobs(clearScope = true)
        val now = clock()
        lastMovementAt = now
        routeTargetLat = null
        routeTargetLng = null
        lastRouteCalcAt = 0L
        controllerScope = scope
        _info.value = PursuitInfo(
            trackerId = trackerId,
            profile = profile,
            state = PursuitState.WAITING_FOR_TRACKER,
            userPosition = initialUserPosition,
        )

        if (scope != null && positionProvider != null) {
            trackingJob = scope.launch {
                positionProvider.observePosition(trackerId).collect { pos ->
                    onTrackerPosition(pos)
                }
            }
            timeoutJob = scope.launch {
                while (isActive) {
                    delay(1_000L)
                    checkTimeouts(clock())
                }
            }
        } else if (scope != null) {
            timeoutJob = scope.launch {
                while (isActive) {
                    delay(1_000L)
                    checkTimeouts(clock())
                }
            }
        }
    }

    /** Única acción que finaliza la persecución. */
    fun stopPursuit() {
        stopInternalJobs(clearScope = true)
        val current = _info.value
        _info.value = current.copy(
            state = PursuitState.STOPPED,
            isRouteCalculating = false,
        )
    }

    private fun stopInternalJobs(clearScope: Boolean) {
        trackingJob?.cancel()
        trackingJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        routeJob?.cancel()
        routeJob = null
        if (clearScope) controllerScope = null
    }

    fun updateUserPosition(position: UserPosition) {
        val current = _info.value
        if (current.state == PursuitState.STOPPED || current.state == PursuitState.IDLE) {
            _info.value = current.copy(userPosition = position)
            return
        }
        val straight = straightDistance(position, current.lastKnownTrackerPosition)
        val nextState = deriveActiveState(
            base = current.state,
            hasTracker = current.lastKnownTrackerPosition != null,
            straightDistance = straight,
            trackerPosition = current.lastKnownTrackerPosition,
            signalLost = current.state == PursuitState.TRACKER_SIGNAL_LOST,
            now = clock(),
            isRouteCalculating = current.isRouteCalculating,
            hasRoute = current.route != null,
        )
        _info.value = current.copy(
            userPosition = position,
            straightDistanceMeters = straight,
            state = nextState,
        )
        maybeAutoRecalc(force = false)
    }

    fun updateProfile(profile: TravelProfile) {
        val current = _info.value
        if (current.state == PursuitState.STOPPED || current.state == PursuitState.IDLE) {
            _info.value = current.copy(profile = profile)
            return
        }
        _info.value = current.copy(profile = profile)
        routeTargetLat = null
        routeTargetLng = null
        maybeAutoRecalc(force = true)
    }

    /**
     * Evento de "llegada" emitido por un motor/SDK externo (OSRM UI, Navigation SDK futuro, etc.).
     * NO debe finalizar la persecución ni alterar su continuidad.
     */
    fun onExternalArrivalEvent(source: String? = null) {
        val current = _info.value
        if (current.state == PursuitState.STOPPED || current.state == PursuitState.IDLE) return
        val now = clock()
        val straight = straightDistance(current.userPosition, current.lastKnownTrackerPosition)
        val state = deriveActiveState(
            base = if (current.state == PursuitState.WAITING_FOR_TRACKER) PursuitState.PURSUING else current.state,
            hasTracker = current.lastKnownTrackerPosition != null,
            straightDistance = straight,
            trackerPosition = current.lastKnownTrackerPosition,
            signalLost = false,
            now = now,
            isRouteCalculating = current.isRouteCalculating,
            hasRoute = current.route != null,
        )
        _info.value = current.copy(
            lastExternalArrivalEventAt = now,
            straightDistanceMeters = straight ?: current.straightDistanceMeters,
            state = state,
        )
        // Importante: no detener, no cancelar jobs, no limpiar destino.
    }

    fun onTrackerPosition(position: TrackerPosition) {
        val current = _info.value
        if (current.state == PursuitState.STOPPED || current.state == PursuitState.IDLE) return
        if (current.trackerId != null && current.trackerId != position.trackerId) return

        val now = clock()
        val wasLost = current.state == PursuitState.TRACKER_SIGNAL_LOST || current.state == PursuitState.RECOVERING_CONNECTION
        val prev = current.lastKnownTrackerPosition

        val moved = if (prev != null) {
            GeoUtils.haversineMeters(prev.latitude, prev.longitude, position.latitude, position.longitude)
        } else {
            Double.MAX_VALUE
        }
        if (prev == null || moved > 1.0) {
            lastMovementAt = now
        }

        val straight = straightDistance(current.userPosition, position)
        val base = when {
            wasLost -> PursuitState.PURSUING
            current.state == PursuitState.WAITING_FOR_TRACKER -> PursuitState.PURSUING
            else -> current.state
        }
        val nextState = deriveActiveState(
            base = base,
            hasTracker = true,
            straightDistance = straight,
            trackerPosition = position,
            signalLost = false,
            now = now,
            isRouteCalculating = current.isRouteCalculating,
            hasRoute = current.route != null,
        )

        _info.value = current.copy(
            state = nextState,
            trackerPosition = position,
            lastKnownTrackerPosition = position,
            straightDistanceMeters = straight,
            lastUpdateTimestamp = position.receivedAtMillis,
        )

        // Recuperación automática y reevaluación de ruta (posición actualizada siempre; ruta solo si procede).
        maybeAutoRecalc(force = wasLost)
    }

    fun checkTimeouts(now: Long = clock()) {
        val current = _info.value
        if (!current.isActive) return
        val last = current.lastUpdateTimestamp ?: return
        if (now - last > config.signalLossTimeoutMillis) {
            if (current.state != PursuitState.TRACKER_SIGNAL_LOST) {
                _info.value = current.copy(
                    state = PursuitState.TRACKER_SIGNAL_LOST,
                    // conservar última posición conocida
                    trackerPosition = current.lastKnownTrackerPosition ?: current.trackerPosition,
                )
            }
        }
    }

    /** Permite inyectar resultado de ruta (tests/integración) sin perder continuidad de la persecución. */
    fun onRouteResult(target: GeoPoint, result: RouteResult) {
        val current = _info.value
        if (current.state == PursuitState.STOPPED || current.state == PursuitState.IDLE) {
            _info.value = current.copy(isRouteCalculating = false)
            return
        }
        when (result) {
            is RouteResult.Success -> {
                routeTargetLat = target.latitude
                routeTargetLng = target.longitude
                val straight = straightDistance(current.userPosition, current.lastKnownTrackerPosition)
                val nextState = deriveActiveState(
                    base = PursuitState.PURSUING,
                    hasTracker = current.lastKnownTrackerPosition != null,
                    straightDistance = straight,
                    trackerPosition = current.lastKnownTrackerPosition,
                    signalLost = current.state == PursuitState.TRACKER_SIGNAL_LOST,
                    now = clock(),
                    isRouteCalculating = false,
                    hasRoute = true,
                )
                _info.value = current.copy(
                    route = result.route,
                    routeDistanceMeters = result.route.distanceMeters,
                    estimatedTimeSeconds = result.route.durationSeconds.toLong(),
                    isRouteCalculating = false,
                    straightDistanceMeters = straight ?: current.straightDistanceMeters,
                    state = nextState,
                )
            }
            is RouteResult.Failure, RouteResult.UnsupportedProfile -> {
                val straight = straightDistance(current.userPosition, current.lastKnownTrackerPosition)
                val nextState = deriveActiveState(
                    base = PursuitState.PURSUING,
                    hasTracker = current.lastKnownTrackerPosition != null,
                    straightDistance = straight,
                    trackerPosition = current.lastKnownTrackerPosition,
                    signalLost = current.state == PursuitState.TRACKER_SIGNAL_LOST,
                    now = clock(),
                    isRouteCalculating = false,
                    hasRoute = current.route != null,
                )
                _info.value = current.copy(
                    isRouteCalculating = false,
                    straightDistanceMeters = straight ?: current.straightDistanceMeters,
                    state = nextState,
                )
                // conservar última ruta válida si existía
            }
        }
    }

    private fun maybeAutoRecalc(force: Boolean) {
        val scope = controllerScope ?: return
        val current = _info.value
        if (!current.isActive) return
        if (current.state == PursuitState.TRACKER_SIGNAL_LOST) return
        val tracker = current.lastKnownTrackerPosition ?: return
        val user = current.userPosition ?: return
        val now = clock()
        if (!force && !shouldRecalculateRoute(current, tracker, user, now)) return
        if (!force && (now - lastRouteCalcAt) < config.minRouteRecalcIntervalMillis) return
        if (current.isRouteCalculating) return

        val target = GeoPoint(tracker.latitude, tracker.longitude)
        val origin = GeoPoint(user.latitude, user.longitude)
        val profile = current.profile

        _info.value = _info.value.copy(
            isRouteCalculating = true,
            state = if (current.route == null) PursuitState.CALCULATING_ROUTE else current.state,
        )
        lastRouteCalcAt = now
        routeJob?.cancel()
        routeJob = scope.launch {
            val result = try {
                routingProvider.calculateRoute(RouteRequest(origin, target, profile))
            } catch (e: Exception) {
                RouteResult.Failure(e.message, e)
            }
            onRouteResult(target, result)
        }
    }

    fun requestRouteRecalc(force: Boolean = true) {
        maybeAutoRecalc(force = force)
    }

    private fun shouldRecalculateRoute(
        current: PursuitInfo,
        tracker: TrackerPosition,
        user: UserPosition,
        now: Long,
    ): Boolean {
        if (current.route == null) return true
        if ((now - lastRouteCalcAt) < config.minRouteRecalcIntervalMillis) return false

        val tLat = routeTargetLat
        val tLng = routeTargetLng
        if (tLat == null || tLng == null) return true

        val moved = GeoUtils.haversineMeters(tLat, tLng, tracker.latitude, tracker.longitude)
        if (moved >= config.routeRecalcTrackerMoveMeters) return true

        val route = current.route
        if (route.geometry.size >= 2) {
            val deviation = GeoUtils.distanceToPolylineMeters(user.latitude, user.longitude, route.geometry)
            if (deviation >= config.routeRecalcUserDeviationMeters) return true
        }
        return false
    }

    private fun deriveActiveState(
        base: PursuitState,
        hasTracker: Boolean,
        straightDistance: Double?,
        trackerPosition: TrackerPosition?,
        signalLost: Boolean,
        now: Long,
        isRouteCalculating: Boolean,
        hasRoute: Boolean,
    ): PursuitState {
        if (base == PursuitState.STOPPED) return PursuitState.STOPPED
        if (base == PursuitState.IDLE) return PursuitState.IDLE
        if (signalLost) return PursuitState.TRACKER_SIGNAL_LOST
        if (!hasTracker || trackerPosition == null) return PursuitState.WAITING_FOR_TRACKER

        val effectiveDistance = straightDistance
        if (effectiveDistance != null && effectiveDistance <= config.nearbyDistanceMeters) {
            // Cercanía (incluye 0 m) es informativa y NO terminal.
            return PursuitState.TRACKER_NEARBY
        }

        if (isStationary(now, trackerPosition)) {
            return PursuitState.TRACKER_STATIONARY
        }

        if (isRouteCalculating && !hasRoute) return PursuitState.CALCULATING_ROUTE

        return PursuitState.PURSUING
    }

    private fun isStationary(now: Long, trackerPosition: TrackerPosition): Boolean {
        val ref = lastMovementAt
        if (ref <= 0L) return false
        if (now - ref < config.stationaryMinDurationMillis) return false
        val speed = trackerPosition.speed
        return speed == null || speed <= config.stationarySpeedThreshold
    }

    private fun straightDistance(user: UserPosition?, tracker: TrackerPosition?): Double? {
        if (user == null || tracker == null) return null
        return GeoUtils.haversineMeters(user.latitude, user.longitude, tracker.latitude, tracker.longitude)
    }
}
