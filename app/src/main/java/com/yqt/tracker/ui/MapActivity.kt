package com.yqt.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.yqt.tracker.R
import com.yqt.tracker.consumption.ConsumptionManager
import com.yqt.tracker.consumption.ConsumptionPrefs
import com.yqt.tracker.consumption.ServiceCatalog
import com.yqt.tracker.consumption.ui.ConsumptionActivity
import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.data.WatchState
import com.yqt.tracker.databinding.ActivityMapBinding
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.service.ExternalNavFollowService
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class MapActivity : AppCompatActivity(), OnMapReadyCallback, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMapBinding
    private var client: YqtClient? = null
    private var googleMap: GoogleMap? = null

    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                handleLocation(loc)
            }
        }
    }

    private val deviceMarkers = mutableMapOf<String, Marker>()
    private var routePolyline: Polyline? = null

    private var devices: Map<String, WatchState> = emptyMap()
    private var selectedDid: String? = null
    private var myLocation: LatLng? = null

    private var refreshJob: Job? = null
    private var routeJob: Job? = null
    private var followJob: Job? = null
    private var following = false
    private var externalFollowing = false
    private var currentRoute: RouteInfo? = null
    private var routeTargetDid: String? = null
    private var routeTargetPoint: LatLng? = null
    private var lastRouteFetchAt = 0L
    private var currentStepIndex = 0
    private var lastSpokenStep = -1
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var fitDone = false
    private var userBearing: Float = 0f
    private var lastFix: Location? = null
    private var refreshStarted = false
    private var mapSessionLogged = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val REQ_LOC = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val creds = Prefs.load(this)
        if (creds == null) {
            goLogin()
            return
        }
        client = YqtClient(this, creds.first, creds.second, creds.third)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        tts = TextToSpeech(this, this)

        binding.myLocationButton.setOnClickListener { centerOnMe() }
        binding.followButton.setOnClickListener { toggleFollow() }
        binding.followExternalButton.setOnClickListener { toggleExternalFollow() }

        ensureLocationPermission()
        startLocationUpdates()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        try {
            map.isMyLocationEnabled = hasLocationPermission()
        } catch (_: SecurityException) {
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.4168, -3.7038), 6f))

        map.setOnMarkerClickListener { marker ->
            val did = marker.tag as? String
            if (did != null) {
                selectDevice(did)
                marker.showInfoWindow()
                true
            } else {
                false
            }
        }

        updateDeviceMarkers()
        if (!mapSessionLogged) {
            mapSessionLogged = true
            logLocalMapSession()
        }
        if (!refreshStarted) {
            refreshStarted = true
            startRefreshLoop()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.refresh)
        menu.add(0, 3, 1, R.string.consumption_control)
        menu.add(0, 2, 2, R.string.logout)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                refreshOnce(force = true)
                true
            }
            3 -> {
                startActivity(Intent(this, ConsumptionActivity::class.java))
                true
            }
            2 -> {
                logout()
                true
            }
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
        stopExternalFollowing(showToast = false)
        goLogin()
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            enableMyLocationLayer()
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationLayer() {
        val map = googleMap ?: return
        if (hasLocationPermission()) {
            try {
                map.isMyLocationEnabled = true
            } catch (_: SecurityException) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        enableMyLocationLayer()
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) handleLocation(loc)
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    private fun handleLocation(location: Location) {
        updateBearing(location)
        myLocation = LatLng(location.latitude, location.longitude)
        if (following) {
            updateFollowCamera()
            onFollowTick()
        }
    }

    private fun updateBearing(location: Location) {
        if (location.hasBearing()) {
            userBearing = location.bearing
        } else {
            val prev = lastFix
            if (prev != null && prev.distanceTo(location) > 1f) {
                userBearing = prev.bearingTo(location)
            }
        }
        lastFix = location
    }

    private fun updateFollowCamera(animate: Boolean = true) {
        val map = googleMap ?: return
        val me = myLocation ?: return
        val camera = CameraPosition.Builder()
            .target(me)
            .zoom(if (map.cameraPosition.zoom < 17f) 17.5f else map.cameraPosition.zoom)
            .bearing(userBearing)
            .tilt(50f)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    private fun centerOnMe() {
        val me = myLocation
        val map = googleMap
        if (me != null && map != null) {
            if (following) {
                updateFollowCamera()
            } else {
                val zoom = if (map.cameraPosition.zoom < 16f) 16f else map.cameraPosition.zoom
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(me, zoom))
            }
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
                try {
                    Result.success(c.refreshAllStates(devices))
                } catch (e: Exception) {
                    Result.failure(e)
                }
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
        val typeInfo = st.position?.locationType?.let { " · Posición: $it" } ?: ""
        binding.routeText.text = "Seleccionado: ${st.info.name}$typeInfo"
        // reset route when target changes
        if (routeTargetDid != did) {
            clearRoute()
        }
        updateDeviceMarkers()
        if (following) {
            requestRoute(force = true)
            startFollowLoop()
        }
    }

    private fun updateDeviceMarkers() {
        val map = googleMap ?: return
        val seen = mutableSetOf<String>()
        for ((did, st) in devices) {
            val pos = st.position ?: continue
            seen.add(did)
            val latLng = LatLng(pos.lat, pos.lng)
            val marker = deviceMarkers[did]
            val icon = makeLabelIcon(st.info.name, did == selectedDid)
            if (marker == null) {
                val m = map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title(st.info.name)
                        .snippet(buildInfo(st))
                        .icon(icon)
                        .anchor(labelAnchorX(st.info.name), labelAnchorY(st.info.name))
                )
                if (m != null) {
                    m.tag = did
                    deviceMarkers[did] = m
                }
            } else {
                marker.position = latLng
                marker.setIcon(icon)
                marker.title = st.info.name
                marker.snippet = buildInfo(st)
                marker.setAnchor(labelAnchorX(st.info.name), labelAnchorY(st.info.name))
                if (marker.isInfoWindowShown) marker.showInfoWindow()
            }
        }
        // remove missing
        val it = deviceMarkers.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen) {
                e.value.remove()
                it.remove()
            }
        }
        if (!fitDone) {
            val pts = devices.values.mapNotNull { it.position?.let { p -> LatLng(p.lat, p.lng) } }.toMutableList()
            myLocation?.let { pts.add(it) }
            if (pts.size == 1) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 16f))
                fitDone = true
            } else if (pts.size > 1) {
                try {
                    val builder = LatLngBounds.builder()
                    pts.forEach { builder.include(it) }
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
                    fitDone = true
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun buildInfo(st: WatchState): String {
        val p = st.position ?: return st.error ?: ""
        val parts = mutableListOf<String>()
        p.locationType?.let { parts.add("Posición: $it") }
        p.positionDate?.let { parts.add(it) }
        p.address?.let { parts.add(it) }
        p.battery?.let { parts.add("Batería: $it%") }
        st.error?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun labelMetrics(name: String): FloatArray {
        val padH = 16
        val padV = 10
        val pinR = 14f
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paintText.measureText(name)
        val fm = paintText.fontMetrics
        val textH = fm.descent - fm.ascent
        val labelW = (textW + padH * 2).toInt()
        val labelH = (textH + padV * 2).toInt()
        val gap = 6
        val w = (pinR * 2 + gap + labelW + 4)
        val h = max((pinR * 3).toInt(), labelH + 8)
        val pinCx = pinR + 2f
        val pinCy = h / 2f
        val tipY = pinCy + pinR * 1.6f
        return floatArrayOf(w, h.toFloat(), pinCx / w, tipY / h, pinR, pinCx, pinCy, labelW.toFloat(), labelH.toFloat(), padH.toFloat(), padV.toFloat(), fm.ascent)
    }

    private fun labelAnchorX(name: String): Float = labelMetrics(name)[2]
    private fun labelAnchorY(name: String): Float = labelMetrics(name)[3]

    private fun makeLabelIcon(name: String, selected: Boolean): BitmapDescriptor {
        val padH = 16f
        val padV = 10f
        val pinR = 14f
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paintText.measureText(name)
        val fm = paintText.fontMetrics
        val textH = fm.descent - fm.ascent
        val labelW = (textW + padH * 2)
        val labelH = (textH + padV * 2)
        val gap = 6f
        val w = (pinR * 2 + gap + labelW + 4).toInt()
        val h = max((pinR * 3).toInt(), (labelH + 8).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // pin
        val pinCx = pinR + 2f
        val pinCy = h / 2f
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#DC2626") }
        c.drawCircle(pinCx, pinCy - 6f, pinR, pinPaint)
        val path = Path()
        path.moveTo(pinCx - pinR * 0.7f, pinCy - 6f)
        path.lineTo(pinCx + pinR * 0.7f, pinCy - 6f)
        path.lineTo(pinCx, pinCy + pinR * 1.6f)
        path.close()
        c.drawPath(path, pinPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(pinCx, pinCy - 6f, pinR * 0.45f, dotPaint)
        // label box
        val lx = pinCx + pinR + gap
        val ly = (h - labelH) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#111827") }
        val rect = RectF(lx, ly, lx + labelW, ly + labelH)
        c.drawRoundRect(rect, 12f, 12f, bg)
        c.drawText(name, lx + padH, ly + padV - fm.ascent, paintText)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun toggleFollow() {
        if (following) stopFollowing() else startFollowing()
    }

    private fun startFollowing() {
        val did = selectedDid
        val st = did?.let { devices[it] }
        if (did == null || st?.position == null) {
            Toast.makeText(this, "Selecciona un reloj con posición", Toast.LENGTH_SHORT).show()
            return
        }
        if (myLocation == null) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
            return
        }
        following = true
        binding.followButton.text = getString(R.string.stop_follow)
        binding.navInstructionText.visibility = View.VISIBLE
        updateFollowCamera(animate = false)
        requestRoute(force = true)
        startFollowLoop()
    }

    private fun stopFollowing() {
        following = false
        followJob?.cancel()
        followJob = null
        binding.followButton.text = getString(R.string.follow)
        binding.navInstructionText.visibility = View.GONE
        binding.navInstructionText.text = ""
        val map = googleMap
        val me = myLocation
        if (map != null) {
            val target = me ?: map.cameraPosition.target
            val camera = CameraPosition.Builder()
                .target(target)
                .zoom(map.cameraPosition.zoom)
                .bearing(0f)
                .tilt(0f)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
        }
    }

    private fun toggleExternalFollow() {
        if (externalFollowing) stopExternalFollowing(showToast = true) else startExternalFollowing()
    }

    private fun startExternalFollowing() {
        val did = selectedDid
        val st = did?.let { devices[it] }
        if (did == null || st?.position == null) {
            Toast.makeText(this, "Selecciona un reloj con posición", Toast.LENGTH_SHORT).show()
            return
        }
        // Avoid duplicate/confusing modes; external mode runs in foreground service and opens Google Maps.
        if (following) stopFollowing()
        externalFollowing = true
        binding.followExternalButton.text = getString(R.string.stop_follow_external)
        try {
            ExternalNavFollowService.start(this, did, st.info.name)
            Toast.makeText(this, R.string.external_follow_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            externalFollowing = false
            binding.followExternalButton.text = getString(R.string.follow_external)
            Toast.makeText(this, "No se pudo iniciar el seguimiento externo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopExternalFollowing(showToast: Boolean) {
        if (!externalFollowing) {
            // ensure service stopped even if flag out of sync
            try { ExternalNavFollowService.stop(this) } catch (_: Exception) {}
            binding.followExternalButton.text = getString(R.string.follow_external)
            return
        }
        externalFollowing = false
        binding.followExternalButton.text = getString(R.string.follow_external)
        try { ExternalNavFollowService.stop(this) } catch (_: Exception) {}
        if (showToast) Toast.makeText(this, R.string.external_follow_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun startFollowLoop() {
        followJob?.cancel()
        followJob = lifecycleScope.launch {
            while (isActive && following) {
                val did = selectedDid
                if (did != null) {
                    val c = client
                    if (c != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                c.freshPosition(did)
                            } catch (_: Exception) {
                                // ignore individual trigger errors; position refresh will surface issues if persistent
                            }
                        }
                    }
                    refreshOnce(force = true)
                }
                delay(3_000)
            }
        }
    }

    private fun clearRoute() {
        routePolyline?.remove()
        routePolyline = null
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
        val target = LatLng(tp.lat, tp.lng)
        val now = System.currentTimeMillis()
        val moved = routeTargetPoint?.let {
            GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude)
        } ?: Double.MAX_VALUE
        val minMove = ConsumptionPrefs.rerouteMinDistanceMeters(this).toDouble()
        val minInterval = ConsumptionPrefs.rerouteMinIntervalMs(this)
        if (!force && currentRoute != null && moved < minMove && (now - lastRouteFetchAt) < minInterval) return
        if (routeJob?.isActive == true) return
        binding.routeText.text = getString(R.string.route_to, st.info.name) + " · recalculando…"
        routeJob = lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    Result.success(GeoUtils.fetchRoute(me.latitude, me.longitude, target.latitude, target.longitude))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            res.onSuccess { route ->
                if (route != null) applyRoute(did, target, route) else applyFallbackRoute(did, target, st.info.name)
            }.onFailure {
                applyFallbackRoute(did, target, st.info.name)
            }
        }
    }

    private fun applyRoute(did: String, target: LatLng, route: RouteInfo) {
        val map = googleMap ?: return
        currentRoute = route
        routeTargetDid = did
        routeTargetPoint = target
        lastRouteFetchAt = System.currentTimeMillis()
        currentStepIndex = 0
        lastSpokenStep = -1
        val pts = route.geometry.map { LatLng(it.first, it.second) }
        if (routePolyline == null) {
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(pts)
                    .color(Color.parseColor("#16A34A"))
                    .width(12f)
            )
        } else {
            routePolyline?.points = pts
            routePolyline?.color = Color.parseColor("#16A34A")
            routePolyline?.width = 12f
            routePolyline?.pattern = null
        }
        val name = devices[did]?.info?.name ?: did
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(route.distanceMeters)}, ~${GeoUtils.formatDurationSeconds(route.durationSeconds)}"
        if (following) updateNavigationInstruction()
    }

    private fun applyFallbackRoute(did: String, target: LatLng, name: String) {
        val map = googleMap ?: return
        val me = myLocation ?: return
        currentRoute = null
        routeTargetDid = did
        routeTargetPoint = target
        val pts = listOf(me, target)
        if (routePolyline == null) {
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(pts)
                    .color(Color.parseColor("#F59E0B"))
                    .width(10f)
                    .pattern(listOf(Dash(30f), Gap(20f)))
            )
        } else {
            routePolyline?.points = pts
            routePolyline?.color = Color.parseColor("#F59E0B")
            routePolyline?.width = 10f
            routePolyline?.pattern = listOf(Dash(30f), Gap(20f))
        }
        val d = GeoUtils.haversineMeters(me.latitude, me.longitude, target.latitude, target.longitude)
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(d)} (línea directa)"
    }

    private fun maybeRerouteForTargetMove() {
        val did = routeTargetDid ?: selectedDid ?: return
        val tp = devices[did]?.position ?: return
        val target = LatLng(tp.lat, tp.lng)
        val moved = routeTargetPoint?.let {
            GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude)
        } ?: Double.MAX_VALUE
        if (moved > 40.0) requestRoute(force = true)
    }

    private fun onFollowTick() {
        val me = myLocation ?: return
        val route = currentRoute
        if (route != null) {
            val off = GeoUtils.distanceToPolylineMeters(me.latitude, me.longitude, route.geometry)
            val now = System.currentTimeMillis()
            val minInterval = ConsumptionPrefs.rerouteMinIntervalMs(this)
            if (off > 60.0 && (now - lastRouteFetchAt) > minInterval) {
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
            if (d < bestD) {
                bestD = d
                best = i
            }
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
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navstep")
        } catch (_: Exception) {
        }
    }

    private fun logLocalMapSession() {
        val def = ServiceCatalog.byId("maps_sdk_android") ?: return
        // Registro local orientativo de sesión de visualización de mapa (no es evento oficial de facturación).
        val opId = "mapsdk_session_${System.currentTimeMillis()}_${hashCode()}"
        lifecycleScope.launch {
            try {
                ConsumptionManager.logEvent(
                    context = this@MapActivity,
                    serviceId = def.id,
                    sku = def.sku,
                    operationType = "map_session_display",
                    operationId = opId,
                    result = "ok",
                    billableQuantity = 1.0,
                    origin = "apertura_mapa",
                    metadata = "estimacion_local_sesion_visualizacion"
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS).also {
            if (it) tts?.language = Locale("es", "ES")
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        routeJob?.cancel()
        followJob?.cancel()
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
