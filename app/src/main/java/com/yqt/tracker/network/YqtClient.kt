package com.yqt.tracker.network

import android.content.Context
import com.yqt.tracker.data.WatchInfo
import com.yqt.tracker.data.WatchPosition
import com.yqt.tracker.data.WatchState
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class YqtApiException(val status: Int?, val apiMessage: String, val payload: JSONObject? = null) :
    Exception(if (status != null) "status=$status: $apiMessage" else apiMessage)

class YqtClient(
    private val context: Context,
    private val region: String,
    private val account: String,
    private val password: String,
) {
    private val baseUrl: String = Protocol.REGIONS[region] ?: throw IllegalArgumentException("Unknown region: $region")
    private val http get() = Transport.getHttpClient(context)

    @Volatile var sessionId: String? = null
        private set
    @Volatile var userId: Int? = null
        private set
    @Volatile var loginName: String? = null
        private set

    // did -> info map
    val deviceIndex: LinkedHashMap<String, MutableMap<String, Any?>> = LinkedHashMap()

    @Synchronized
    fun login(): JSONObject {
        val payload = Protocol.signedParams(
            mapOf(
                "language" to Protocol.DEFAULT_LANGUAGE,
                "appid" to Protocol.DEFAULT_APP_ID,
                "password" to Protocol.hashPassword(password),
                "loginname" to account,
                "flag" to Protocol.DEFAULT_CLIENT_FLAG,
                "version" to Protocol.DEFAULT_CLIENT_VERSION,
                "isIPHONE" to Protocol.DEFAULT_IS_IPHONE,
            )
        )
        val resp = requestJson("POST", "/app/public/S10APP/v2_new_userLogin2", data = payload)
        ensureSuccess(resp)

        val sid = resp.optString("sid", "")
        if (sid.isNotEmpty()) sessionId = sid
        loginName = account
        val users = resp.opt("data")
        if (users is JSONArray && users.length() > 0) {
            val u = users.optJSONObject(0)
            val uid = Protocol.coerceInt(u?.opt("id"))
            if (uid != null) userId = uid
        }
        cacheDeviceMetadata(resp)
        return resp
    }

    @Synchronized
    fun ensureLoggedIn() {
        if (sessionId.isNullOrEmpty()) login()
    }

    fun discoverDevices(): List<WatchInfo> {
        ensureLoggedIn()
        val uid = userId
        if (uid != null) {
            // Prefer per-device rows (often include did_id/nickname)
            try { listDevices(uid, account) } catch (_: Exception) {}
            // Enrich from metadata mapping
            try {
                val meta = findDeviceListByUserId(uid, account)
                cacheDeviceMetadata(meta)
            } catch (_: Exception) {}
            // Fallback if still empty
            if (deviceIndex.isEmpty()) {
                try { listDevices(uid, account) } catch (_: Exception) {}
            }
        }
        return deviceIndex.values.map { toWatchInfo(it) }
    }

    fun toWatchInfo(m: Map<String, Any?>): WatchInfo {
        val did = (m["did"] ?: "").toString()
        val name = ((m["nickname"] ?: m["rolename"] ?: m["name"] ?: did).toString()).ifBlank { did }
        val didId = (m["did_id"] ?: m["didId"] ?: "").toString()
        return WatchInfo(
            did = did,
            didId = didId,
            name = name,
            model = (m["model"] ?: m["dev_model"] ?: "").toString(),
            config = (m["config"] ?: "").toString(),
        )
    }

    fun refreshAllStates(previous: Map<String, WatchState> = emptyMap()): Map<String, WatchState> {
        ensureLoggedIn()
        val watches = if (deviceIndex.isEmpty()) discoverDevices().associateBy { it.did } else deviceIndex.mapValues { toWatchInfo(it.value) }
        val out = linkedMapOf<String, WatchState>()
        for ((did, info0) in watches) {
            val info = info0
            try {
                val resp = findLastPosition(did, info.didId)
                val parsed = Protocol.parseLastPosition(resp)
                val pos = if (parsed.lat != null && parsed.lng != null) {
                    WatchPosition(
                        lat = parsed.lat,
                        lng = parsed.lng,
                        accuracy = parsed.accuracy,
                        speed = parsed.speed,
                        direction = parsed.direction,
                        battery = parsed.battery,
                        address = parsed.address,
                        positionDate = parsed.positionDate,
                        pollStatus = parsed.pollStatus,
                        pollMessage = parsed.pollMessage,
                    )
                } else null
                val err = if (pos == null) (parsed.pollMessage?.ifBlank { "sin posición" }) else null
                // keep previous position if none and previous exists? similar to build_watch_state behavior
                val prev = previous[did]
                val finalPos = pos ?: prev?.position
                val finalErr = if (pos == null && prev?.position != null) null else err
                out[did] = WatchState(info = info, position = finalPos, error = finalErr)
            } catch (e: YqtApiException) {
                val prev = previous[did]
                out[did] = WatchState(info = info, position = prev?.position, error = e.apiMessage.ifBlank { e.message ?: "error" })
            } catch (e: Exception) {
                val prev = previous[did]
                out[did] = WatchState(info = info, position = prev?.position, error = e.message ?: "error")
            }
        }
        return out
    }

    fun findLastPosition(did: String, didId: String = ""): JSONObject {
        ensureLoggedIn()
        val resolvedDidId = resolveDidId(did, didId)

        fun buildParams() = Protocol.signedParams(
            mapOf(
                "language" to Protocol.DEFAULT_LANGUAGE,
                "did_id" to resolvedDidId,
                "did" to did,
                "id" to "",
            )
        )

        val path = sessionPath("/S10APP/v2_findLastPosition")
        var resp = requestJson("GET", path, query = buildParams())
        if (Protocol.isLoginTimeout(resp)) {
            login()
            val retryPath = sessionPath("/S10APP/v2_findLastPosition")
            resp = requestJson("GET", retryPath, query = buildParams())
        }
        ensureStatus(resp, setOf(1, 4, 2))
        if (!resp.has("data")) resp.put("data", JSONArray())
        return resp
    }

    fun findDeviceListByUserId(uid: Int, loginname: String): JSONObject {
        val params = Protocol.signedParams(
            mapOf(
                "language" to Protocol.DEFAULT_LANGUAGE,
                "user_id" to uid,
                "loginname" to loginname,
            )
        )
        val resp = requestJson("GET", "/app/public/S10APP/v2_findDeviceListByUserId", query = params)
        ensureSuccess(resp)
        cacheDeviceMetadata(resp)
        return resp
    }

    fun listDevices(uid: Int, loginname: String): JSONObject {
        val params = Protocol.signedParams(
            mapOf(
                "language" to Protocol.DEFAULT_LANGUAGE,
                "user_id" to uid,
                "loginname" to loginname,
                "type" to 0,
            )
        )
        val resp = requestJson("GET", "/app/public/S10APP/v2_new_findUserDeviceInfo", query = params)
        ensureSuccess(resp)
        val data = resp.opt("data")
        if (data is JSONArray) cacheDeviceRows(data)
        return resp
    }

    private fun sessionPath(suffix: String): String {
        val sid = sessionId ?: throw YqtApiException(null, "No hay sesión; inicia sesión primero")
        return "/app/$sid$suffix"
    }

    private fun resolveDidId(did: String, didId: String): String {
        if (didId.isNotBlank()) {
            val dev = deviceIndex.getOrPut(did) { mutableMapOf("did" to did) }
            dev["did_id"] = didId
            return didId
        }
        val dev = deviceIndex[did]
        val cached = (dev?.get("did_id") ?: dev?.get("didId"))?.toString() ?: ""
        if (cached.isNotBlank() && cached != "null") {
            dev?.set("did_id", cached)
            return cached
        }
        throw YqtApiException(null, "Falta did_id para $did")
    }

    private fun cacheDeviceMetadata(payload: JSONObject) {
        val index = Protocol.buildWatchIndex(payload, userId)
        for ((did, m) in index) {
            val cached = deviceIndex.getOrPut(did) { mutableMapOf("did" to did) }
            for ((k, v) in m) {
                if ((v == null || v == "") && cached.containsKey(k) && cached[k] != null && cached[k] != "") continue
                cached[k] = v
            }
        }
    }

    private fun cacheDeviceRows(rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val did = row.optString("did", "")
            if (did.isBlank()) continue
            val cached = deviceIndex.getOrPut(did) { mutableMapOf("did" to did) }
            val keys = row.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = row.opt(k)
                if ((v == null || v == JSONObject.NULL || v == "") && cached.containsKey(k) && cached[k] != null && cached[k] != "") continue
                cached[k] = if (v == JSONObject.NULL) null else v
            }
        }
    }

    private fun requestJson(method: String, path: String, query: Map<String, String>? = null, data: Map<String, String>? = null): JSONObject {
        val urlBase = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val (envelope, index) = if (query != null) {
            Transport.encryptRequest(query, formEncoded = false)
        } else {
            Transport.encryptRequest(data ?: emptyMap(), formEncoded = true)
        }

        val reqBuilder = Request.Builder()
            .header("Accept", "application/json")
            .header(Transport.ENCRYPT_INDEX_HEADER, index.toString())

        val request = if (method == "GET") {
            val url = urlBase.toHttpUrl().newBuilder()
                .addQueryParameter("encryptData", envelope.optString("encryptData"))
                .addQueryParameter("encryptIndex", envelope.opt("encryptIndex").toString())
                .build()
            reqBuilder.url(url).get().build()
        } else {
            val bodyStr = envelope.toString()
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            reqBuilder.url(urlBase).post(body).build()
        }

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw YqtApiException(resp.code, "HTTP ${resp.code}: $raw")
            }
            val json = try { JSONObject(raw) } catch (e: Exception) { throw YqtApiException(null, "Respuesta no JSON") }
            return try { Transport.decryptResponse(json) } catch (e: Exception) { throw YqtApiException(null, "Respuesta cifrada inválida") }
        }
    }

    private fun ensureSuccess(payload: JSONObject) = ensureStatus(payload, setOf(1, 4))
    private fun ensureStatus(payload: JSONObject, allowed: Set<Int>) {
        val status = Protocol.coerceInt(payload.opt("status"))
        if (status == null || status !in allowed) {
            val msg = payload.optString("message", payload.optString("msg", "respuesta inesperada"))
            throw YqtApiException(status, msg, payload)
        }
    }
}
