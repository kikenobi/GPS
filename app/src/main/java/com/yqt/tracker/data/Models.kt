package com.yqt.tracker.data

data class WatchInfo(
    val did: String,
    val didId: String,
    val name: String,
    val model: String,
    val config: String = "",
)

data class WatchPosition(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val direction: Double? = null,
    val battery: Int? = null,
    val address: String? = null,
    val positionDate: String? = null,
    val pollStatus: Int? = null,
    val pollMessage: String? = null,
    val locationType: String? = null,
)

data class WatchState(
    val info: WatchInfo,
    val position: WatchPosition? = null,
    val error: String? = null,
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: Pair<Double, Double>? = null, // lat,lng of maneuver
)

data class RouteInfo(
    val geometry: List<Pair<Double, Double>>, // lat,lng points
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
)
