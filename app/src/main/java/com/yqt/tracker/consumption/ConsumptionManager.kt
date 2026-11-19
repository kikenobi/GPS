package com.yqt.tracker.consumption

import android.content.Context
import com.yqt.tracker.consumption.db.ConsumptionDatabase
import com.yqt.tracker.consumption.db.UsageEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ServiceUsageView(
    val def: ServiceDef,
    val localCount: Double,
    val limit: Int?,
    val percent: Double?,
    val remaining: Double?,
    val estimatedCost: String,
    val syncStatus: String,
    val cloudConfirmed: String,
    val realBilledCost: String,
    val alertLevel: String, // green/orange/red/none
    val blocked: Boolean,
)

object ConsumptionManager {
    private val http = OkHttpClient()

    fun currentPeriodKey(context: Context): String =
        ConsumptionPrefs.currentPeriodKey(startDay = ConsumptionPrefs.billingPeriodStartDay(context))

    suspend fun logEvent(
        context: Context,
        serviceId: String,
        sku: String,
        operationType: String,
        operationId: String = UUID.randomUUID().toString(),
        result: String = "ok",
        billableQuantity: Double = 1.0,
        origin: String,
        metadata: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val def = ServiceCatalog.byId(serviceId) ?: return@withContext false
        val period = currentPeriodKey(context)
        val event = UsageEventEntity(
            timestampMs = System.currentTimeMillis(),
            periodKey = period,
            serviceId = serviceId,
            sku = sku.ifBlank { def.sku },
            operationType = operationType,
            operationId = operationId,
            result = result,
            billableQuantity = billableQuantity,
            origin = origin,
            metadata = metadata,
        )
        val dao = ConsumptionDatabase.get(context).usageEventDao()
        val row = dao.insert(event)
        if (row > 0) {
            ConsumptionPrefs.setLastUpdateMs(context, System.currentTimeMillis())
            true
        } else {
            false // duplicate operationId ignored
        }
    }

    suspend fun getServiceViews(context: Context): List<ServiceUsageView> = withContext(Dispatchers.IO) {
        val dao = ConsumptionDatabase.get(context).usageEventDao()
        val period = currentPeriodKey(context)
        val sync = ConsumptionPrefs.lastSyncStatus(context)
        ServiceCatalog.services.map { def ->
            val count = dao.sumOkForService(def.id, period)
            val limit = ConsumptionPrefs.monthlyLimit(context, def.id, def.defaultFreeLimit)
            val percent = if (limit != null && limit > 0) (count / limit.toDouble()) * 100.0 else null
            val remaining = if (limit != null) (limit.toDouble() - count) else null
            val level = when {
                percent == null -> "none"
                percent > 90.0 -> "red"
                percent >= 80.0 -> "orange"
                else -> "green"
            }
            val blocked = shouldBlock(context, def.id, count, limit)
            ServiceUsageView(
                def = def,
                localCount = count,
                limit = limit,
                percent = percent,
                remaining = remaining,
                estimatedCost = "No disponible (sin tarifa oficial verificada/configurada en esta versión)",
                syncStatus = sync,
                cloudConfirmed = "No disponible",
                realBilledCost = "No disponible (consultar facturación en Google Cloud; puede tener retraso)",
                alertLevel = level,
                blocked = blocked,
            )
        }
    }

    fun shouldBlock(context: Context, serviceId: String, localCount: Double, limit: Int?): Boolean {
        if (!ConsumptionPrefs.blockAt100(context, serviceId)) return false
        if (ConsumptionPrefs.manualException(context, serviceId)) return false
        if (limit == null || limit <= 0) return false
        return localCount >= limit.toDouble()
    }

    suspend fun listPeriods(context: Context): List<String> = withContext(Dispatchers.IO) {
        ConsumptionDatabase.get(context).usageEventDao().listPeriods()
    }

    suspend fun recentEvents(context: Context, limit: Int = 200) = withContext(Dispatchers.IO) {
        ConsumptionDatabase.get(context).usageEventDao().recent(limit)
    }

    fun formatTs(ms: Long): String {
        if (ms <= 0) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    suspend fun syncCloud(context: Context): String = withContext(Dispatchers.IO) {
        val url = ConsumptionPrefs.cloudBackendUrl(context)
        if (url.isBlank()) {
            val msg = "Sincronización oficial no configurada. Consulta manual: Google Cloud Console > Facturación/Informes y APIs y servicios/Métricas (según disponibilidad). Los datos pueden tener retraso (p. ej. ~48 h) y no todos los SKU exponen métricas en tiempo real."
            ConsumptionPrefs.setLastSyncStatus(context, msg)
            ConsumptionPrefs.setLastUpdateMs(context, System.currentTimeMillis())
            return@withContext msg
        }
        if (!url.startsWith("https://")) {
            val msg = "URL de backend no válida: debe ser HTTPS. No se han consultado datos oficiales."
            ConsumptionPrefs.setLastSyncStatus(context, msg)
            ConsumptionPrefs.setLastUpdateMs(context, System.currentTimeMillis())
            return@withContext msg
        }
        try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = "Error al consultar backend oficial (HTTP ${resp.code}). Mostrando solo datos locales."
                    ConsumptionPrefs.setLastSyncStatus(context, msg)
                } else {
                    // Contrato esperado (documentado): JSON con campos como status/message/services[].{id,cloudConfirmed,realBilledCost,estimatedCost,periodStart,lastUpdate}
                    // Si no cumple, se informa y se mantienen datos locales.
                    try {
                        val json = JSONObject(body)
                        val status = json.optString("status", "ok")
                        val message = json.optString("message", "Respuesta recibida del backend configurado.")
                        val msg = "Backend: ${if (status.isBlank()) "ok" else status} — $message (interpretación completa según contrato pendiente de datos oficiales por SKU)."
                        ConsumptionPrefs.setLastSyncStatus(context, msg)
                    } catch (_: Exception) {
                        ConsumptionPrefs.setLastSyncStatus(context, "Respuesta del backend recibida pero no reconocida. Mostrando solo datos locales.")
                    }
                }
            }
        } catch (e: Exception) {
            ConsumptionPrefs.setLastSyncStatus(context, "No se pudo contactar con el backend oficial: ${e.message}. Mostrando solo datos locales.")
        }
        ConsumptionPrefs.setLastUpdateMs(context, System.currentTimeMillis())
        ConsumptionPrefs.lastSyncStatus(context)
    }
}