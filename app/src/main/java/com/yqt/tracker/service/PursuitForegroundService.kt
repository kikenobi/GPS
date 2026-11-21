package com.yqt.tracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yqt.tracker.R
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.pursuit.PursuitController
import com.yqt.tracker.pursuit.PursuitInfo
import com.yqt.tracker.pursuit.PursuitState
import com.yqt.tracker.pursuit.UserPosition
import com.yqt.tracker.routing.OsrmRoutingProvider
import com.yqt.tracker.routing.TravelProfile
import com.yqt.tracker.tracking.YqtTrackerPositionProvider
import com.yqt.tracker.ui.MapActivity
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import com.yqt.tracker.util.PursuitPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class PursuitForegroundService : Service(), LocationListener, TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idlePollJob: Job? = null
    private var client: YqtClient? = null
    private var provider: YqtTrackerPositionProvider? = null
    private var controller: PursuitController? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var lastSpokenInstruction: String? = null
    private var lastSpokenState: PursuitState? = null
    private val firedProximity = mutableSetOf<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createChannel()
        ensureClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPursuitInternal(userRequested = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PURSUIT -> {
                val did = intent.getStringExtra(EXTRA_DID) ?: return START_STICKY
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { did }
                val profileName = intent.getStringExtra(EXTRA_PROFILE)
                val profile = runCatching { TravelProfile.valueOf(profileName ?: "") }
                    .getOrDefault(PursuitPrefs.travelProfile(this))
                startPursuitInternal(did, name, profile)
            }
            ACTION_UPDATE_PROFILE -> {
                val profileName = intent.getStringExtra(EXTRA_PROFILE)
                val profile = runCatching { TravelProfile.valueOf(profileName ?: "") }.getOrNull() ?: return START_STICKY
                PursuitPrefs.setTravelProfile(this, profile)
                controller?.updateProfile(profile)
                updateNotificationFromState()
            }
            ACTION_SET_VOICE -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                PursuitPrefs.setVoiceEnabled(this, enabled)
            }
        }
        return START_STICKY
    }

    private fun ensureClient() {
        if (client != null && provider != null && controller != null) return
        val creds = Prefs.load(this) ?: return
        val c = YqtClient(this, creds.first, creds.second, creds.third)
        client = c
        provider = YqtTrackerPositionProvider(c)
        controller = PursuitController(OsrmRoutingProvider())
        serviceScope.launch {
            controller?.info?.collect { info ->
                _state.value = info
                onPursuitInfo(info)
            }
        }
    }

    private fun startPursuitInternal(trackerId: String, trackerName: String, profile: TravelProfile) {
        ensureClient()
        val c = client ?: return
        val p = provider ?: return
        val ctrl = controller ?: return

        _trackerName.value = trackerName
        PursuitPrefs.setTravelProfile(this, profile)
        firedProximity.clear()
        lastSpokenInstruction = null
        lastSpokenState = null

        startForeground(NOTIF_ID, buildNotification("Iniciando persecución: $trackerName"))
        startLocationUpdates()

        // 1s durante persecución activa (provider solicita fresh). Reutiliza YqtClient existente.
        ctrl.startPursuit(
            trackerId = trackerId,
            profile = profile,
            scope = serviceScope,
            positionProvider = p,
            initialUserPosition = lastUserPosition,
        )
        // asegurar que tenemos user position en controlador si ya la conocemos
        lastUserPosition?.let { ctrl.updateUserPosition(it) }

        stopIdlePolling()
    }

    private fun stopPursuitInternal(userRequested: Boolean) {
        // Solo el usuario puede detener: este método se invoca desde acción explícita del usuario.
        if (!userRequested) return
        controller?.stopPursuit()
        stopLocationUpdates()
        stopIdlePolling()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        stopForegroundCompat()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun onPursuitInfo(info: PursuitInfo) {
        updateNotificationFromState(info)
        handleVoiceAndAlerts(info)
        // Cuando termina por acción del usuario, detener servicio en primer plano si corresponde.
        if (info.state == PursuitState.STOPPED) {
            stopLocationUpdates()
        }
    }

    private fun updateNotificationFromState(info: PursuitInfo = _state.value) {
        val name = _trackerName.value.ifBlank { info.trackerId ?: "Tracker" }
        val text = when (info.state) {
            PursuitState.IDLE -> "En espera"
            PursuitState.WAITING_FOR_TRACKER -> "Esperando posición de $name…"
            PursuitState.CALCULATING_ROUTE -> "Calculando ruta hacia $name…"
            PursuitState.PURSUING -> "Persiguiendo a $name · ${distText(info)}"
            PursuitState.TRACKER_NEARBY -> "Cerca de $name · ${distText(info)}"
            PursuitState.TRACKER_STATIONARY -> "$name detenido · ${distText(info)}"
            PursuitState.TRACKER_SIGNAL_LOST -> "Sin señal de $name · última posición conservada"
            PursuitState.RECOVERING_CONNECTION -> "Recuperando señal de $name…"
            PursuitState.STOPPED -> "Persecución detenida"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, showStop = info.isActive))
    }

    private fun distText(info: PursuitInfo): String {
        val route = info.routeDistanceMeters
        val straight = info.straightDistanceMeters
        return when {
            route != null -> GeoUtils.formatDistanceMeters(route)
            straight != null -> "${GeoUtils.formatDistanceMeters(straight)} (línea recta)"
            else -> "distancia no disponible"
        }
    }

    private fun handleVoiceAndAlerts(info: PursuitInfo) {
        if (!PursuitPrefs.voiceEnabled(this)) return
        if (!ttsReady) return
        val name = _trackerName.value.ifBlank { info.trackerId ?: "el dispositivo" }

        // Estado de señal
        if (info.state != lastSpokenState) {
            when (info.state) {
                PursuitState.TRACKER_SIGNAL_LOST -> speak("Se ha perdido la señal de $name. Se mantiene la última posición conocida.")
                PursuitState.RECOVERING_CONNECTION, PursuitState.PURSUING -> if (lastSpokenState == PursuitState.TRACKER_SIGNAL_LOST) speak("Se ha recuperado la señal de $name.")
                PursuitState.TRACKER_NEARBY -> speak("Estás cerca de $name. La persecución continúa.")
                PursuitState.TRACKER_STATIONARY -> speak("$name parece detenido. La persecución continúa.")
                else -> {}
            }
            lastSpokenState = info.state
        }

        // Proximidad (alertas configurables con histéresis simple)
        if (PursuitPrefs.proximityAlertsEnabled(this) && info.isActive) {
            val d = info.routeDistanceMeters ?: info.straightDistanceMeters
            if (d != null) {
                val thresholds = listOf(500, 100, 50, 10)
                for (th in thresholds) {
                    val enabled = when (th) {
                        500 -> PursuitPrefs.alert500(this)
                        100 -> PursuitPrefs.alert100(this)
                        50 -> PursuitPrefs.alert50(this)
                        10 -> PursuitPrefs.alert10(this)
                        else -> false
                    }
                    if (!enabled) continue
                    if (d <= th && !firedProximity.contains(th)) {
                        firedProximity.add(th)
                        speak("Aviso de proximidad: $th metros de $name.")
                    } else if (d > th * 1.2) {
                        firedProximity.remove(th) // histéresis
                    }
                }
            }
        }

        // Instrucción de navegación (desde ruta actual, si disponible)
        val step = info.route?.steps?.firstOrNull()?.instruction
        if (!step.isNullOrBlank() && step != lastSpokenInstruction && info.isActive) {
            // Evitar repetir "Has llegado..." como fin (no debe terminar persecución)
            if (!step.startsWith("Has llegado", ignoreCase = true)) {
                speak(step)
                lastSpokenInstruction = step
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "pursuit-${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            try { tts?.language = Locale("es", "ES") } catch (_: Exception) {}
        }
    }

    private var lastUserPosition: UserPosition? = null

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

    private fun stopLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        val up = UserPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            speed = if (location.hasSpeed()) location.speed else null,
            heading = if (location.hasBearing()) location.bearing else null,
            timestampMillis = System.currentTimeMillis(),
        )
        lastUserPosition = up
        controller?.updateUserPosition(up)
    }

    // LocationListener: los métodos obsoletos tienen implementación por defecto en APIs recientes;
    // se omiten overrides explícitos para evitar problemas de firma entre niveles de API.

    private fun stopIdlePolling() {
        idlePollJob?.cancel()
        idlePollJob = null
    }

    private fun stopInternalJobs(clearScope: Boolean) {
        // Limpieza interna del servicio/controlador. En destrucción garantiza que no queden jobs activos.
        try { controller?.stopPursuit() } catch (_: Exception) {}
        idlePollJob?.cancel()
        idlePollJob = null
        stopLocationUpdates()
        if (clearScope) {
            // el scope del servicio se cancela en onDestroy
        }
    }

    private fun buildNotification(text: String, showStop: Boolean = true): Notification {
        val openIntent = Intent(this, MapActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 10, openIntent, piFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Persecución dinámica")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (showStop) {
            val stopIntent = Intent(this, PursuitForegroundService::class.java).apply { action = ACTION_STOP }
            val stopPi = PendingIntent.getService(this, 11, stopIntent, piFlags)
            builder.addAction(0, "Detener persecución", stopPi)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Persecución", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Seguimiento y navegación dinámica del tracker"
            ch.setSound(null, null)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        stopInternalJobs(clearScope = true)
        idlePollJob?.cancel()
        stopLocationUpdates()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_PURSUIT = "com.yqt.tracker.action.PURSUIT_START"
        const val ACTION_STOP = "com.yqt.tracker.action.PURSUIT_STOP"
        const val ACTION_UPDATE_PROFILE = "com.yqt.tracker.action.PURSUIT_PROFILE"
        const val ACTION_SET_VOICE = "com.yqt.tracker.action.PURSUIT_VOICE"
        const val EXTRA_DID = "extra_did"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_PROFILE = "extra_profile"
        const val EXTRA_ENABLED = "extra_enabled"
        private const val CHANNEL_ID = "pursuit_fg"
        private const val NOTIF_ID = 3001

        private val _state = MutableStateFlow(PursuitInfo())
        val state: StateFlow<PursuitInfo> = _state.asStateFlow()
        private val _trackerName = MutableStateFlow("")
        val trackerName: StateFlow<String> = _trackerName.asStateFlow()

        fun startPursuit(context: Context, did: String, name: String, profile: TravelProfile) {
            val i = Intent(context, PursuitForegroundService::class.java).apply {
                action = ACTION_START_PURSUIT
                putExtra(EXTRA_DID, did)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_PROFILE, profile.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, PursuitForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }

        fun updateProfile(context: Context, profile: TravelProfile) {
            val i = Intent(context, PursuitForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROFILE
                putExtra(EXTRA_PROFILE, profile.name)
            }
            context.startService(i)
        }
    }
}
