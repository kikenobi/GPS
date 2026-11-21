package com.yqt.tracker.pursuit

enum class PursuitState {
    IDLE,
    WAITING_FOR_TRACKER,
    CALCULATING_ROUTE,
    PURSUING,
    TRACKER_NEARBY,
    TRACKER_STATIONARY,
    TRACKER_SIGNAL_LOST,
    RECOVERING_CONNECTION,
    STOPPED,
}
