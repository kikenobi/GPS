package com.yqt.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.yqt.tracker.pursuit.PursuitInfo
import com.yqt.tracker.pursuit.PursuitState
import com.yqt.tracker.pursuit.UserPosition
import com.yqt.tracker.routing.TravelProfile
import com.yqt.tracker.service.ExternalNavFollowService
import com.yqt.tracker.service.PursuitForegroundService
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import com.yqt.tracker.util.PursuitPrefs
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
import kotlin.math.roundToInt

class MapActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMapBinding
    private var client: YqtClient? = null

    private var mapLibreMap: MapLibreMap? = null
    private var mapStyle: Style? = null

    private var devices: Map<String, WatchState> = emptyMap()
    private var selectedDid: String? = null
    private var myLocation: LatLng? = null
    private var mySpeedMps: Float? = null

    private var refreshJob: Job? = null
    private var uiTickJob: Job? = null
    private var routeJob: Job? = null
    private var followJob: Job? = null

    // Modo integrado antiguo (compatibilidad básica)
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

    private var followCamera = true
    private var lastPursuitState: PursuitState? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val REQ_LOC = 1001
    private val REQ_NOTIF = 1002

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
        binding.recenterButton.setOnClickListener {
            followCamera = true
            val info = PursuitForegroundService.state.value
            val target = displayedMyLocation(info)
            if (target != null) {
                if (info.isActive) {
                    animateToPursuitCamera(target, info)
                } else {
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLng(target))
                }
            }
        }
        binding.followExternalButton.setOnClickListener { toggleExternalFollow() }
        binding.followButton.setOnClickListener { /* modo antiguo oculto; conservado por compatibilidad */ }

        binding.startPursuitButton.setOnClickListener { startPursuitForSelected() }
        binding.stopPursuitButton.setOnClickListener { confirmStopPursuit() }
        binding.profileButton.setOnClickListener { cycleTravelProfile() }
        binding.voiceToggleButton.setOnClickListener { toggleVoice() }
        updateVoiceButton()

        binding.map.onCreate(savedInstanceState)
        binding.map.getMapAsync { map ->
            mapLibreMap = map
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followCamera = false
                }
            }
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
                updateMyMarker()
                updateDeviceMarkers()
                updateRouteLayer()
            }
        }

        ensureLocationPermission()
        ensureNotificationPermission()
        startLocationUpdates()
        startRefreshLoop()
        observePursuitState()
        startUiTicker()
    }

    private fun observePursuitState() {
        lifecycleScope.launch {
            PursuitForegroundService.state.collect { info ->
                renderPursuitInfo(info)
            }
        }
        lifecycleScope.launch {
            PursuitForegroundService.trackerName.collect { /* nombre disponible si se requiere */ }
        }
    }

    private fun startUiTicker() {
        uiTickJob?.cancel()
        uiTickJob = lifecycleScope.launch {
            while (isActive) {
                updateLastUpdateAge()
                delay(1000L)
            }
        }
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
        menu.add(0, 4, 2, "Intervalo actualización")
        menu.add(0, 5, 3, "Alertas de proximidad")
        menu.add(0, 2, 4, R.string.logout)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { refreshOnce(force = true); true }
            3 -> { startActivity(Intent(this, ConsumptionActivity::class.java)); true }
            4 -> { showIntervalDialog(); true }
            5 -> { showProximityAlertsDialog(); true }
            2 -> { logout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showIntervalDialog() {
        val options = arrayOf("5 segundos", "10 segundos", "30 segundos", "60 segundos", "5 minutos")
        val values = longArrayOf(5_000, 10_000, 30_000, 60_000, 300_000)
        val current = PursuitPrefs.trackerPollIntervalMs(this)
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("Intervalo fuera de persecución")
            .setSingleChoiceItems(options, checked) { d, which ->
                PursuitPrefs.setTrackerPollIntervalMs(this, values[which])
                restartRefreshLoop()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showProximityAlertsDialog() {
        val labels = arrayOf("500 m", "100 m", "50 m", "10 m")
        val meters = intArrayOf(500, 100, 50, 10)
        val checked = booleanArrayOf(
            PursuitPrefs.alert500(this),
            PursuitPrefs.alert100(this),
            PursuitPrefs.alert50(this),
            PursuitPrefs.alert10(this),
        )
        AlertDialog.Builder(this)
            .setTitle("Alertas de proximidad")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                PursuitPrefs.setAlertThreshold(this, meters[which], isChecked)
            }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun logout() {
        PursuitForegroundService.stop(this)
        Prefs.clear(this)
        stopExternalFollowing(showToast = false)
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
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
        mySpeedMps = if (location.hasSpeed()) location.speed else null
        val pursuit = PursuitForegroundService.state.value
        updateMyMarker()
        updateUserSpeedUi()

        if (followCamera) {
            val target = displayedMyLocation(pursuit) ?: LatLng(location.latitude, location.longitude)
            if (pursuit.isActive) {
                animateToPursuitCamera(target, pursuit)
            } else {
                mapLibreMap?.let { map ->
                    val cam = CameraPosition.Builder(map.cameraPosition)
                        .target(target)
                        .zoom(max(map.cameraPosition.zoom, 16.0))
                        .tilt(0.0)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
                }
            }
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

    private fun displayedMyLocation(info: PursuitInfo = PursuitForegroundService.state.value): LatLng? {
        val real = myLocation ?: return null
        val useSnap = info.isActive && (info.profile == TravelProfile.CAR || info.profile == TravelProfile.MOTORCYCLE)
        if (!useSnap) return real
        val route = info.route ?: return real
        val snapped = GeoUtils.nearestPointOnPolyline(real.latitude, real.longitude, route.geometry) ?: return real
        return LatLng(snapped.first, snapped.second)
    }

    private fun pursuitCameraTilt(info: PursuitInfo = PursuitForegroundService.state.value): Double =
        if (info.isActive) 50.0 else 0.0

    private fun animateToPursuitCamera(target: LatLng, info: PursuitInfo = PursuitForegroundService.state.value) {
        val map = mapLibreMap ?: return
        val zoom = max(map.cameraPosition.zoom, 17.0)
        val builder = CameraPosition.Builder(map.cameraPosition)
            .target(target)
            .zoom(zoom)
        if (info.isActive) {
            builder.bearing(userBearing.toDouble()).tilt(pursuitCameraTilt(info))
        } else {
            builder.tilt(0.0)
        }
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    private fun updateMyMarker() {
        val style = mapStyle ?: return
        val me = displayedMyLocation()
        val features = if (me != null) {
            arrayOf(Feature.fromGeometry(Point.fromLngLat(me.longitude, me.latitude)))
        } else {
            emptyArray()
        }
        style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun centerOnMe() {
        val map = mapLibreMap
        val info = PursuitForegroundService.state.value
        val me = displayedMyLocation(info)
        if (me != null && map != null) {
            followCamera = true
            val zoom = if (map.cameraPosition.zoom < 16.0) 16.0 else map.cameraPosition.zoom
            if (info.isActive) {
                animateToPursuitCamera(me, info)
            } else {
                val cam = CameraPosition.Builder(map.cameraPosition)
                    .target(me)
                    .zoom(zoom)
                    .tilt(0.0)
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
            }
        } else {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartRefreshLoop() {
        refreshJob?.cancel()
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        refreshOnce(force = false)
        val interval = PursuitPrefs.trackerPollIntervalMs(this).coerceAtLeast(5_000L)
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(interval)
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
                val errs = states.values.mapNotNull { st -> st.error?.takeIf { it.isNotBlank() }?.let { "${st.info.name}: $it" } }.distinct()
                setStatus(if (errs.isEmpty()) base else base + " · " + errs.joinToString(" | "))
                maybeAutoSelect()
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
        binding.routeText.text = selectedSummary(did)
        updateDeviceMarkers()
    }

    private fun selectedSummary(did: String? = selectedDid): String {
        val st = did?.let { devices[it] } ?: return ""
        val parts = mutableListOf(st.info.name)
        st.position?.locationType?.let { parts.add(it) }
        st.position?.battery?.let { parts.add("${it}%") }
        return "Seleccionado: " + parts.joinToString(" · ")
    }

    private fun deviceLabel(st: WatchState): String {
        val bat = st.position?.battery
        return if (bat != null) "${st.info.name} ${bat}%" else st.info.name
    }

    private fun updateDeviceMarkers() {
        val style = mapStyle ?: return
        val features = mutableListOf<Feature>()
        for ((did, st) in devices) {
            val pos = st.position ?: continue
            val selected = did == selectedDid
            val iconId = deviceIconId(did, selected)
            ensureDeviceIcon(style, did, deviceLabel(st), selected)
            val f = Feature.fromGeometry(Point.fromLngLat(pos.lng, pos.lat))
            f.addStringProperty("did", did)
            f.addStringProperty("icon", iconId)
            features.add(f)
        }
        style.getSourceAs<GeoJsonSource>(SRC_DEVICES)?.setGeoJson(FeatureCollection.fromFeatures(features))

        if (selectedDid != null && !PursuitForegroundService.state.value.isActive) {
            val summary = selectedSummary(selectedDid)
            if (summary.isNotBlank()) binding.routeText.text = summary
        }

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
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun deviceIconId(did: String, selected: Boolean): String = "dev_${did}_${if (selected) "s" else "n"}"

    private fun ensureDeviceIcon(style: Style, did: String, label: String, selected: Boolean) {
        val id = deviceIconId(did, selected)
        val bmp = makeLabelBitmap(label, selected)
        if (style.getImage(id) != null) style.removeImage(id)
        style.addImage(id, bmp)
    }

    private fun makeLabelBitmap(name: String, selected: Boolean): Bitmap {
        val padH = 16; val padV = 10; val pinR = 14f
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paintText.measureText(name)
        val fm = paintText.fontMetrics
        val textH = fm.descent - fm.ascent
        val labelW = (textW + padH * 2).toInt()
        val labelH = (textH + padV * 2).toInt()
        val gap = 6
        val pinHeight = (pinR * 2 + pinR * 1.6f + 4).toInt()
        val w = max(labelW + 8, (pinR * 2 + 8).toInt())
        val h = labelH + gap + pinHeight + 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
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

    private fun startPursuitForSelected() {
        val did = selectedDid
        val st = did?.let { devices[it] }
        if (did == null || st == null) {
            Toast.makeText(this, "Selecciona un reloj", Toast.LENGTH_SHORT).show()
            return
        }
        ensureLocationPermission()
        ensureNotificationPermission()
        val profile = PursuitPrefs.travelProfile(this)
        followCamera = true
        PursuitForegroundService.startPursuit(this, did, st.info.name, profile)
        Toast.makeText(this, "Persecución iniciada (${profileLabel(profile)})", Toast.LENGTH_SHORT).show()
    }

    private fun confirmStopPursuit() {
        // Detener persecución: solo acción explícita del usuario (confirmada)
        AlertDialog.Builder(this)
            .setTitle("Detener persecución")
            .setMessage("La persecución solo se detiene manualmente. ¿Deseas detenerla ahora?")
            .setPositiveButton("Detener") { _, _ ->
                PursuitForegroundService.stop(this)
                Toast.makeText(this, "Persecución detenida", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cycleTravelProfile() {
        val next = when (PursuitPrefs.travelProfile(this)) {
            TravelProfile.CAR -> TravelProfile.MOTORCYCLE
            TravelProfile.MOTORCYCLE -> TravelProfile.WALKING
            TravelProfile.WALKING -> TravelProfile.CAR
        }
        PursuitPrefs.setTravelProfile(this, next)
        binding.profileButton.text = profileLabel(next)
        if (PursuitForegroundService.state.value.isActive) {
            PursuitForegroundService.updateProfile(this, next)
        }
    }

    private fun profileLabel(p: TravelProfile): String = when (p) {
        TravelProfile.CAR -> "Coche"
        TravelProfile.MOTORCYCLE -> "Moto"
        TravelProfile.WALKING -> "Caminando"
    }

    private fun toggleVoice() {
        val newVal = !PursuitPrefs.voiceEnabled(this)
        PursuitPrefs.setVoiceEnabled(this, newVal)
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        binding.voiceToggleButton.text = if (PursuitPrefs.voiceEnabled(this)) "Voz: ON" else "Voz: OFF"
        binding.profileButton.text = profileLabel(PursuitPrefs.travelProfile(this))
    }

    private fun renderPursuitInfo(info: PursuitInfo) {
        val active = info.isActive
        val name = PursuitForegroundService.trackerName.value.ifBlank { info.trackerId ?: selectedDid?.let { devices[it]?.info?.name } ?: "Tracker" }

        binding.connectionStateText.text = "Estado: ${stateLabel(info.state)}"

        val straight = info.straightDistanceMeters
        binding.straightDistanceText.text = "Recta: " + (straight?.let { GeoUtils.formatDistanceMeters(it) } ?: "-")

        val routeDist = info.routeDistanceMeters
        binding.routeDistanceText.text = "Ruta: " + (routeDist?.let { GeoUtils.formatDistanceMeters(it) } ?: if (active) "no disponible" else "-")

        val eta = info.estimatedTimeSeconds
        binding.etaText.text = "ETA: " + (eta?.let { formatEta(it) } ?: if (routeDist == null && active) "no disponible" else "-")

        val trackerSpeed = info.trackerPosition?.speed ?: info.lastKnownTrackerPosition?.speed
        binding.trackerSpeedText.text = "Vel. tracker: " + (trackerSpeed?.let { formatSpeed(it) } ?: "-")

        val heading = info.trackerPosition?.heading ?: info.lastKnownTrackerPosition?.heading
        binding.trackerHeadingText.text = "Rumbo: " + (heading?.let { "${it.roundToInt()}°" } ?: "-")

        // Próxima maniobra / instrucción
        val nextStep = info.route?.steps?.firstOrNull()
        if (active) {
            binding.nextManeuverText.text = when {
                info.state == PursuitState.WAITING_FOR_TRACKER -> "Esperando posición del tracker…"
                info.state == PursuitState.TRACKER_SIGNAL_LOST -> "Sin señal: última posición conservada"
                info.state == PursuitState.CALCULATING_ROUTE -> "Calculando ruta…"
                nextStep != null -> nextStep.instruction
                info.state == PursuitState.TRACKER_NEARBY -> "Cerca del tracker (persecución activa)"
                info.state == PursuitState.TRACKER_STATIONARY -> "Tracker detenido (persecución activa)"
                else -> "Persiguiendo a $name"
            }
            val maneuverDist = nextStep?.distanceMeters
            binding.navInstructionText.visibility = View.VISIBLE
            binding.navInstructionText.text = buildString {
                if (nextStep != null) {
                    append("Próxima maniobra: ${GeoUtils.formatDistanceMeters(nextStep.distanceMeters)}")
                } else {
                    append("Sin instrucciones de ruta")
                }
                append(" · Dist. maniobra: ")
                append(maneuverDist?.let { GeoUtils.formatDistanceMeters(it) } ?: "-")
            }
        } else {
            if (info.state == PursuitState.STOPPED) {
                binding.nextManeuverText.text = "Persecución detenida"
                binding.navInstructionText.visibility = View.GONE
            } else if (selectedDid != null) {
                binding.nextManeuverText.text = selectedSummary(selectedDid)
                binding.navInstructionText.visibility = View.GONE
            }
        }

        // Ruta en mapa: preferir ruta del controlador de persecución
        val route = info.route
        val me = displayedMyLocation(info) ?: myLocation
        if (info.isActive && route != null) {
            val pts = route.geometry.map { LatLng(it.first, it.second) }
            val walking = info.profile == TravelProfile.WALKING
            updateRouteLayer(
                pts,
                dashed = walking,
                color = if (walking) "#0EA5E9" else "#16A34A",
                dotted = walking,
            )
        } else if (info.isActive && info.lastKnownTrackerPosition != null && me != null) {
            // sin ruta válida: línea directa (indicativa)
            val t = info.lastKnownTrackerPosition!!
            updateRouteLayer(listOf(me, LatLng(t.latitude, t.longitude)), dashed = true, color = "#F59E0B")
        } else if (!info.isActive) {
            if (info.state == PursuitState.STOPPED) {
                // mantener última visualización simple
            }
        }

        if (info.isActive) {
            binding.routeText.text = "Objetivo: $name · ${profileLabel(info.profile)} · ${stateLabel(info.state)}"
            // Asegurar que el objetivo de persecución esté seleccionado
            info.trackerId?.let { if (selectedDid != it) selectedDid = it }
        }

        val wasActive = lastPursuitState?.let { it != PursuitState.IDLE && it != PursuitState.STOPPED } == true
        if (wasActive && !info.isActive) {
            // Salir de modo persecución: volver a vista cenital (plano normal)
            mapLibreMap?.let { map ->
                val target = myLocation ?: map.cameraPosition.target
                val cam = CameraPosition.Builder(map.cameraPosition)
                    .target(target)
                    .tilt(0.0)
                    .bearing(0.0)
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
            }
        }

        updateLastUpdateAge(info)
        updateMyMarker()
        if (info.isActive && followCamera) {
            val target = displayedMyLocation(info)
            if (target != null) animateToPursuitCamera(target, info)
        }
        lastPursuitState = info.state
    }

    private fun updateUserSpeedUi() {
        val s = mySpeedMps
        binding.userSpeedText.text = "Tu vel.: " + (s?.let { String.format(Locale.getDefault(), "%.1f km/h", it * 3.6f) } ?: "-")
    }

    private fun updateLastUpdateAge(info: PursuitInfo = PursuitForegroundService.state.value) {
        val ts = info.lastUpdateTimestamp ?: info.lastKnownTrackerPosition?.receivedAtMillis
        if (ts == null) {
            binding.lastUpdateAgeText.text = "Antigüedad: -"
            return
        }
        val ageSec = ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(0L)
        binding.lastUpdateAgeText.text = "Antigüedad: ${ageSec}s"
        if (info.isActive && ageSec > 15) {
            binding.connectionStateText.text = "Estado: ${stateLabel(info.state)} (dato antiguo)"
        }
    }

    private fun stateLabel(state: PursuitState): String = when (state) {
        PursuitState.IDLE -> "Inactivo"
        PursuitState.WAITING_FOR_TRACKER -> "Esperando tracker"
        PursuitState.CALCULATING_ROUTE -> "Calculando ruta"
        PursuitState.PURSUING -> "Persiguiendo"
        PursuitState.TRACKER_NEARBY -> "Cerca (activo)"
        PursuitState.TRACKER_STATIONARY -> "Tracker detenido (activo)"
        PursuitState.TRACKER_SIGNAL_LOST -> "Sin señal"
        PursuitState.RECOVERING_CONNECTION -> "Recuperando"
        PursuitState.STOPPED -> "Detenido"
    }

    private fun formatEta(seconds: Long): String {
        val min = (seconds / 60).toInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    private fun formatSpeed(speed: Double): String {
        // Heurística: si parece m/s (<60) convertir a km/h; si ya parece km/h, mostrar tal cual.
        val kmh = if (speed <= 60.0) speed * 3.6 else speed
        return String.format(Locale.getDefault(), "%.1f km/h", kmh)
    }

    private fun updateRouteLayer(
        points: List<LatLng> = emptyList(),
        dashed: Boolean = false,
        color: String = "#16A34A",
        dotted: Boolean = false,
    ) {
        val style = mapStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return
        if (points.size >= 2) {
            val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            source.setGeoJson(LineString.fromLngLats(coords))
        } else {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }

        if (style.getLayer(LAYER_ROUTE) != null) style.removeLayer(LAYER_ROUTE)
        val builder = LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(Color.parseColor(color)),
            PropertyFactory.lineWidth(if (dashed) 4f else 5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        when {
            dotted -> builder.withProperties(PropertyFactory.lineDasharray(arrayOf(1f, 2f)))
            dashed -> builder.withProperties(PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)))
        }
        if (style.getLayer(LAYER_DEVICES) != null) style.addLayerBelow(builder, LAYER_DEVICES) else style.addLayer(builder)
    }

    // ---- Seguimiento externo en Google Maps (conservado) ----
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
        if (PursuitForegroundService.state.value.isActive) {
            // permitir coexistencia lógica no simultánea confusa: se puede mantener, pero avisamos
        }
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
            try { ExternalNavFollowService.stop(this) } catch (_: Exception) {}
            binding.followExternalButton.text = getString(R.string.follow_external)
            return
        }
        externalFollowing = false
        binding.followExternalButton.text = getString(R.string.follow_external)
        try { ExternalNavFollowService.stop(this) } catch (_: Exception) {}
        if (showToast) Toast.makeText(this, R.string.external_follow_stopped, Toast.LENGTH_SHORT).show()
    }

    // ---- TTS local (heredado; la voz principal de persecución vive en el servicio) ----
    private fun speak(text: String) {
        if (!ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navstep") } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS).also { if (it) try { tts?.language = Locale("es", "ES") } catch (_: Exception) {} }
    }

    override fun onStart() { super.onStart(); binding.map.onStart() }
    override fun onResume() { super.onResume(); binding.map.onResume(); updateVoiceButton() }
    override fun onPause() { binding.map.onPause(); super.onPause() }
    override fun onStop() { binding.map.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.map.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.map.onLowMemory() }

    override fun onDestroy() {
        refreshJob?.cancel()
        uiTickJob?.cancel()
        routeJob?.cancel()
        followJob?.cancel()
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        binding.map.onDestroy()
        super.onDestroy()
    }
}
