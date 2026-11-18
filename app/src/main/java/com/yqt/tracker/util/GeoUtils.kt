package com.yqt.tracker.util

import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.data.RouteStep
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.*

object GeoUtils {
    private val http = OkHttpClient()

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    fun distanceToPolylineMeters(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Double {
        if (poly.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val d = distanceToSegmentMeters(lat, lon, poly[i].first, poly[i].second, poly[i + 1].first, poly[i + 1].second)
            if (d < best) best = d
        }
        return best
    }

    // approximate point-to-segment distance in meters using equirectangular projection
    private fun distanceToSegmentMeters(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val refLat = Math.toRadians((aLat + bLat + pLat) / 3.0)
        fun x(lat: Double, lon: Double) = Math.toRadians(lon) * cos(refLat) * 6371000.0
        fun y(lat: Double, lon: Double) = Math.toRadians(lat) * 6371000.0
        val px = x(pLat, pLon); val py = y(pLat, pLon)
        val ax = x(aLat, aLon); val ay = y(aLat, aLon)
        val bx = x(bLat, bLon); val by = y(bLat, bLon)
        val abx = bx - ax; val aby = by - ay
        val apx = px - ax; val apy = py - ay
        val ab2 = abx * abx + aby * aby
        val t = if (ab2 <= 0.0) 0.0 else ((apx * abx + apy * aby) / ab2).coerceIn(0.0, 1.0)
        val cx = ax + t * abx; val cy = ay + t * aby
        val dx = px - cx; val dy = py - cy
        return sqrt(dx * dx + dy * dy)
    }

    fun fetchRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteInfo? {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") return null
            val routes = json.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route = routes.getJSONObject(0)
            val coordsArr = route.getJSONObject("geometry").getJSONArray("coordinates")
            val geom = ArrayList<Pair<Double, Double>>(coordsArr.length())
            for (i in 0 until coordsArr.length()) {
                val c = coordsArr.getJSONArray(i)
                geom.add(Pair(c.getDouble(1), c.getDouble(0))) // lat,lng
            }
            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val sArr = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                    for (s in 0 until sArr.length()) {
                        val st = sArr.getJSONObject(s)
                        val man = st.optJSONObject("maneuver")
                        val locArr = man?.optJSONArray("location")
                        val mLoc = if (locArr != null && locArr.length() >= 2) Pair(locArr.getDouble(1), locArr.getDouble(0)) else null
                        val instr = formatInstruction(st)
                        steps.add(
                            RouteStep(
                                instruction = instr,
                                distanceMeters = st.optDouble("distance", 0.0),
                                durationSeconds = st.optDouble("duration", 0.0),
                                location = mLoc,
                            )
                        )
                    }
                }
            }
            return RouteInfo(
                geometry = geom,
                distanceMeters = route.optDouble("distance", 0.0),
                durationSeconds = route.optDouble("duration", 0.0),
                steps = steps,
            )
        }
    }

    private fun formatInstruction(step: JSONObject): String {
        val man = step.optJSONObject("maneuver")
        val type = man?.optString("type", "") ?: ""
        val modifier = man?.optString("modifier", "") ?: ""
        val name = step.optString("name", "").trim()
        val base = when (type) {
            "depart" -> "Inicia"
            "arrive" -> "Has llegado"
            "turn" -> when (modifier) {
                "left" -> "Gira a la izquierda"
                "right" -> "Gira a la derecha"
                "slight left" -> "Gira ligeramente a la izquierda"
                "slight right" -> "Gira ligeramente a la derecha"
                "sharp left" -> "Gira cerrado a la izquierda"
                "sharp right" -> "Gira cerrado a la derecha"
                "uturn" -> "Da la vuelta"
                "straight" -> "Sigue recto"
                else -> "Gira"
            }
            "merge" -> "Incorpórate"
            "on ramp", "off ramp" -> "Toma la salida/rampa"
            "fork" -> "En la bifurcación, sigue"
            "end of road" -> "Al final de la vía, gira"
            "continue" -> "Continúa"
            "roundabout", "rotary" -> "En la rotonda, toma la salida indicada"
            "new name" -> "Continúa"
            "notification" -> "Aviso"
            else -> if (type.isNotBlank()) type else "Continúa"
        }
        val dist = step.optDouble("distance", 0.0)
        val distTxt = if (dist >= 1000) String.format("%.1f km", dist / 1000.0) else String.format("%.0f m", dist)
        return if (name.isNotBlank() && type != "arrive") "$base en $name ($distTxt)" else if (type == "arrive") "Has llegado a tu destino" else "$base ($distTxt)"
    }

    fun formatDistanceMeters(m: Double): String = if (m >= 1000) String.format("%.2f km", m / 1000.0) else String.format("%.0f m", m)
    fun formatDurationSeconds(s: Double): String {
        val min = round(s / 60.0).toInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}
