package com.yqt.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yqt.tracker.R
import com.yqt.tracker.consumption.ui.ConsumptionActivity
import com.yqt.tracker.data.RouteInfo
import com.yqt.tracker.data.WatchState
import com.yqt.tracker.databinding.ActivityMapBinding
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.pursuit.PursuitInfo
import com.yqt.tracker.pursuit.PursuitState
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
import kotlin.math.*

class MapActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener, SensorEventListener {
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

    private var externalFollowing = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var fitDone = false

    // Rumbo
    private var gpsBearing: Float? = null
    private var sensorBearing: Float? = null
    private var fusedBearing: Float = 0f
    private var lastFix: Location? = null
    private var lastSmoothedZoom: Double? = null

    // Sensores de orientación
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var accelValues: FloatArray? = null
    private var magnetValues: FloatArray? = null

    private var followCamera = true
    private var lastPursuitState: PursuitState? = null
    private var suppressDeviceSelectionCallback = false
    private var lastCameraUpdateAt = 0L

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
        private const val SRC_FOOT = "foot-source"
        private const val LAYER_FOOT = "foot-layer"
        private const val IMG_ME = "me-icon"
        private const val FOOT_THRESHOLD_METERS = 20.0
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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

        binding.myLocationButton.setOnClickListener { recenterCamera() }
        binding.centerDeviceButton.setOnClickListener { centerOnSelectedDevice(zoomIn = true) }
        binding.followExternalButton.setOnClickListener { toggleExternalFollow() }
        binding.followButton.setOnClickListener { /* compatibilidad */ }

        binding.startPursuitButton.setOnClickListener { togglePursuit() }
        binding.recenterNavButton.setOnClickListener {
            followCamera = true
            updateNavOverlay()
            val info = PursuitForegroundService.state.value
            val target = displayedMyLocation(info) ?: myLocation
            if (target != null) {
                if (info.isActive) animateToPursuitCamera(target, info) else animateToNormalTarget(target, 16.5)
            }
        }
        binding.profileButton.setOnClickListener { cycleTravelProfile() }
        binding.voiceToggleButton.setOnClickListener { toggleVoice() }
        updateVoiceButton()
        updatePursuitToggleButton(PursuitForegroundService.state.value)

        setupDeviceDropdown()

