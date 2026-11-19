package com.yqt.tracker.consumption

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ConsumptionPrefs {
    private const val NAME = "consumption_prefs"

    fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun lastUpdateMs(context: Context): Long = sp(context).getLong("last_update_ms", 0L)
    fun setLastUpdateMs(context: Context, v: Long) = sp(context).edit().putLong("last_update_ms", v).apply()

    fun lastSyncStatus(context: Context): String = sp(context).getString("last_sync_status", "Solo datos locales") ?: "Solo datos locales"
    fun setLastSyncStatus(context: Context, v: String) = sp(context).edit().putString("last_sync_status", v).apply()

    fun cloudBackendUrl(context: Context): String = sp(context).getString("cloud_backend_url", "") ?: ""
    fun setCloudBackendUrl(context: Context, v: String) = sp(context).edit().putString("cloud_backend_url", v.trim()).apply()

    fun autoSyncEnabled(context: Context): Boolean = sp(context).getBoolean("auto_sync", false)
    fun setAutoSyncEnabled(context: Context, v: Boolean) = sp(context).edit().putBoolean("auto_sync", v).apply()

    fun billingPeriodStartDay(context: Context): Int = sp(context).getInt("billing_period_start_day", 1).coerceIn(1, 28)
    fun setBillingPeriodStartDay(context: Context, v: Int) = sp(context).edit().putInt("billing_period_start_day", v.coerceIn(1, 28)).apply()

    // Per-service config
    fun monthlyLimit(context: Context, serviceId: String, default: Int?): Int? {
        val v = sp(context).getInt("limit_$serviceId", Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) default else if (v <= 0) null else v
    }
    fun setMonthlyLimit(context: Context, serviceId: String, v: Int?) {
        sp(context).edit().putInt("limit_$serviceId", v ?: 0).apply()
    }

    fun warn80(context: Context, serviceId: String): Boolean = sp(context).getBoolean("warn80_$serviceId", true)
    fun warn90(context: Context, serviceId: String): Boolean = sp(context).getBoolean("warn90_$serviceId", true)
    fun warn95(context: Context, serviceId: String): Boolean = sp(context).getBoolean("warn95_$serviceId", true)
    fun setWarn(context: Context, serviceId: String, w80: Boolean, w90: Boolean, w95: Boolean) {
        sp(context).edit()
            .putBoolean("warn80_$serviceId", w80)
            .putBoolean("warn90_$serviceId", w90)
            .putBoolean("warn95_$serviceId", w95)
            .apply()
    }

    fun blockAt100(context: Context, serviceId: String): Boolean = sp(context).getBoolean("block100_$serviceId", false)
    fun setBlockAt100(context: Context, serviceId: String, v: Boolean) = sp(context).edit().putBoolean("block100_$serviceId", v).apply()

    fun manualException(context: Context, serviceId: String): Boolean = sp(context).getBoolean("except_$serviceId", false)
    fun setManualException(context: Context, serviceId: String, v: Boolean) = sp(context).edit().putBoolean("except_$serviceId", v).apply()

    // Reroute thresholds for PERSEGUIR (configurable)
    fun rerouteMinDistanceMeters(context: Context): Float = sp(context).getFloat("reroute_min_m", 40f)
    fun setRerouteMinDistanceMeters(context: Context, v: Float) = sp(context).edit().putFloat("reroute_min_m", v).apply()
    fun rerouteMinIntervalMs(context: Context): Long = sp(context).getLong("reroute_min_ms", 8000L)
    fun setRerouteMinIntervalMs(context: Context, v: Long) = sp(context).edit().putLong("reroute_min_ms", v).apply()

    fun currentPeriodKey(nowMs: Long = System.currentTimeMillis(), startDay: Int = 1): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val day = cal.get(Calendar.DAY_OF_MONTH)
        if (day < startDay) {
            cal.add(Calendar.MONTH, -1)
        }
        return SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
    }

    fun periodStartLabel(nowMs: Long = System.currentTimeMillis(), startDay: Int = 1): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val day = cal.get(Calendar.DAY_OF_MONTH)
        if (day < startDay) cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, startDay.coerceIn(1, 28))
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }
}