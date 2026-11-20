package com.yqt.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
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
import com.yqt.tracker.consumption.ConsumptionPrefs
import com.yqt.tracker.consumption.ui.ConsumptionActivity
import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.data.WatchState
import com.yqt.tracker.databinding.ActivityMapBinding
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.service.ExternalNavFollowService
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class MapActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMapBinding
    private var client: YqtClient? = null

    private var mapLibreMap: MapLibreMap? = null
    private var mapStyle: Style? = null

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

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val REQ_LOC = 1001

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val SRC_DEVICES = "devices-source"
        private const val LAYER_DEVICES = "devices-layer"
        private const val SRC_ME = "me-source"
        private const val LAYER_ME = "me-layer"
        private const val SRC_ROUTE = "route-source"
        private const val LAYER_ROUTE = "route-layer"
        private const val IMG_ME = "me-icon"
    }

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

        tts = TextToSpeech(this, this)

        binding.myLocationButton.setOnClickListener { centerOnMe() }
        binding.followButton.setOnClickListener { toggleFollow() }
        binding.followExternalButton.setOnClickListener { toggleExternalFollow() }

        binding.map.onCreate(savedInstanceState)
        binding.map.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                mapStyle = style
                setupMapLayers(style)
                val pos = CameraPosition.Builder()
                    .target(LatLng(40.4168, -3.7038))
                    .zoom(6.0)
                    .build()
                map.cameraPosition = pos
                map.addOnMapClickListener { point ->
                    handleMapClick(point)
                    true
                }
                // apply any pending state
                updateMyMarker()
                updateDeviceMarkers()
                updateRouteLayer()
            }
        }

        ensureLocationPermission()
        startLocationUpdates()
        startRefreshLoop()
    }

    private fun setupMapLayers(style: Style) {
        if (style.getImage(IMG_ME) == null) {
            style.addImage(IMG_ME, makeMyLocationBitmap())
        }
        if (style.getSource(SRC_ROUTE) == null) {
            style.addSource(GeoJsonSource(SRC_ROUTE, FeatureCollection.fromFeatures(emptyArray())))
            style.addLayer(
                LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                    PropertyFactory.lineColor(Color.parseColor("#16A34A")),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        if (style.getSource(SRC_DEVICES) == null) {
            style.addSource(GeoJsonSource(SRC_DEVICES, FeatureCollection.fromFeatures(emptyArray())))
            style.addLayer(
                SymbolLayer(LAYER_DEVICES, SRC_DEVICES).withProperties(
                    PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
                )
            )
        }
        if (style.getSource(SRC_ME) == null) {
            style.addSource(GeoJsonSource(SRC_ME, FeatureCollection.fromFeatures(emptyArray())))
            style.addLayer(
                SymbolLayer(LAYER_ME, SRC_ME).withProperties(
                    PropertyFactory.iconImage(IMG_ME),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
                )
            )
        }
        // ensure route below markers if possible (layers added in order; route added first so below devices/me added later)
    }

    private fun handleMapClick(point: LatLng) {
        val map = mapLibreMap ?: return
        val screen = map.projection.toScreenLocation(point)
        val features = map.queryRenderedFeatures(screen, LAYER_DEVICES)
        if (features.isNotEmpty()) {
            val did = features[0].getStringProperty("did")
            if (!did.isNullOrBlank()) {
                selectDevice(did)
            }
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

    private fun playPositionSound() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            Handler(Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (_: Exception) {}
            }, 250)
        } catch (_: Exception) {
        }
    }

    private fun hasPositionChanged(old: com.yqt.tracker.data.WatchPosition?, new: com.yqt.tracker.data.WatchPosition?): Boolean {
        if (old == null && new == null) return false
        if (old == null || new == null) return new != null
        return GeoUtils.haversineMeters(old.lat, old.lng, new.lat, new.lng) > 1.0
    }

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
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            }
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocationChanged(last)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        updateBearing(location)
        myLocation = LatLng(location.latitude, location.longitude)
        updateMyMarker()
        if (following) {
            mapLibreMap?.let { map ->
                val cam = CameraPosition.Builder(map.cameraPosition).bearing(userBearing.toDouble()).build()
                map.cameraPosition = cam
            }
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

    private fun updateMyMarker() {
        val style = mapStyle ?: return
        val me = myLocation
        val features = if (me != null) {
            arrayOf(Feature.fromGeometry(Point.fromLngLat(me.longitude, me.latitude)))
        } else {
            emptyArray()
        }
        style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun centerOnMe() {
        val me = myLocation
        val map = mapLibreMap
        if (me != null && map != null) {
            val zoom = if (map.cameraPosition.zoom < 16.0) 16.0 else map.cameraPosition.zoom
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(me, zoom))
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
        val selectedBefore = selectedDid
        val oldSelectedPos = selectedBefore?.let { devices[it]?.position }
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
                val sel = selectedBefore
                if ((following || externalFollowing) && sel != null && hasPositionChanged(oldSelectedPos, states[sel]?.position)) {
                    playPositionSound()
                }
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
        val style = mapStyle ?: return
        val features = mutableListOf<Feature>()
        for ((did, st) in devices) {
            val pos = st.position ?: continue
            val selected = did == selectedDid
            val iconId = deviceIconId(did, selected)
            ensureDeviceIcon(style, did, st.info.name, selected)
            val f = Feature.fromGeometry(Point.fromLngLat(pos.lng, pos.lat))
            f.addStringProperty("did", did)
            f.addStringProperty("icon", iconId)
            f.addStringProperty("name", st.info.name)
            features.add(f)
        }
        style.getSourceAs<GeoJsonSource>(SRC_DEVICES)?.setGeoJson(FeatureCollection.fromFeatures(features))

        if (!fitDone) {
            val pts = mutableListOf<LatLng>()
            devices.values.mapNotNull { it.position }.forEach { pts.add(LatLng(it.lat, it.lng)) }
            myLocation?.let { pts.add(it) }
            val map = mapLibreMap
            if (map != null) {
                if (pts.size == 1) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 16.0))
                    fitDone = true
                } else if (pts.size > 1) {
                    try {
                        val b = LatLngBounds.Builder()
                        pts.forEach { b.include(it) }
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
                        fitDone = true
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun deviceIconId(did: String, selected: Boolean): String = "dev_${did}_${if (selected) "s" else "n"}"

    private fun ensureDeviceIcon(style: Style, did: String, name: String, selected: Boolean) {
        val id = deviceIconId(did, selected)
        // regenerate to reflect current selection/name
        val bmp = makeLabelBitmap(name, selected)
        if (style.getImage(id) != null) {
            style.removeImage(id)
        }
        style.addImage(id, bmp)
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

    private fun makeLabelBitmap(name: String, selected: Boolean): Bitmap {
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
        val pinHeight = (pinR * 2 + pinR * 1.6f + 4).toInt() // circle + tip
        val w = max(labelW + 8, (pinR * 2 + 8).toInt())
        val h = labelH + gap + pinHeight + 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        // pin at bottom center, tip at bottom
        val tipY = h - 2f
        val pinCircleCy = tipY - pinR * 1.6f
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#DC2626") }
        c.drawCircle(cx, pinCircleCy, pinR, pinPaint)
        val path = Path()
        path.moveTo(cx - pinR * 0.7f, pinCircleCy)
        path.lineTo(cx + pinR * 0.7f, pinCircleCy)
        path.lineTo(cx, tipY)
        path.close()
        c.drawPath(path, pinPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(cx, pinCircleCy, pinR * 0.45f, dotPaint)
        // label above pin
        val ly = pinCircleCy - pinR - gap - labelH
        val lx = (w - labelW) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Color.parseColor("#2563EB") else Color.parseColor("#111827") }
        val rect = RectF(lx, ly, lx + labelW, ly + labelH)
        c.drawRoundRect(rect, 12f, 12f, bg)
        c.drawText(name, lx + padH, ly + padV - fm.ascent, paintText)
        return bmp
    }

    private fun makeMyLocationBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val center = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        c.drawCircle(center, center, 14f, fill)
        c.drawCircle(center, center, 14f, stroke)
        return bmp
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
        // Si estaba activo el seguimiento externo (Google Maps), lo detenemos para usar el seguimiento integrado
        if (externalFollowing) stopExternalFollowing(showToast = false)
        following = true
        binding.followButton.text = getString(R.string.stop_follow)
        binding.navInstructionText.visibility = View.VISIBLE
        mapLibreMap?.let { map ->
            val zoom = if (map.cameraPosition.zoom < 17.0) 17.0 else map.cameraPosition.zoom
            val cam = CameraPosition.Builder(map.cameraPosition)
                .bearing(userBearing.toDouble())
                .zoom(zoom)
                .build()
            map.cameraPosition = cam
        }
        playPositionSound()
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
        mapLibreMap?.let { map ->
            val cam = CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
            map.cameraPosition = cam
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
        // Evita modos duplicados/confusos; el modo externo ejecuta seguimiento en servicio y abre Google Maps
        if (following) stopFollowing()
        externalFollowing = true
        binding.followExternalButton.text = getString(R.string.stop_follow_external)
        try {
            ExternalNavFollowService.start(this, did, st.info.name)
            playPositionSound()
            Toast.makeText(this, R.string.external_follow_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            externalFollowing = false
            binding.followExternalButton.text = getString(R.string.follow_external)
            Toast.makeText(this, "No se pudo iniciar el seguimiento externo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopExternalFollowing(showToast: Boolean) {
        if (!externalFollowing) {
            // asegurar parada del servicio aunque el estado local esté desincronizado
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
        currentRoute = null
        routeTargetDid = null
        routeTargetPoint = null
        currentStepIndex = 0
        lastSpokenStep = -1
        updateRouteLayer()
    }

    private fun updateRouteLayer(points: List<LatLng> = emptyList(), dashed: Boolean = false, color: String = "#16A34A") {
        val style = mapStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return
        if (points.size >= 2) {
            val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            source.setGeoJson(LineString.fromLngLats(coords))
        } else {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }

        var layer = style.getLayerAs<LineLayer>(LAYER_ROUTE)
        if (layer != null) {
            style.removeLayer(LAYER_ROUTE)
        }
        val builder = LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(Color.parseColor(color)),
            PropertyFactory.lineWidth(if (dashed) 4f else 5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        if (dashed) {
            builder.withProperties(PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)))
        }
        if (style.getLayer(LAYER_DEVICES) != null) {
            style.addLayerBelow(builder, LAYER_DEVICES)
        } else {
            style.addLayer(builder)
        }
    }

    private fun requestRoute(force: Boolean) {
        val did = selectedDid ?: return
        val st = devices[did] ?: return
        val tp = st.position ?: return
        val me = myLocation ?: return
        val target = LatLng(tp.lat, tp.lng)
        val now = System.currentTimeMillis()
        val moved = routeTargetPoint?.let { GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude) } ?: Double.MAX_VALUE
        val minMove = ConsumptionPrefs.rerouteMinDistanceMeters(this).toDouble().coerceAtLeast(5.0)
        val minInterval = ConsumptionPrefs.rerouteMinIntervalMs(this).coerceAtLeast(2000L)
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
        currentRoute = route
        routeTargetDid = did
        routeTargetPoint = target
        lastRouteFetchAt = System.currentTimeMillis()
        currentStepIndex = 0
        lastSpokenStep = -1
        val pts = route.geometry.map { LatLng(it.first, it.second) }
        updateRouteLayer(pts, dashed = false, color = "#16A34A")
        val name = devices[did]?.info?.name ?: did
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(route.distanceMeters)}, ~${GeoUtils.formatDurationSeconds(route.durationSeconds)}"
        if (following) updateNavigationInstruction()
    }

    private fun applyFallbackRoute(did: String, target: LatLng, name: String) {
        val me = myLocation ?: return
        currentRoute = null
        routeTargetDid = did
        routeTargetPoint = target
        val pts = listOf(me, target)
        updateRouteLayer(pts, dashed = true, color = "#F59E0B")
        val d = GeoUtils.haversineMeters(me.latitude, me.longitude, target.latitude, target.longitude)
        binding.routeText.text = getString(R.string.route_to, name) + ": ${GeoUtils.formatDistanceMeters(d)} (línea directa)"
    }

    private fun maybeRerouteForTargetMove() {
        val did = routeTargetDid ?: selectedDid ?: return
        val tp = devices[did]?.position ?: return
        val target = LatLng(tp.lat, tp.lng)
        val moved = routeTargetPoint?.let { GeoUtils.haversineMeters(it.latitude, it.longitude, target.latitude, target.longitude) } ?: Double.MAX_VALUE
        val minMove = ConsumptionPrefs.rerouteMinDistanceMeters(this).toDouble().coerceAtLeast(5.0)
        if (moved > minMove) requestRoute(force = true)
    }

    private fun onFollowTick() {
        val me = myLocation ?: return
        val map = mapLibreMap
        if (map != null) {
            val cam = CameraPosition.Builder(map.cameraPosition)
                .target(me)
                .bearing(userBearing.toDouble())
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
        }
        val route = currentRoute
        if (route != null) {
            val off = GeoUtils.distanceToPolylineMeters(me.latitude, me.longitude, route.geometry)
            val now = System.currentTimeMillis()
            val minInterval = ConsumptionPrefs.rerouteMinIntervalMs(this).coerceAtLeast(2000L)
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

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS).also {
            if (it) tts?.language = Locale("es", "ES")
        }
    }

    override fun onStart() {
        super.onStart()
        binding.map.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.map.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.map.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.map.onLowMemory()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        routeJob?.cancel()
        followJob?.cancel()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        binding.map.onDestroy()
        super.onDestroy()
    }
}