        binding.map.onCreate(savedInstanceState)
        binding.map.getMapAsync { map ->
            mapLibreMap = map
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followCamera = false
                    updateNavOverlay()
                }
            }
            map.addOnCameraMoveListener { updateZoomIndicator() }
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
                updateZoomIndicator()
            }
        }

        ensureLocationPermission()
        ensureNotificationPermission()
        startLocationUpdates()
        startRefreshLoop()
        observePursuitState()
        startUiTicker()
    }

    private fun setupDeviceDropdown() {
        binding.deviceSelector.setOnItemClickListener { _, _, position, _ ->
            if (suppressDeviceSelectionCallback) return@setOnItemClickListener
            val entries = currentDeviceEntries()
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            selectDevice(entry.did, fromDropdown = true)
        }
    }

    private data class DeviceEntry(val did: String, val label: String)

    private fun currentDeviceEntries(): List<DeviceEntry> {
        return devices.values
            .sortedBy { it.info.name.lowercase(Locale.getDefault()) }
            .map { st ->
                val pos = st.position
                val label = buildString {
                    append(st.info.name)
                    if (pos?.battery != null) append(" · ${pos.battery}%")
                    if (pos?.locationType != null) append(" · ${pos.locationType}")
                }
                DeviceEntry(st.info.did, label)
            }
    }

    private fun refreshDeviceDropdown() {
        val entries = currentDeviceEntries()
        val labels = entries.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        suppressDeviceSelectionCallback = true
        binding.deviceSelector.setAdapter(adapter)
        val selectedLabel = entries.firstOrNull { it.did == selectedDid }?.label
        if (selectedLabel != null) {
            binding.deviceSelector.setText(selectedLabel, false)
        } else if (binding.deviceSelector.text?.isNotEmpty() == true) {
            binding.deviceSelector.setText("", false)
        }
        suppressDeviceSelectionCallback = false
    }

    private fun observePursuitState() {
        lifecycleScope.launch {
            PursuitForegroundService.state.collect { info ->
                renderPursuitInfo(info)
            }
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
        if (style.getSource(SRC_FOOT) == null) {
            style.addSource(GeoJsonSource(SRC_FOOT, FeatureCollection.fromFeatures(emptyArray())))
            style.addLayerAbove(
                LineLayer(LAYER_FOOT, SRC_FOOT).withProperties(
                    PropertyFactory.lineColor(Color.parseColor("#F59E0B")),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f))
                ),
                LAYER_ROUTE
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
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)
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
        menu.add(0, 6, 4, "Ver todos")
        menu.add(0, 2, 5, R.string.logout)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { refreshOnce(force = true); true }
            3 -> { startActivity(Intent(this, ConsumptionActivity::class.java)); true }
            4 -> { showIntervalDialog(); true }
            5 -> { showProximityAlertsDialog(); true }
            6 -> { fitAllDevices(animated = true); true }
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

    private fun startSensorUpdates() {
        val sm = sensorManager ?: return
        val rot = rotationSensor
        if (rot != null) {
            sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    private fun stopSensorUpdates() {
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rotMat, orient)
                val azimuthDeg = Math.toDegrees(orient[0].toDouble()).toFloat()
                val bearing = normalizeBearing(azimuthDeg)
                sensorBearing = smoothBearing(sensorBearing, bearing, 0.15f)
            }
            Sensor.TYPE_ACCELEROMETER -> accelValues = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> magnetValues = event.values.clone()
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER || event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val acc = accelValues
            val mag = magnetValues
            if (acc != null && mag != null) {
                val r = FloatArray(9)
                val i = FloatArray(9)
                if (SensorManager.getRotationMatrix(r, i, acc, mag)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(r, orient)
                    val bearing = normalizeBearing(Math.toDegrees(orient[0].toDouble()).toFloat())
                    sensorBearing = smoothBearing(sensorBearing, bearing, 0.12f)
                }
            }
        }
        updateFusedBearing()
        maybeUpdateCameraFromSensor()
    }

    private fun maybeUpdateCameraFromSensor() {
        val info = PursuitForegroundService.state.value
        if (!info.isActive || !followCamera) return
        val now = System.currentTimeMillis()
        if (now - lastCameraUpdateAt < 250L) return
        val target = displayedMyLocation(info) ?: return
        lastCameraUpdateAt = now
        animateToPursuitCamera(target, info)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun normalizeBearing(b: Float): Float {
        var v = b % 360f
        if (v < 0f) v += 360f
        return v
    }

    private fun smoothBearing(current: Float?, target: Float, alpha: Float): Float {
        if (current == null) return target
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return normalizeBearing(current + diff * alpha)
    }

    private fun updateFusedBearing() {
        val speed = mySpeedMps ?: 0f
        val gps = gpsBearing
        val sensor = sensorBearing
        val info = PursuitForegroundService.state.value
        val walkingPursuit = info.isActive && info.profile == TravelProfile.WALKING
        val carPursuit = info.isActive && info.profile == TravelProfile.CAR

        val target = when {
            // Caminando: brújula/sensor tiene prioridad; GPS solo como apoyo si hay movimiento claro.
            walkingPursuit -> when {
                sensor != null && speed < 1.2f -> sensor
                gps != null && speed >= 1.2f && sensor != null -> {
                    val t = ((speed - 1.2f) / 1.0f).coerceIn(0f, 1f)
                    val diff = bearingDelta(sensor, gps)
                    normalizeBearing(sensor + diff * t)
                }
                sensor != null -> sensor
                gps != null -> gps
                else -> fusedBearing
            }
            // Coche (TRACK UP): dirección de avance siempre arriba; el mapa rota según rumbo GPS.
            // Nunca usar brújula del móvil en coche (aunque el móvil gire).
            carPursuit -> when {
                gps != null -> gps
                else -> fusedBearing
            }
            // Fuera de persecución: comportamiento general.
            gps != null && speed >= 1.5f -> gps
            sensor != null && speed < 1.5f -> sensor
            gps != null && sensor != null -> {
                val t = ((speed - 0.8f) / 0.7f).coerceIn(0f, 1f)
                val diff = bearingDelta(sensor, gps)
                normalizeBearing(sensor + diff * t)
            }
            gps != null -> gps
            sensor != null -> sensor
            else -> fusedBearing
        }
        val alpha = when {
            walkingPursuit -> 0.18f
            carPursuit -> if (speed >= 1.0f) 0.28f else 0.12f
            speed >= 1.5f -> 0.25f
            else -> 0.18f
        }
        fusedBearing = smoothBearing(fusedBearing, target, alpha)
    }

    private fun bearingDelta(from: Float, to: Float): Float {
        var diff = to - from
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    override fun onLocationChanged(location: Location) {
        updateBearing(location)
        myLocation = LatLng(location.latitude, location.longitude)
        mySpeedMps = if (location.hasSpeed()) location.speed else mySpeedMps
        updateFusedBearing()
        updateMyMarker()
        updateUserSpeedUi()

        if (followCamera) {
            val pursuit = PursuitForegroundService.state.value
            val target = displayedMyLocation(pursuit) ?: LatLng(location.latitude, location.longitude)
            if (pursuit.isActive) {
                animateToPursuitCamera(target, pursuit)
            } else {
                // Visualización normal: no forzar cámara continuamente salvo que el usuario haya centrado;
                // se mantiene la vista y se actualiza marcador.
            }
        }
    }

    private fun updateBearing(location: Location) {
        if (location.hasBearing() && (location.speed >= 1.0f || location.hasSpeed())) {
            gpsBearing = normalizeBearing(location.bearing)
        } else {
            val prev = lastFix
            if (prev != null) {
                val d = prev.distanceTo(location)
                if (d > 1.5f) {
                    gpsBearing = normalizeBearing(prev.bearingTo(location))
                }
            }
        }
        lastFix = location
    }
    private fun displayedMyLocation(info: PursuitInfo = PursuitForegroundService.state.value): LatLng? {
        val real = myLocation ?: return null
        val useSnap = info.isActive && info.profile == TravelProfile.CAR
        if (!useSnap) return real
        val route = info.route ?: return real
        val snapped = GeoUtils.nearestPointOnPolyline(real.latitude, real.longitude, route.geometry) ?: return real
        return LatLng(snapped.first, snapped.second)
    }

    private fun pursuitCameraTilt(info: PursuitInfo): Double = if (info.isActive) 55.0 else 0.0

    private fun applyPursuitPadding(active: Boolean) {
        val map = mapLibreMap ?: return
        if (active) {
            val h = binding.map.height.coerceAtLeast(1)
            val bottom = (h * 0.30f).roundToInt()
            val top = (h * 0.10f).roundToInt()
            map.setPadding(0, top, 0, bottom)
        } else {
            map.setPadding(0, 0, 0, 0)
        }
    }

    private fun targetZoomForSpeed(info: PursuitInfo): Double {
        val speed = (mySpeedMps ?: 0f).toDouble().coerceAtLeast(0.0)
        val zoom = when (info.profile) {
            TravelProfile.WALKING -> {
                // Parado/lento -> más cerca; rápido -> algo más lejos
                val t = (speed / 2.0).coerceIn(0.0, 1.0)
                20.0 - (1.5 * t) // 20.0 lento -> 18.5 rápido
            }
            TravelProfile.CAR -> {
                // Parado/lento -> 20.0, rápido -> 18.0
                val fastSpeed = 13.9 // ~50 km/h
                val t = (speed / fastSpeed).coerceIn(0.0, 1.0)
                20.0 - (2.0 * t)
            }
        }
        val prev = lastSmoothedZoom ?: zoom
        val smoothed = prev + (zoom - prev) * 0.20
        lastSmoothedZoom = smoothed
        return smoothed.coerceIn(16.0, 20.5)
    }

    private fun animateToPursuitCamera(target: LatLng, info: PursuitInfo = PursuitForegroundService.state.value) {
        val map = mapLibreMap ?: return
        applyPursuitPadding(info.isActive)
        val builder = CameraPosition.Builder(map.cameraPosition)
            .target(target)
            .zoom(targetZoomForSpeed(info))
        if (info.isActive) {
            builder.bearing(fusedBearing.toDouble()).tilt(pursuitCameraTilt(info))
        } else {
            builder.tilt(0.0)
        }
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        updateZoomIndicator(builder.build().zoom)
    }

    private fun updateZoomIndicator(zoomOverride: Double? = null) {
        val map = mapLibreMap ?: return
        val zoom = zoomOverride ?: map.cameraPosition.zoom
        val pct = (((zoom - 10.0) / (20.0 - 10.0)) * 100.0).coerceIn(0.0, 100.0)
        binding.zoomIndicatorText.text = String.format(Locale.getDefault(), "Zoom: %.2f (%.0f%%)", zoom, pct)
    }

    private fun recenterCamera() {
        followCamera = true
        updateNavOverlay()
        val info = PursuitForegroundService.state.value
        val target = displayedMyLocation(info) ?: myLocation
        if (target != null) {
            if (info.isActive) animateToPursuitCamera(target, info) else animateToNormalTarget(target, 16.5)
        } else {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerOnMe() {
        val info = PursuitForegroundService.state.value
        val me = displayedMyLocation(info) ?: myLocation
        if (me == null) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
            return
        }
        followCamera = true
        updateNavOverlay()
        if (info.isActive) animateToPursuitCamera(me, info) else animateToNormalTarget(me, 17.0)
    }

    private fun animateToNormalTarget(target: LatLng, zoom: Double) {
        val map = mapLibreMap ?: return
        applyPursuitPadding(false)
        val cam = CameraPosition.Builder(map.cameraPosition)
            .target(target)
            .zoom(zoom)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
        updateZoomIndicator(cam.zoom)
    }

    private fun centerOnSelectedDevice(zoomIn: Boolean) {
        val did = selectedDid
        val st = did?.let { devices[it] }
        val pos = st?.position
        if (pos == null) {
            Toast.makeText(this, "Selecciona un dispositivo con posición", Toast.LENGTH_SHORT).show()
            return
        }
        followCamera = false
        updateNavOverlay()
        val target = LatLng(pos.lat, pos.lng)
        val zoom = if (zoomIn) 17.5 else 15.5
        animateToNormalTarget(target, zoom)
    }

    private fun fitAllDevices(animated: Boolean) {
        if (PursuitForegroundService.state.value.isActive) return
        val pts = mutableListOf<LatLng>()
        devices.values.mapNotNull { it.position }.forEach { pts.add(LatLng(it.lat, it.lng)) }
        myLocation?.let { pts.add(it) }
        val map = mapLibreMap ?: return
        if (pts.isEmpty()) return
        applyPursuitPadding(false)
        try {
            if (pts.size == 1) {
                val cu = CameraUpdateFactory.newLatLngZoom(pts[0], 16.0)
                if (animated) map.animateCamera(cu) else map.moveCamera(cu)
            } else {
                val b = LatLngBounds.Builder()
                pts.forEach { b.include(it) }
                val cu = CameraUpdateFactory.newLatLngBounds(b.build(), 80)
                if (animated) map.animateCamera(cu) else map.moveCamera(cu)
            }
        } catch (_: Exception) { }
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
                refreshDeviceDropdown()
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

    private fun selectDevice(did: String, fromDropdown: Boolean = false) {
        selectedDid = did
        val st = devices[did] ?: return
        binding.routeText.text = selectedSummary(did)
        refreshDeviceDropdown()
        updateDeviceMarkers()
        if (!fromDropdown) {
            // mantener sincronizado el desplegable
            val entries = currentDeviceEntries()
            val label = entries.firstOrNull { it.did == did }?.label
            if (label != null) {
                suppressDeviceSelectionCallback = true
                binding.deviceSelector.setText(label, false)
                suppressDeviceSelectionCallback = false
            }
        }
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

        if (!fitDone && !PursuitForegroundService.state.value.isActive) {
            fitAllDevices(animated = false)
            if (devices.values.any { it.position != null }) fitDone = true
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

    private fun togglePursuit() {
        val info = PursuitForegroundService.state.value
        if (info.isActive) {
            confirmStopPursuit()
        } else {
            startPursuitForSelected()
        }
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
        lastSmoothedZoom = null
        applyPursuitPadding(true)
        updateNavOverlay()
        PursuitForegroundService.startPursuit(this, did, st.info.name, profile)
        Toast.makeText(this, "Persecución iniciada (${profileLabel(profile)})", Toast.LENGTH_SHORT).show()
    }

    private fun confirmStopPursuit() {
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
            TravelProfile.CAR -> TravelProfile.WALKING
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

    private fun updatePursuitToggleButton(info: PursuitInfo = PursuitForegroundService.state.value) {
        binding.startPursuitButton.text = if (info.isActive) "Detener persecución" else "Iniciar persecución"
    }

    private fun updateNavOverlay() {
        val info = PursuitForegroundService.state.value
        val show = info.isActive && !followCamera
        binding.recenterNavButton.visibility = if (show) View.VISIBLE else View.GONE
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

        val trackerHeading = info.trackerPosition?.heading ?: info.lastKnownTrackerPosition?.heading
        binding.trackerHeadingText.text = "Rumbo: " + (trackerHeading?.let { "${it.roundToInt()}°" } ?: "-")

        val nextStep = info.route?.steps?.firstOrNull()
        if (active) {
            binding.nextManeuverText.text = when {
                info.state == PursuitState.WAITING_FOR_TRACKER -> "Esperando posición del tracker…"
                info.state == PursuitState.TRACKER_SIGNAL_LOST -> "Sin señal: última posición conservada"
                info.state == PursuitState.CALCULATING_ROUTE -> "Calculando ruta…"
                info.profile == TravelProfile.WALKING -> "Línea directa al destino"
                nextStep != null -> nextStep.instruction
                info.state == PursuitState.TRACKER_NEARBY -> "Cerca del tracker (persecución activa)"
                info.state == PursuitState.TRACKER_STATIONARY -> "Tracker detenido (persecución activa)"
                else -> "Persiguiendo a $name"
            }
            val maneuverDist = nextStep?.distanceMeters
            binding.navInstructionText.visibility = View.VISIBLE
            binding.navInstructionText.text = buildString {
                if (info.profile == TravelProfile.WALKING) {
                    append("A pie: línea recta en tiempo real al destino")
                } else if (nextStep != null) {
                    append("Próxima maniobra: ${GeoUtils.formatDistanceMeters(nextStep.distanceMeters)}")
                } else {
                    append("Sin instrucciones de ruta")
                }
                if (info.profile != TravelProfile.WALKING) {
                    append(" · Dist. maniobra: ")
                    append(maneuverDist?.let { GeoUtils.formatDistanceMeters(it) } ?: "-")
                }
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

        // Render de ruta según perfil
        val me = displayedMyLocation(info) ?: myLocation
        val trackerPos = info.trackerPosition ?: info.lastKnownTrackerPosition
        clearFootLayer()
        if (info.isActive && trackerPos != null && me != null) {
            when (info.profile) {
                TravelProfile.WALKING -> {
                    // Siempre línea recta directa al destino, en tiempo real
                    val pts = listOf(me, LatLng(trackerPos.latitude, trackerPos.longitude))
                    updateRouteLayer(pts, dashed = true, color = "#0EA5E9", dotted = true)
                    binding.routeDistanceText.text = "Ruta: directa"
                }
                TravelProfile.CAR -> {
                    val route = info.route
                    if (route != null && route.geometry.size >= 2) {
                        val pts = route.geometry.map { LatLng(it.first, it.second) }
                        updateRouteLayer(pts, dashed = false, color = "#16A34A", dotted = false)
                        val end = route.geometry.last()
                        val footMeters = GeoUtils.haversineMeters(end.first, end.second, trackerPos.latitude, trackerPos.longitude)
                        if (footMeters > FOOT_THRESHOLD_METERS) {
                            setFootLayer(listOf(LatLng(end.first, end.second), LatLng(trackerPos.latitude, trackerPos.longitude)))
                            binding.navInstructionText.visibility = View.VISIBLE
                            val existing = binding.navInstructionText.text.toString()
                            binding.navInstructionText.text = "$existing · A pie desde fin de carretera: ${GeoUtils.formatDistanceMeters(footMeters)}"
                        }
                    } else {
                        // Sin ruta: línea directa indicativa hasta que OSRM responda
                        val pts = listOf(me, LatLng(trackerPos.latitude, trackerPos.longitude))
                        updateRouteLayer(pts, dashed = true, color = "#F59E0B", dotted = false)
                    }
                }
            }
        } else if (!info.isActive) {
            if (info.state == PursuitState.STOPPED) {
                // mantener última visualización simple
            } else {
                clearFootLayer()
                // en visualización normal no mostramos ruta de persecución
            }
        }

        if (info.isActive) {
            binding.routeText.text = "Objetivo: $name · ${profileLabel(info.profile)} · ${stateLabel(info.state)}"
            info.trackerId?.let { if (selectedDid != it) selectDevice(it) }
        }

        val wasActive = lastPursuitState?.let { it != PursuitState.IDLE && it != PursuitState.STOPPED } == true
        if (wasActive && !info.isActive) {
            applyPursuitPadding(false)
            lastSmoothedZoom = null
            mapLibreMap?.let { map ->
                val target = myLocation ?: map.cameraPosition.target
                val cam = CameraPosition.Builder(map.cameraPosition)
                    .target(target)
                    .tilt(0.0)
                    .bearing(0.0)
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
            }
            fitAllDevices(animated = true)
        }

        updateLastUpdateAge(info)
        updateMyMarker()
        updatePursuitToggleButton(info)
        updateNavOverlay()
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
            PropertyFactory.lineWidth(if (dashed || dotted) 4f else 5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        when {
            dotted -> builder.withProperties(PropertyFactory.lineDasharray(arrayOf(1f, 2f)))
            dashed -> builder.withProperties(PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)))
        }
        if (style.getLayer(LAYER_FOOT) != null) {
            style.addLayerBelow(builder, LAYER_FOOT)
        } else if (style.getLayer(LAYER_DEVICES) != null) {
            style.addLayerBelow(builder, LAYER_DEVICES)
        } else {
            style.addLayer(builder)
        }
    }

    private fun setFootLayer(points: List<LatLng>) {
        val style = mapStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(SRC_FOOT) ?: return
        if (points.size >= 2) {
            val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            source.setGeoJson(LineString.fromLngLats(coords))
        } else {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    private fun clearFootLayer() {
        val style = mapStyle ?: return
        style.getSourceAs<GeoJsonSource>(SRC_FOOT)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
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

    private fun speak(text: String) {
        if (!ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navstep") } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS).also { if (it) try { tts?.language = Locale("es", "ES") } catch (_: Exception) {} }
    }

    override fun onStart() { super.onStart(); binding.map.onStart() }
    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        updateVoiceButton()
        startSensorUpdates()
    }
    override fun onPause() {
        stopSensorUpdates()
        binding.map.onPause()
        super.onPause()
    }
    override fun onStop() { binding.map.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.map.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.map.onLowMemory() }

    override fun onDestroy() {
        refreshJob?.cancel()
        uiTickJob?.cancel()
        stopSensorUpdates()
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        binding.map.onDestroy()
        super.onDestroy()
    }
}
