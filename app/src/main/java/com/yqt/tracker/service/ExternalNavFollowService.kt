package com.yqt.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.yqt.tracker.R
import com.yqt.tracker.network.Protocol
import com.yqt.tracker.network.YqtClient
import com.yqt.tracker.ui.MapActivity
import com.yqt.tracker.util.GeoUtils
import com.yqt.tracker.util.Prefs
import kotlinx.coroutines.*

class ExternalNavFollowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var client: YqtClient? = null

    @Volatile private var targetDid: String? = null
    @Volatile private var targetName: String = ""
    @Volatile private var lastSentLat: Double? = null
    @Volatile private var lastSentLng: Double? = null
    @Volatile private var lastSentAt: Long = 0L
    @Volatile private var lastKnownLat: Double? = null
    @Volatile private var lastKnownLng: Double? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val did = intent?.getStringExtra(EXTRA_DID) ?: targetDid
                val name = intent?.getStringExtra(EXTRA_NAME) ?: targetName
                if (did.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (did != targetDid) {
                    lastSentLat = null
                    lastSentLng = null
                    lastSentAt = 0L
                    lastKnownLat = null
                    lastKnownLng = null
                }
                targetDid = did
                targetName = name.ifBlank { did }
                startForegroundServiceInternal()
                startLoop()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        createChannel()
        val notification = buildNotification("Iniciando seguimiento de $targetName…")
        startForeground(NOTIF_ID, notification)
    }

    private fun startLoop() {
        job?.cancel()
        job = scope.launch {
            ensureClient()
            while (isActive) {
                try {
                    tick()
                } catch (_: Exception) {
                }
                delay(3000L)
            }
        }
    }

    private fun ensureClient() {
        if (client != null) return
        val creds = Prefs.load(this) ?: return
        val c = YqtClient(this, creds.first, creds.second, creds.third)
        try {
            c.ensureLoggedIn()
            c.discoverDevices()
        } catch (_: Exception) {
        }
        client = c
    }

    private suspend fun tick() {
        val did = targetDid ?: return
        val c = client ?: run { ensureClient(); client } ?: return
        try { c.ensureLoggedIn() } catch (_: Exception) { return }
        val info = c.deviceIndex[did]
        val didId = (info?.get("did_id") ?: info?.get("didId") ?: "").toString()
        val name = ((info?.get("nickname") ?: info?.get("rolename") ?: info?.get("name") ?: targetName).toString()).ifBlank { targetName }
        targetName = name
        try { c.freshPosition(did) } catch (_: Exception) { }

        val resp = try { c.findLastPosition(did, didId) } catch (_: Exception) { null } ?: return
        val parsed = Protocol.parseLastPosition(resp)
        val lat = parsed.lat ?: return
        val lng = parsed.lng ?: return

        val prevKnownLat = lastKnownLat
        val prevKnownLng = lastKnownLng
        val movedFromKnown = if (prevKnownLat != null && prevKnownLng != null) {
            GeoUtils.haversineMeters(prevKnownLat, prevKnownLng, lat, lng)
        } else {
            Double.MAX_VALUE
        }
        if (prevKnownLat == null || prevKnownLng == null || movedFromKnown > 1.0) {
            playPositionSound()
        }
        lastKnownLat = lat
        lastKnownLng = lng

        val now = System.currentTimeMillis()
        val prevLat = lastSentLat
        val prevLng = lastSentLng
        val moved = if (prevLat != null && prevLng != null) GeoUtils.haversineMeters(prevLat, prevLng, lat, lng) else Double.MAX_VALUE
        val minMove = 30.0
        val minInterval = 15000L
        val shouldSend = (prevLat == null || prevLng == null) || (moved >= minMove && (now - lastSentAt) >= minInterval)

        if (shouldSend) {
            openExternalNavigation(lat, lng)
            lastSentLat = lat
            lastSentLng = lng
            lastSentAt = now
        } else {
            updateNotification("Siguiendo: $name · sin cambios significativos")
        }
    }

    private fun openExternalNavigation(lat: Double, lng: Double) {
        val coordText = String.format("%.5f, %.5f", lat, lng)
        val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var opened = false
        var mode = "Google Maps"
        try {
            startActivity(intent)
            opened = true
        } catch (_: Exception) {
            try {
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving&dir_action=navigate")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(webIntent)
                opened = true
                mode = "navegador"
            } catch (_: Exception) {
                opened = false
            }
        }
        val msg = if (opened) {
            "Abriendo $mode con destino: $coordText"
        } else {
            "No se pudo abrir Google Maps/navegador para: $coordText"
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
        updateNotification(msg)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MapActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val stopIntent = Intent(this, ExternalNavFollowService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.external_follow_notification_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_follow), stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Persecución externa", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Seguimiento del reloj y apertura de Google Maps"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yqt.tracker.action.EXTERNAL_FOLLOW_START"
        const val ACTION_STOP = "com.yqt.tracker.action.EXTERNAL_FOLLOW_STOP"
        const val EXTRA_DID = "extra_did"
        const val EXTRA_NAME = "extra_name"
        private const val CHANNEL_ID = "external_follow"
        private const val NOTIF_ID = 2001

        fun start(context: Context, did: String, name: String) {
            val i = Intent(context, ExternalNavFollowService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DID, did)
                putExtra(EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ExternalNavFollowService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}