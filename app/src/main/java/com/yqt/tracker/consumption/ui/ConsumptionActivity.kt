package com.yqt.tracker.consumption.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.yqt.tracker.R
import com.yqt.tracker.consumption.ConsumptionManager
import com.yqt.tracker.consumption.ConsumptionPrefs
import com.yqt.tracker.consumption.ServiceUsageView
import com.yqt.tracker.databinding.ActivityConsumptionBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConsumptionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConsumptionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsumptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.refreshButton.setOnClickListener { refresh(syncCloud = true) }
        binding.settingsButton.setOnClickListener { showGlobalSettings() }
        refresh(syncCloud = ConsumptionPrefs.autoSyncEnabled(this))
    }

    override fun onResume() {
        super.onResume()
        // refresh local view when returning
        refresh(syncCloud = false)
    }

    private fun refresh(syncCloud: Boolean) {
        lifecycleScope.launch {
            binding.syncStatusText.text = if (syncCloud) "Actualizando / sincronizando…" else "Actualizando datos locales…"
            if (syncCloud) {
                val msg = ConsumptionManager.syncCloud(this@ConsumptionActivity)
                binding.syncStatusText.text = "Estado de sincronización: $msg"
            } else {
                binding.syncStatusText.text = "Estado de sincronización: ${ConsumptionPrefs.lastSyncStatus(this@ConsumptionActivity)}"
            }
            val views = ConsumptionManager.getServiceViews(this@ConsumptionActivity)
            renderViews(views)
            renderHistoryAndBreakdown()
            val last = ConsumptionPrefs.lastUpdateMs(this@ConsumptionActivity)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.lastUpdateText.text = getString(R.string.consumption_last_update, if (last > 0) fmt.format(Date(last)) else "—")
            val periodKey = ConsumptionManager.currentPeriodKey(this@ConsumptionActivity)
            val start = ConsumptionPrefs.periodStartLabel(startDay = ConsumptionPrefs.billingPeriodStartDay(this@ConsumptionActivity))
            binding.periodText.text = "Periodo actual (lógico): $periodKey · ${getString(R.string.consumption_period_start, start)}"
            renderDocs()
        }
    }

    private fun renderViews(views: List<ServiceUsageView>) {
        binding.cardsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (v in views) {
            val card = inflater.inflate(R.layout.item_consumption_card, binding.cardsContainer, false) as MaterialCardView
            card.findViewById<TextView>(R.id.serviceName).text = v.def.name + if (v.def.usedByApp) " (en uso)" else " (no utilizado)"
            card.findViewById<TextView>(R.id.skuText).text = "SKU: ${v.def.sku}"
            val unit = v.def.defaultUnitLabel
            val limitText = if (v.limit != null) "${fmtNum(v.localCount)} / ${v.limit} $unit" else "${fmtNum(v.localCount)} $unit (límite gratuito no definido: verificar precios vigentes)"
            card.findViewById<TextView>(R.id.usageText).text = "Consumo local (mes actual): $limitText"

            val pb = card.findViewById<ProgressBar>(R.id.progressBar)
            if (v.percent != null) {
                val p = v.percent.coerceIn(0.0, 100.0).toInt()
                pb.progress = p
                val color = when (v.alertLevel) {
                    "red" -> Color.parseColor("#DC2626")
                    "orange" -> Color.parseColor("#F59E0B")
                    "green" -> Color.parseColor("#16A34A")
                    else -> Color.parseColor("#6B7280")
                }
                pb.progressDrawable?.setTint(color)
                card.findViewById<TextView>(R.id.percentText).text = String.format(Locale.getDefault(), "%.1f %% consumido (según límite configurado/applicable)", v.percent)
            } else {
                pb.progress = 0
                card.findViewById<TextView>(R.id.percentText).text = "Porcentaje no disponible: configura un límite aplicable o verifica el límite gratuito vigente del SKU."
            }

            val rem = when {
                v.remaining == null -> "Restante antes del límite: no disponible"
                v.remaining >= 0 -> "Restante antes del límite: ${fmtNum(v.remaining)} $unit"
                else -> "Exceso sobre el límite configurado: ${fmtNum(-v.remaining)} $unit"
            }
            card.findViewById<TextView>(R.id.remainingText).text = rem

            card.findViewById<TextView>(R.id.costText).text =
                "Coste estimado: ${v.estimatedCost}\nConsumo confirmado Google Cloud: ${v.cloudConfirmed}\nCoste real facturado: ${v.realBilledCost}"

            val blockNote = if (v.blocked) " · BLOQUEO PREVENTIVO ACTIVO (límite alcanzado)" else ""
            card.findViewById<TextView>(R.id.statusText).text = "Estado de sincronización: ${v.syncStatus}$blockNote"

            card.findViewById<TextView>(R.id.noteText).text = v.def.notes + if (!v.def.usedByApp) "\nServicio no utilizado actualmente por esta versión de la app." else ""

            card.findViewById<MaterialButton>(R.id.configButton).setOnClickListener { showServiceConfig(v) }
            card.findViewById<MaterialButton>(R.id.detailButton).setOnClickListener { showServiceDetail(v) }

            binding.cardsContainer.addView(card)
        }
    }

    private fun fmtNum(v: Double): String {
        return if (Math.abs(v - Math.rint(v)) < 0.0001) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.2f", v)
    }

    private fun renderHistoryAndBreakdown() {
        lifecycleScope.launch {
            val periods = ConsumptionManager.listPeriods(this@ConsumptionActivity)
            binding.historyText.text = if (periods.isEmpty()) "Sin histórico local todavía." else periods.joinToString("\n") { "• $it" }

            val events = ConsumptionManager.recentEvents(this@ConsumptionActivity, 80)
            if (events.isEmpty()) {
                binding.breakdownText.text = "Sin eventos locales registrados."
            } else {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                binding.breakdownText.text = events.joinToString("\n") { e ->
                    "• ${fmt.format(Date(e.timestampMs))} · ${e.serviceId} · ${e.operationType} · ${e.result} · cant=${fmtNum(e.billableQuantity)} · origen=${e.origin}"
                }
            }
        }
    }

    private fun renderDocs() {
        binding.docsText.text = """
            |Documentación / significado de contadores:
            |• Consumo local (APK): eventos facturables registrados por esta app. Es orientativo y puede no coincidir con la facturación oficial.
            |• Consumo confirmado por Google Cloud: requiere sincronización con una fuente oficial (no disponible directamente desde la APK sin credenciales privilegiadas). Puede configurarse un backend HTTPS propio.
            |• Estimación de coste: depende de SKU, tarifas vigentes y modalidad; no se calcula como dato oficial en esta versión salvo fuente verificada.
            |• Coste real facturado: consultar Google Cloud Billing/Informes. Puede tener retraso (p. ej. hasta ~48 h).
            |• Servicios usados actualmente: Maps SDK for Android (visualización de mapa). Rutas: OSRM (no Google Routes). No se usan Navigation SDK, Places ni Geocoding de Google.
            |• No se contabilizan como consumo de Google: actualizaciones GPS del móvil/reloj, movimiento de marcadores/cámara/bearing/tilt, TTS ni redibujado de una ruta ya calculada. En “Perseguir”, las actualizaciones del reloj cada 3 s no generan solicitudes Google registradas.
            |• PERSEGUIR: el recálculo de ruta (OSRM) se limita por distancia/tiempo mínimos configurables para evitar trabajo redundante; no implica solicitudes facturables a Google Maps Platform.
            |• Navigation SDK (futuro): migración no realizada. Requiere evaluar integración de navegación giro a giro, voz/reruteo de Google, destino dinámico y qué operaciones generan el SKU “Navigation Request” frente a actualizaciones visuales del marcador (no facturables como nueva solicitud de destino). Cualquier migración debe planificarse sin romper OSRM/funcionalidad actual.
            |• Consulta manual sugerida: https://console.cloud.google.com/ (Facturación > Informes; APIs y servicios; Google Maps Platform). Precios vigentes: https://mapsplatform.google.com/pricing/ y https://developers.google.com/maps/billing-and-pricing/pricing
        """.trimMargin()
    }

    private fun showServiceDetail(v: ServiceUsageView) {
        val msg = "Servicio: ${v.def.name}\nSKU: ${v.def.sku}\nEn uso por la app: ${if (v.def.usedByApp) "Sí" else "No"}\n\n${v.def.description}\n\nNotas: ${v.def.notes}\n\nConsumo local periodo: ${fmtNum(v.localCount)} ${v.def.defaultUnitLabel}\nConfirmado Google Cloud: ${v.cloudConfirmed}\nCoste real: ${v.realBilledCost}\nEstado sync: ${v.syncStatus}"
        AlertDialog.Builder(this).setTitle("Detalle de servicio").setMessage(msg).setPositiveButton("Cerrar", null).show()
    }

    private fun showServiceConfig(v: ServiceUsageView) {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun label(t: String) = TextView(ctx).apply { text = t; textSize = 13f }
        fun field(hint: String, value: String, number: Boolean = false): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }

        val limitInput = field("Límite mensual propio (vacío = usar predeterminado/configuración; 0 = sin límite)", ConsumptionPrefs.monthlyLimit(ctx, v.def.id, v.def.defaultFreeLimit)?.toString() ?: "", true)
        val warnText = TextView(ctx).apply { text = "Alertas activas: 80%=${ConsumptionPrefs.warn80(ctx, v.def.id)}, 90%=${ConsumptionPrefs.warn90(ctx, v.def.id)}, 95%=${ConsumptionPrefs.warn95(ctx, v.def.id)} (se aplican según porcentaje sobre límite)."; textSize = 12f }
        val blockCheck = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply { text = "Bloqueo preventivo al 100% (si hay límite)"; isChecked = ConsumptionPrefs.blockAt100(ctx, v.def.id) }
        val exceptCheck = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply { text = "Excepción manual: permitir continuar aunque esté bloqueado"; isChecked = ConsumptionPrefs.manualException(ctx, v.def.id) }

        layout.addView(label("Configuración: ${v.def.name}"))
        layout.addView(limitInput)
        layout.addView(warnText)
        layout.addView(blockCheck)
        layout.addView(exceptCheck)
        layout.addView(TextView(ctx).apply { text = "Nota: los límites gratuitos de Google y los límites propios/Cloud son conceptos distintos. El contador local no garantiza evitar cargos."; textSize = 12f; setPadding(0,8,0,0) })

        AlertDialog.Builder(ctx)
            .setTitle("Configurar servicio")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val limStr = limitInput.text?.toString()?.trim().orEmpty()
                val lim = limStr.toIntOrNull()
                ConsumptionPrefs.setMonthlyLimit(ctx, v.def.id, lim)
                // keep default warnings enabled (spec suggests 80/90/95) - toggles could be added; keep simple and compliant
                ConsumptionPrefs.setWarn(ctx, v.def.id, true, true, true)
                ConsumptionPrefs.setBlockAt100(ctx, v.def.id, blockCheck.isChecked)
                ConsumptionPrefs.setManualException(ctx, v.def.id, exceptCheck.isChecked)
                Toast.makeText(ctx, "Configuración guardada", Toast.LENGTH_SHORT).show()
                refresh(syncCloud = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGlobalSettings() {
        val ctx = this
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }
        fun field(hint: String, value: String, number: Boolean = false) = EditText(ctx).apply {
            this.hint = hint; setText(value)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        }
        val url = field("URL backend oficial (HTTPS, opcional)", ConsumptionPrefs.cloudBackendUrl(ctx))
        val auto = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply { text = "Sincronización automática al abrir esta pantalla"; isChecked = ConsumptionPrefs.autoSyncEnabled(ctx) }
        val day = field("Día de inicio de periodo de facturación (1-28)", ConsumptionPrefs.billingPeriodStartDay(ctx).toString(), true)
        val dist = field("Distancia mínima recálculo ruta en PERSEGUIR (m)", ConsumptionPrefs.rerouteMinDistanceMeters(ctx).toInt().toString(), true)
        val secs = field("Tiempo mínimo entre recálculos en PERSEGUIR (s)", (ConsumptionPrefs.rerouteMinIntervalMs(ctx)/1000L).toString(), true)

        layout.addView(TextView(ctx).apply { text = "Conexión con Google Cloud: ${if (ConsumptionPrefs.cloudBackendUrl(ctx).isBlank()) "No configurada (solo local / consulta manual)" else "Backend configurado (sin credenciales en la APK)"}"; textSize = 13f })
        layout.addView(url)
        layout.addView(auto)
        layout.addView(day)
        layout.addView(TextView(ctx).apply { text = "Umbrales de recálculo de ruta (OSRM, no facturable en Google):"; textSize = 13f; setPadding(0,12,0,0) })
        layout.addView(dist)
        layout.addView(secs)
        layout.addView(TextView(ctx).apply { text = "Proyecto/estado de servicios Google Cloud: configurable mediante backend propio si se dispone. Sin backend, consulta manual en Google Cloud Console. La clave de Maps SDK se gestiona por secrets.properties/MAPS_API_KEY y restricciones en Google Cloud (no se modifica aquí)."; textSize = 12f; setPadding(0,12,0,0) })

        AlertDialog.Builder(ctx)
            .setTitle("Configuración")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                ConsumptionPrefs.setCloudBackendUrl(ctx, url.text?.toString().orEmpty())
                ConsumptionPrefs.setAutoSyncEnabled(ctx, auto.isChecked)
                ConsumptionPrefs.setBillingPeriodStartDay(ctx, day.text?.toString()?.toIntOrNull() ?: 1)
                ConsumptionPrefs.setRerouteMinDistanceMeters(ctx, (dist.text?.toString()?.toFloatOrNull() ?: 40f).coerceAtLeast(5f))
                val s = (secs.text?.toString()?.toLongOrNull() ?: 8L).coerceAtLeast(2L)
                ConsumptionPrefs.setRerouteMinIntervalMs(ctx, s * 1000L)
                Toast.makeText(ctx, "Configuración guardada", Toast.LENGTH_SHORT).show()
                refresh(syncCloud = auto.isChecked)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}