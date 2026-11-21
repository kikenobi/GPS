package com.yqt.tracker.tracking

import com.yqt.tracker.network.Protocol
import com.yqt.tracker.network.YqtClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Implementación de [TrackerPositionProvider] sobre [YqtClient], reutilizando autenticación,
 * resolución de dispositivos, endpoints y parseo existentes de YQT-Smart-API.
 *
 * No modifica el comportamiento del tracking actual; encapsula las mismas operaciones
 * (freshPosition/findLastPosition) para consumidores nuevos como el controlador de persecución.
 */
class YqtTrackerPositionProvider(
    private val client: YqtClient,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) : TrackerPositionProvider {

    override suspend fun getPosition(trackerId: String, requestFresh: Boolean): TrackerPosition? =
        withContext(Dispatchers.IO) {
            fetchOnce(trackerId, requestFresh)
        }

    override fun observePosition(
        trackerId: String,
        interval: Duration,
        requestFresh: Boolean,
    ): Flow<TrackerPosition> = flow {
        val intervalMs = max(200L, interval.inWholeMilliseconds)
        var backoffMs = intervalMs
        val maxBackoffMs = max(intervalMs, 30_000L)

        while (currentCoroutineContext().isActive) {
            val cycleStart = timeProvider()
            try {
                val pos = withContext(Dispatchers.IO) { fetchOnce(trackerId, requestFresh) }
                if (pos != null) {
                    emit(pos)
                    backoffMs = intervalMs
                } else {
                    // Sin posición válida en esta lectura: no emitir; mantener cadencia/espera.
                    backoffMs = intervalMs
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Error de red/API/parseo: aplicar backoff sin solapar consultas.
                backoffMs = minOf(maxBackoffMs, max(intervalMs, backoffMs * 2))
            }

            val elapsed = timeProvider() - cycleStart
            val wait = max(0L, backoffMs - elapsed)
            if (wait > 0) delay(wait)
        }
    }

    private fun fetchOnce(trackerId: String, requestFresh: Boolean): TrackerPosition? {
        client.ensureLoggedIn()
        ensureDeviceKnown(trackerId)

        val infoMap = client.deviceIndex[trackerId]
        val info = infoMap?.let { client.toWatchInfo(it) }
        val didId = info?.didId ?: (infoMap?.get("did_id") ?: infoMap?.get("didId"))?.toString().orEmpty()

        if (requestFresh) {
            try {
                // Reutiliza resolución de modelo y comando D3 existentes.
                client.freshPosition(trackerId)
            } catch (_: Exception) {
                // No impedir la lectura de la última posición conocida si el comando falla.
            }
        }

        val resp = try {
            client.findLastPosition(trackerId, didId)
        } catch (_: Exception) {
            return null
        }
        val parsed = Protocol.parseLastPosition(resp)
        val lat = parsed.lat ?: return null
        val lng = parsed.lng ?: return null
        val now = timeProvider()
        return TrackerPosition(
            trackerId = trackerId,
            latitude = lat,
            longitude = lng,
            speed = parsed.speed,
            heading = parsed.direction,
            timestampMillis = now,
            receivedAtMillis = now,
            accuracyMeters = parsed.accuracy,
            locationType = parsed.locationType,
            isEstimated = false,
        )
    }

    private fun ensureDeviceKnown(trackerId: String) {
        val known = client.deviceIndex[trackerId]
        if (known != null) return
        try {
            client.discoverDevices()
        } catch (_: Exception) {
            // Si no se puede descubrir, se intentará igualmente con lo disponible.
        }
    }
}
