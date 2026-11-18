package com.yqt.tracker.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Protocol {
    const val DEFAULT_LANGUAGE = "enUS"
    const val DEFAULT_APP_ID = "aaagg11145"
    const val DEFAULT_CLIENT_FLAG = 394
    const val DEFAULT_CLIENT_VERSION = "1.0.2"
    const val DEFAULT_IS_IPHONE = 1
    const val DEFAULT_SIGN_FLAG = "KHDIW"
    const val SIGN_PREFIX = "SECRPRO"

    val REGIONS = linkedMapOf(
        "europe" to "https://europe.myaqsh.com:11001",
        "asia" to "https://asia.myaqsh.com:11001",
        "northam" to "https://northam.myaqsh.com:11001",
        "southam" to "https://southam.myaqsh.com:11001",
        "hk" to "https://hk.myaqsh.com:11001",
        "vie" to "https://vie.myaqsh.com:11001",
        "russ" to "https://russ.myaqsh.com:11001",
    )

    private fun md5Hex(value: String): String {
        val md = MessageDigest.getInstance("MD5")
        val dig = md.digest(value.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(value.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(password: String): String = sha256Hex(md5Hex(password))

    fun computeSign(params: Map<String, Any?>): String {
        val filtered = linkedMapOf<String, String>()
        params.forEach { (k, v) ->
            if (v != null && k != "sign") filtered[k] = v.toString()
        }
        val sortedKeys = filtered.keys.sorted()
        val sb = StringBuilder()
        sb.append(SIGN_PREFIX)
        for (k in sortedKeys) {
            sb.append(k).append(filtered[k])
        }
        sb.append(SIGN_PREFIX)
        val message = sb.toString()
        return sha256Hex(md5Hex(md5Hex(md5Hex(message)))).lowercase(Locale.ROOT)
    }

    fun signedParams(params: Map<String, Any?>, signFlag: String = DEFAULT_SIGN_FLAG): LinkedHashMap<String, String> {
        val normalized = LinkedHashMap<String, String>()
        params.forEach { (k, v) -> if (v != null) normalized[k] = v.toString() }
        if (!normalized.containsKey("timestamppp")) normalized["timestamppp"] = System.currentTimeMillis().toString()
        if (!normalized.containsKey("sign_flag")) normalized["sign_flag"] = signFlag
        normalized["sign"] = computeSign(normalized)
        return normalized
    }

    // quote_plus with safe="" similar to Python: space -> +, unreserved kept (-_.~ alnum), rest percent-encoded
    fun quotePlus(s: String): String {
        val out = StringBuilder()
        val bytes = s.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val unreserved = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_' || ch == '~'
            when {
                unreserved -> out.append(ch)
                ch == ' ' -> out.append('+')
                else -> out.append(String.format("%%%02X", c))
            }
        }
        return out.toString()
    }

    fun isLoginTimeout(payload: JSONObject): Boolean {
        val status = payload.optInt("status", Int.MIN_VALUE)
        if (status == 607) return true
        val msg = payload.optString("message", "").lowercase(Locale.ROOT)
        return msg.contains("login timeout")
    }

    fun parseDidMapping(value: String?): Map<String, String> {
        if (value.isNullOrEmpty()) return emptyMap()
        val map = linkedMapOf<String, String>()
        value.split(",").forEach { item ->
            if (item.isBlank() || !item.contains("-")) return@forEach
            val idx = item.indexOf('-')
            val did = item.substring(0, idx)
            val payload = item.substring(idx + 1)
            if (did.isNotEmpty()) map[did] = payload
        }
        return map
    }

    fun didOrder(value: String?): List<String> = parseDidMapping(value).keys.toList()

    fun buildWatchIndex(payload: JSONObject, userId: Int? = null): Map<String, MutableMap<String, Any?>> {
        fun metaValue(key: String): String {
            val top = payload.optString(key, "")
            if (top.isNotEmpty() && top != "null") return top
            val data = payload.opt("data")
            if (data is JSONArray && data.length() > 0) {
                val row = data.optJSONObject(0)
                if (row != null) {
                    val v = row.optString(key, "")
                    if (v.isNotEmpty() && v != "null") return v
                }
            }
            return ""
        }

        val didNames = parseDidMapping(metaValue("didstr").ifEmpty { null })
        val didRoles = parseDidMapping(metaValue("didrole").ifEmpty { null })
        val didTypes = parseDidMapping(metaValue("didtype").ifEmpty { null })
        val didEsim = parseDidMapping(metaValue("isEsim").ifEmpty { null })
        val didIds = parseDidMapping(metaValue("total_did_id").ifEmpty { null })
        val didModels = parseDidMapping(metaValue("total_did_model").ifEmpty { null })
        val didConfigs = parseDidMapping(metaValue("total_did_config").ifEmpty { null })

        val order = didOrder(metaValue("didstr").ifEmpty { null }).ifEmpty { didOrder(metaValue("total_did_id").ifEmpty { null }) }
        val out = linkedMapOf<String, MutableMap<String, Any?>>()
        for (did in order) {
            out[did] = mutableMapOf(
                "did" to did,
                "did_id" to (didIds[did] ?: ""),
                "model" to (didModels[did] ?: ""),
                "nickname" to (didNames[did] ?: ""),
                "rolename" to (didRoles[did] ?: ""),
                "user_id" to userId,
                "config" to (didConfigs[did] ?: ""),
                "is_esim" to (didEsim[did] ?: ""),
                "type" to (didTypes[did] ?: ""),
            )
        }
        return out
    }

    fun coerceInt(v: Any?): Int? = when (v) {
        null -> null
        is Int -> v
        is Number -> v.toInt()
        is String -> if (v.isBlank()) null else v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun coerceDouble(v: Any?): Double? = when (v) {
        null -> null
        is Double -> v
        is Number -> v.toDouble()
        is String -> if (v.isBlank()) null else v.toDoubleOrNull()
        else -> null
    }

    fun coerceAccuracy(v: Any?): Double? {
        val p = coerceDouble(v) ?: return null
        return if (p <= 0) null else p
    }

    fun parsePositionDate(v: Any?): String? {
        val s = v as? String ?: return null
        if (s.isBlank()) return null
        return s // keep as-is for display; it's device/server time
    }

    fun extractAddress(row: JSONObject): String? {
        val a = row.optString("address", "")
        if (a.isNotEmpty()) return a
        val r = row.optString("replacePosition", "")
        return if (r.isNotEmpty()) r else null
    }

    data class ParsedPosition(
        val lat: Double?,
        val lng: Double?,
        val battery: Int?,
        val positionDate: String?,
        val address: String?,
        val speed: Double?,
        val direction: Double?,
        val accuracy: Double?,
        val pollStatus: Int?,
        val pollMessage: String?,
        val raw: JSONObject?,
    )

    fun parseLastPosition(response: JSONObject): ParsedPosition {
        val status = coerceInt(response.opt("status"))
        val message = response.optString("message", "")
        val data = response.opt("data")
        var row: JSONObject? = null
        if (data is JSONArray && data.length() > 0) {
            row = data.optJSONObject(0)
        }
        if (row == null) {
            return ParsedPosition(null, null, null, null, null, null, null, null, status, message, null)
        }
        var battery = coerceInt(row.opt("battery")) ?: coerceInt(response.opt("battery"))
        var lat = coerceDouble(row.opt("lat"))
        var lng = coerceDouble(row.opt("lng"))

        val datatype = abs(coerceInt(row.opt("datatype")) ?: 0)
        if (datatype == 3) {
            val latCo = coerceDouble(row.opt("lat_co"))
            val lngCo = coerceDouble(row.opt("lng_co"))
            if (latCo != null && lngCo != null && (latCo != 0.0 || lngCo != 0.0)) {
                lat = latCo
                lng = lngCo
            }
        }
        if (lat != null && lng != null && lat == 0.0 && lng == 0.0) {
            lat = null; lng = null
        }
        return ParsedPosition(
            lat = lat,
            lng = lng,
            battery = battery,
            positionDate = parsePositionDate(row.opt("positiondate")),
            address = extractAddress(row),
            speed = coerceDouble(row.opt("speed")),
            direction = coerceDouble(row.opt("direction")),
            accuracy = coerceAccuracy(row.opt("gpsrang")),
            pollStatus = status,
            pollMessage = message,
            raw = row,
        )
    }
}
