package com.yqt.tracker.tracking

/**
 * Posición normalizada de un tracker obtenida a través de YQT-Smart-API (capa de dominio),
 * desacoplada de los DTO/modelos internos de red para uso por el controlador de persecución.
 */
data class TrackerPosition(
    val trackerId: String,
    val latitude: Double,
    val longitude: Double,
    /** Velocidad reportada por la API (unidades tal como las entrega el origen), si disponible. */
    val speed: Double? = null,
    /** Rumbo/dirección reportada por la API, si disponible. */
    val heading: Double? = null,
    /** Instante asociado a la posición (ms desde epoch), si puede determinarse; en otro caso instante de recepción. */
    val timestampMillis: Long,
    /** Instante local en que se recibió/observó esta posición (ms desde epoch). */
    val receivedAtMillis: Long = timestampMillis,
    val accuracyMeters: Double? = null,
    val locationType: String? = null,
    val isEstimated: Boolean = false,
)