package com.yqt.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yqt.tracker.R
import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.data.WatchState
import com.yqt.tracker.databinding.ActivityMapBinding
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class MapActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMapBinding
    private var client: YqtClient? = null

    private val deviceMarkers = mutableMapOf<String, Marker>()
    private var myMarker: Marker? = null
    private var routeLine: Polyline? = null

    private var devices: Map<String, WatchState> = emptyMap()
    private var selectedDid: String? = null
    private var myLocation: GeoPoint? = null

    private var refreshJob: Job? = null
    private var routeJob: Job? = null
    private var following = false
    private var currentRoute: RouteInfo? = null
    private var routeTargetDid: String? = null
    private var routeTargetPoint: GeoPoint? = null
    private var lastRouteFetchAt = 0L
    private var currentStepIndex = 0
    private var lastSpokenStep = -1
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var fitDone = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val REQ_LOC = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val creds = Prefs.load(this)
        if (creds == null) { goLogin(); return }
        client = YqtClient(this, creds.first, creds.second, creds.third)

        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(40.4168, -3.7038))

        tts = TextToSpeech(this, this)

        binding.myLocationButton.setOnClickListener { centerOnMe() }
        binding.followButton.setOnClickListener { toggleFollow() }

        ensureLocationPermission()
        startLocationUpdates()
        startRefreshLoop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.refresh)
        menu.add(0, 2, 1, R.string.logout)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { refreshOnce(force = true); true }
            2 -> { logout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun logout() {
        Prefs.clear(this)
        stopFollowing()
        goLogin()
    }

    private fun setStatus(text: String) { binding.statusText.text = text }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, this, Looper.getMainLooper())
            }
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocationChanged(last)
        } catch (_: Exception) { }
    }

    override fun onLocationChanged(location: Location) {
        myLocation = GeoPoint(location.latitude, location.longitude)
        updateMyMarker()
        if (following) onFollowTick()
    }

    private fun updateMyMarker() {
        val me = myLocation ?: return
        if (myMarker == null) {
            val m = Marker(binding.map)
            m.position = me
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.title = getString(R.string.my_location)
            m.icon = makeMyLocationIcon()
            m.setOnMarkerClickListener { _, _ -> true }
            binding.map.overlays.add(m)
            myMarker = m
        } else {
            myMarker?.position = me
        }
        binding.map.invalidate()
    }

    private fun centerOnMe() {
        val me = myLocation
        if (me != null) {
            binding.map.controller.animateTo(me)
            if (binding.map.zoomLevelDouble < 16.0) binding.map.controller.setZoom(16.0)
        } else {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRefreshLoop() {
        refreshOnce(force = false)
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                refreshOnce(force = false)
            }
        }
    }

    private fun refreshOnce(force: Boolean) {
        val c = client ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(c.refreshAllStates(devices)) } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess { states ->
                devices = states
                updateDeviceMarkers()
                val total = states.size
                val withPos = states.values.count { it.position != null }
                val base = getString(R.string.devices_count, withPos, total) + " · " + getString(R.string.updated_at, timeFmt.format(Date()))
                val errs = states.values.mapNotNull { st ->
                    st.error?.takeIf { it.isNotBlank() }?.let { "${st.info.name}: $it" }
                }.distinct()
                setStatus(if (errs.isEmpty()) base else base + " · " + errs.joinToString(" | "))
                maybeAutoSelect()
                if (following) maybeRerouteForTargetMove()
            }.onFailure { e ->
                setStatus("Error: ${e.message}")
            }
        }
    }

    private fun maybeAutoSelect() {
        val cur = selectedDid
        if (cur != null && devices[cur]?.position != null) return
        val first = devices.values.firstOrNull { it.position != null }
        if (first != null) selectDevice(first.info.did)
    }

    private fun selectDevice(did: String) {
        selectedDid = did
        val st = devices[did] ?: return
        binding.routeText.text = "Seleccionado: ${st.info.name}"
        // reset route when target changes
        if (routeTargetDid != did) {
            clearRoute()
        }
        if (following) requestRoute(force = true)
    }

    private fun updateDeviceMarkers() {
        val seen = mutableSetOf<String>()
        for ((did, st) in devices) {
            val pos = st.position ?: continue
            seen.add(did)
            val gp = GeoPoint(pos.lat, pos.lng)
            val marker = deviceMarkers[did]
            if (marker == null) {
                val m = Marker(binding.map)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                m.icon = makeLabelIcon(st.info.name, did == selectedDid)
                m.position = gp
                m.title = st.info.name
                m.setOnMarkerClickListener { _, _ ->
                    selectDevice(did)
                    m.showInfoWindow()
                    true
                }
                binding.map.overlays.add(m)
                deviceMarkers[did] = m
            } else {
                marker.position = gp
                marker.icon = makeLabelIcon(st.info.name, did == selectedDid)
            }
            deviceMarkers[did]?.subDescription = buildInfo(st)
        }
        // remove missing
        val it = deviceMarkers.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen) {
                binding.map.overlays.remove(e.value)
                it.remove()
            }
        }
        if (!fitDone) {
            val pts = devices.values.mapNotNull { it.position?.let { p -> GeoPoint(p.lat, p.lng) } }.toMutableList()
            myLocation?.let { pts.add(it) }
            if (pts.size == 1) { binding.map.controller.setZoom(16.0); binding.map.controller.setCenter(pts[0]); fitDone = true }
            else if (pts.size > 1) { try { binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 80) } catch (_: Exception) {}; fitDone = true }
        }
        binding.map.invalidate()
    }

    private fun buildInfo(st: WatchState): String {
        val p = st.position ?: return st.error ?: ""
        val parts = mutableListOf<String>()
        p.positionDate?.let { parts.add(it) }
        p.address?.let { parts.add(it) }
        p.battery?.let { parts.add("Batería: $it%") }
        st.error?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun makeLabelIcon(name: String, selected: Boolean): BitmapDrawable {
        val padH = 16; val padV = 10; val pinR = 14f
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paintText.measureText(name)
        val fm = paintText.fontMetrics
        val textH = fm.descent - fm.ascent
        val labelW = (textW + padH * 2).toInt()
        val labelH = (textH + padV * 2).toInt()
        val gap = 6
        val w = (pinR * 2 + gap + labelW + 4).toInt()
        val h = max((pinR * 3).toInt(), labelH + 8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // pin
        val pinCx = pinR + 2
        val pinCy = h / 2f
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#DC2626") }
        c.drawCircle(pinCx, pinCy - 6, pinR, pinPaint)
        val path = Path()
        path.moveTo(pinCx - pinR * 0.7f, pinCy - 6)
        path.lineTo(pinCx + pinR * 0.7f, pinCy - 6)
        path.lineTo(pinCx, pinCy + pinR * 1.6f)
        path.close()
        c.drawPath(path, pinPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(pinCx, pinCy - 6, pinR * 0.45f, dotPaint)
        // label box
        val lx = pinCx + pinR + gap
        val ly = (h - labelH) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#111827") }
        val rect = RectF(lx, ly, lx + labelW, ly + labelH)
        c.drawRoundRect(rect, 12f, 12f, bg)
        c.drawText(name, lx + padH, ly + padV - fm.ascent, paintText)
        val d = BitmapDrawable(resources, bmp)
        d.setBounds(0, 0, w, h)
        return d
    }

    private fun makeMyLocationIcon(): BitmapDrawable {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val center = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        c.drawCircle(center, center, 14f, fill)
        c.drawCircle(center, center, 14f, stroke)
        val d = BitmapDrawable(resources, bmp)
        d.setBounds(0, 0, size, size)
        return d
    }

    private fun toggleFollow() {
        if (following) stopFollowing() else startFollowing()
    }

    private fun startFollowing() {
        val did = selectedDid
        val st = did?.let { devices[it] }
        if (did == null || st?.position == null) {
            Toast.makeText(this, "Selecciona un reloj con posición", Toast.LENGTH_SHORT).show(); return
        }
        if (myLocation == null) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show(); return
        }
        following = true
        binding.followButton.text = getString(R.string.stop_follow)
        binding.navInstructionText.visibility = View.VISIBLE
        requestRoute(force = true)
    }

    private fun stopFollowing() {
        following = false
        binding.followButton.text = getString(R.string.follow)
        binding.navInstructionText.visibility = View.GONE
        binding.navInstructionText.text = ""
    }

    private fun clearRoute() {
        routeLine?.let { binding.map.overlays.remove(it) }
        routeLine = null
        currentRoute = null
        routeTargetDid = null
        routeTargetPoint = null
        currentStepIndex = 0
        lastSpokenStep = -1
    }

    private fun requestRoute(force: Boolean) {
        val did = selectedDid ?: return
        val st = devices[did] ?: return
        val tp = st.position ?: return
        val me = myLocation ?: return
        val target = GeoPoint(tp.lat, tp.lng)
        val now = System.currentTimeMillis()
        val moved = routeTargetPoint?.let { GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude) } ?: Double.MAX_VALUE
        if (!force && currentRoute != null && moved < 30.0 && (now - lastRouteFetchAt) < 15000) return
        if (routeJob?.isActive == true) return
        binding.routeText.text = getString(R.string.route_to, st.info.name) + " · recalculando…"
        routeJob = lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(GeoUtils.fetchRoute(me.latitude, me.longitude, target.latitude, target.longitude)) } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { route ->
                if (route != null) applyRoute(did, target, route) else applyFallbackRoute(did, target, st.info.name)
            }.onFailure {
                applyFallbackRoute(did, target, st.info.name)
            }
        }
    }

    private fun applyRoute(did: String, target: GeoPoint, route: RouteInfo) {
        currentRoute = route
        routeTargetDid = did
        routeTargetPoint = target
        lastRouteFetchAt = System.currentTimeMillis()
        currentStepIndex = 0
        lastSpokenStep = -1
        val pts = route.geometry.map { GeoPoint(it.first, it.second) }
        if (routeLine == null) {
            routeLine = Polyline().apply {
                outlinePaint.color = Color.parseColor("#16A34A")
                outlinePaint.strokeWidth = 10f
            }
            binding.map.overlays.add(routeLine)
        }
        routeLine?.setPoints(pts)
        val name = devices[did]?.info?.name ?: did
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(route.distanceMeters)}, ~${GeoUtils.formatDurationSeconds(route.durationSeconds)}"
        binding.map.invalidate()
        if (following) updateNavigationInstruction()
    }

    private fun applyFallbackRoute(did: String, target: GeoPoint, name: String) {
        val me = myLocation ?: return
        currentRoute = null
        routeTargetDid = did
        routeTargetPoint = target
        val pts = listOf(me, target)
        if (routeLine == null) {
            routeLine = Polyline().apply {
                outlinePaint.color = Color.parseColor("#F59E0B")
                outlinePaint.strokeWidth = 8f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
            }
            binding.map.overlays.add(routeLine)
        } else {
            routeLine?.outlinePaint?.color = Color.parseColor("#F59E0B")
            routeLine?.outlinePaint?.pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
        }
        routeLine?.setPoints(pts)
        val d = GeoUtils.haversineMeters(me.latitude, me.longitude, target.latitude, target.longitude)
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(d)} (línea directa)"
        binding.map.invalidate()
    }

    private fun maybeRerouteForTargetMove() {
        val did = routeTargetDid ?: selectedDid ?: return
        val tp = devices[did]?.position ?: return
        val target = GeoPoint(tp.lat, tp.lng)
        val moved = routeTargetPoint?.let { GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude) } ?: Double.MAX_VALUE
        if (moved > 40.0) requestRoute(force = true)
    }

    private fun onFollowTick() {
        val me = myLocation ?: return
        // keep camera following user
        binding.map.controller.animateTo(me)
        val route = currentRoute
        if (route != null) {
            val off = GeoUtils.distanceToPolylineMeters(me.latitude, me.longitude, route.geometry)
            val now = System.currentTimeMillis()
            if (off > 60.0 && (now - lastRouteFetchAt) > 8000) {
                binding.routeText.text = getString(R.string.rerouting)
                requestRoute(force = true)
                return
            }
            updateNavigationInstruction()
        } else {
            // fallback: just update distance
            val did = routeTargetDid ?: selectedDid
            val tp = did?.let { devices[it]?.position }
            if (tp != null) {
                val d = GeoUtils.haversineMeters(me.latitude, me.longitude, tp.lat, tp.lng)
                binding.navInstructionText.text = "Hacia ${devices[did]?.info?.name}: ${GeoUtils.formatDistanceMeters(d)}"
            }
        }
    }

    private fun updateNavigationInstruction() {
        val me = myLocation ?: return
        val route = currentRoute ?: return
        if (route.steps.isEmpty()) {
            binding.navInstructionText.text = getString(R.string.navigating, devices[routeTargetDid]?.info?.name ?: "")
            return
        }
        // pick next step: first step with maneuver location ahead/closest in remaining sequence
        var idx = currentStepIndex.coerceIn(0, route.steps.lastIndex)
        // advance if very close to current step maneuver and there is a next
        while (idx < route.steps.lastIndex) {
            val loc = route.steps[idx].location
            if (loc != null) {
                val d = GeoUtils.haversineMeters(me.latitude, me.longitude, loc.first, loc.second)
                if (d < 25.0) idx++ else break
            } else break
        }
        // also choose nearest upcoming by proximity among [idx..end]
        var best = idx
        var bestD = Double.MAX_VALUE
        for (i in idx..route.steps.lastIndex) {
            val loc = route.steps[i].location ?: continue
            val d = GeoUtils.haversineMeters(me.latitude, me.longitude, loc.first, loc.second)
            if (d < bestD) { bestD = d; best = i }
        }
        // prefer not to go backwards too much
        if (best >= currentStepIndex) idx = best
        currentStepIndex = idx
        val step = route.steps[idx]
        val toManeuver = step.location?.let { GeoUtils.haversineMeters(me.latitude, me.longitude, it.first, it.second) }
        val prefix = if (toManeuver != null && idx < route.steps.lastIndex) "En ${GeoUtils.formatDistanceMeters(toManeuver)}: " else ""
        val text = prefix + step.instruction
        binding.navInstructionText.text = text
        if (idx != lastSpokenStep) {
            lastSpokenStep = idx
            speak(step.instruction)
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navstep") } catch (_: Exception) {}
    }

    override fun onInit(status: Int) { ttsReady = (status == TextToSpeech.SUCCESS).also { if (it) tts?.language = Locale("es", "ES") } }

    override fun onResume() { super.onResume(); binding.map.onResume() }
    override fun onPause() { super.onPause(); binding.map.onPause() }

    override fun onDestroy() {
        refreshJob?.cancel()
        routeJob?.cancel()
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}
