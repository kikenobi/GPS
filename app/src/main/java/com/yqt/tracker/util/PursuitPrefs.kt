package com.yqt.tracker.util

import android.content.Context
import com.yqt.tracker.routing.TravelProfile

object PursuitPrefs {
    private const val NAME = "pursuit_prefs"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Intervalo de consulta del tracker fuera de persecución activa (ms). Opciones: 5,10,30,60,300 s */
    fun trackerPollIntervalMs(context: Context): Long =
        sp(context).getLong("tracker_poll_interval_ms", 10_000L)

    fun setTrackerPollIntervalMs(context: Context, valueMs: Long) {
        sp(context).edit().putLong("tracker_poll_interval_ms", valueMs).apply()
    }

    fun travelProfile(context: Context): TravelProfile {
        val name = sp(context).getString("travel_profile", TravelProfile.CAR.name) ?: TravelProfile.CAR.name
        return runCatching { TravelProfile.valueOf(name) }.getOrDefault(TravelProfile.CAR)
    }

    fun setTravelProfile(context: Context, profile: TravelProfile) {
        sp(context).edit().putString("travel_profile", profile.name).apply()
    }

    fun voiceEnabled(context: Context): Boolean = sp(context).getBoolean("voice_enabled", true)
    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("voice_enabled", enabled).apply()
    }

    fun proximityAlertsEnabled(context: Context): Boolean = sp(context).getBoolean("proximity_alerts", true)
    fun setProximityAlertsEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("proximity_alerts", enabled).apply()
    }

    fun alert500(context: Context): Boolean = sp(context).getBoolean("alert_500", true)
    fun alert100(context: Context): Boolean = sp(context).getBoolean("alert_100", true)
    fun alert50(context: Context): Boolean = sp(context).getBoolean("alert_50", true)
    fun alert10(context: Context): Boolean = sp(context).getBoolean("alert_10", true)

    fun setAlertThreshold(context: Context, meters: Int, enabled: Boolean) {
        val key = when (meters) {
            500 -> "alert_500"
            100 -> "alert_100"
            50 -> "alert_50"
            10 -> "alert_10"
            else -> return
        }
        sp(context).edit().putBoolean(key, enabled).apply()
    }
}
