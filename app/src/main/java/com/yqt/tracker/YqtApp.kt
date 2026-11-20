package com.yqt.tracker

import android.app.Application
import org.maplibre.android.MapLibre

class YqtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
