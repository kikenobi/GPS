package com.yqt.tracker.routing

import com.yqt.tracker.data.RouteInfo

data class GeoPoint(val latitude: Double, val longitude: Double)

data class RouteRequest(
    val from: GeoPoint,
    val to: GeoPoint,
    val profile: TravelProfile,
)

sealed interface RouteResult {
    data class Success(val route: RouteInfo) : RouteResult
    data class Failure(val reason: String? = null, val cause: Throwable? = null) : RouteResult
    data object UnsupportedProfile : RouteResult
}
