package com.yqt.tracker.consumption

data class ServiceDef(
    val id: String,
    val name: String,
    val sku: String,
    val description: String,
    val usedByApp: Boolean,
    val defaultFreeLimit: Int?,
    val defaultUnitLabel: String,
    val notes: String,
)

object ServiceCatalog {
    // Límites/SKU orientativos: verificar siempre en la documentación y precios vigentes de Google Maps Platform.
    val services: List<ServiceDef> = listOf(
        ServiceDef(
            id = "maps_sdk_android",
            name = "Maps SDK for Android",
            sku = "Dynamic Maps / Map Loads (según modalidad y precios vigentes)",
            description = "Visualización del mapa dinámico en la app (Google Maps).",
            usedByApp = true,
            defaultFreeLimit = null, // depende del SKU/modalidad y de los términos vigentes; configurable por el usuario
            defaultUnitLabel = "sesiones/eventos (estimación local)",
            notes = "La app usa Google Maps SDK para mostrar el mapa. El conteo local es una estimación de sesiones/eventos de visualización y NO equivale a eventos facturables oficiales. Consulta Google Cloud Console para consumo confirmado.",
        ),
        ServiceDef(
            id = "routes_api",
            name = "Routes API",
            sku = "Routes (Compute Routes, etc.)",
            description = "Cálculo de rutas mediante la API de Google.",
            usedByApp = false,
            defaultFreeLimit = 10000,
            defaultUnitLabel = "solicitudes",
            notes = "No utilizado actualmente: la app calcula rutas con OSRM.",
        ),
        ServiceDef(
            id = "navigation_sdk",
            name = "Navigation SDK",
            sku = "Navigation Requests / sesiones (según términos vigentes)",
            description = "Navegación giro a giro de Google.",
            usedByApp = false,
            defaultFreeLimit = 1000,
            defaultUnitLabel = "destinos/solicitudes",
            notes = "No integrado. La navegación actual es propia sobre OSRM + Google Maps. Ver documentación del módulo antes de migrar.",
        ),
        ServiceDef(
            id = "places_api",
            name = "Places API",
            sku = "Places (Autocomplete, Details, etc.)",
            description = "Búsqueda y detalles de lugares.",
            usedByApp = false,
            defaultFreeLimit = null,
            defaultUnitLabel = "solicitudes",
            notes = "No utilizado actualmente por la app.",
        ),
        ServiceDef(
            id = "geocoding_api",
            name = "Geocoding API",
            sku = "Geocoding / Reverse Geocoding",
            description = "Conversión entre direcciones y coordenadas.",
            usedByApp = false,
            defaultFreeLimit = null,
            defaultUnitLabel = "solicitudes",
            notes = "No utilizado actualmente por la app.",
        ),
    )

    fun byId(id: String): ServiceDef? = services.firstOrNull { it.id == id }
}