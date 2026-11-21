package com.yqt.tracker.tracking

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Abstracción de acceso a posiciones de trackers.
 * Implementaciones deben reutilizar la integración YQT existente (cliente, auth, sesión y modelos)
 * sin duplicar llamadas innecesarias ni alterar el comportamiento actual del tracking.
 */
interface TrackerPositionProvider {
    /**
     * Obtiene la última posición conocida/disponible del tracker.
     * @param requestFresh si es true, solicita actualización al dispositivo (p.ej. orden de posición)
     * antes de leer la última posición, cuando el origen lo soporte.
     */
    suspend fun getPosition(trackerId: String, requestFresh: Boolean = false): TrackerPosition?

    /**
     * Observa posiciones del tracker emitiendo actualizaciones periódicas.
     * La implementación debe controlar concurrencia (sin solapar consultas), respetar timeouts/errores
     * y aplicar espera entre ciclos según [interval].
     */
    fun observePosition(
        trackerId: String,
        interval: Duration = 1.seconds,
        requestFresh: Boolean = true,
    ): Flow<TrackerPosition>
}
