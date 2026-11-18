package com.yqt.tracker.util

import android.content.Context

object Prefs {
    private const val NAME = "yqt_prefs"
    private const val KEY_REGION = "region"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"

    fun save(context: Context, region: String, account: String, password: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_REGION, region)
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun load(context: Context): Triple<String, String, String>? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val region = p.getString(KEY_REGION, null) ?: return null
        val account = p.getString(KEY_ACCOUNT, null) ?: return null
        val password = p.getString(KEY_PASSWORD, null) ?: return null
        return Triple(region, account, password)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
