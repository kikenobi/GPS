package com.yqt.tracker.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.json.JSONObject
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Transport {
    const val ENCRYPT_INDEX_HEADER = "X-Encrypt-Index"
    private const val CERT_PASSWORD = "yqt-smart-api"

    private val CIPHERS = mapOf(
        1 to ("c04680dcfacba69d".toByteArray(Charsets.US_ASCII) to "c6e134f528459fe0".toByteArray(Charsets.US_ASCII)),
        2 to ("029fd8027e50b5f7".toByteArray(Charsets.US_ASCII) to "d69026a0c6550792".toByteArray(Charsets.US_ASCII)),
        3 to ("bdc637d99503579d".toByteArray(Charsets.US_ASCII) to "562d9ee5da4daa34".toByteArray(Charsets.US_ASCII)),
        4 to ("ee3fa426e9acc406".toByteArray(Charsets.US_ASCII) to "6ee2d4bec18f67e3".toByteArray(Charsets.US_ASCII)),
        5 to ("e439c3a631357fe3".toByteArray(Charsets.US_ASCII) to "e4f8b2aa45f3e159".toByteArray(Charsets.US_ASCII)),
    )

    @Volatile private var httpClient: OkHttpClient? = null
    private val random = SecureRandom()
    private val cookieStore = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                for (cookie in cookies) {
                    cookieStore.removeAll {
                        it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                    }
                    cookieStore.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookieStore) {
                val now = System.currentTimeMillis()
                cookieStore.removeAll { it.expiresAt < now }
                return cookieStore.filter { it.matches(url) }
            }
        }
    }
    private val bcProvider: BouncyCastleProvider by lazy {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) as? BouncyCastleProvider
        if (existing != null) return@lazy existing
        val p = BouncyCastleProvider()
        Security.addProvider(p)
        p
    }

    fun getHttpClient(context: Context): OkHttpClient {
        val existing = httpClient
        if (existing != null) return existing
        synchronized(this) {
            val again = httpClient
            if (again != null) return again
            val built = buildClient(context.applicationContext)
            httpClient = built
            return built
        }
    }

    private fun buildClient(context: Context): OkHttpClient {
        val (keyManagers, trustManager) = loadSslMaterial(context)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun loadSslMaterial(context: Context): Pair<Array<javax.net.ssl.KeyManager>, X509TrustManager> {
        val certs = mutableListOf<X509Certificate>()
        var privateKey: PrivateKey? = null
        context.assets.open("client.pem").use { input ->
            PEMParser(InputStreamReader(input, Charsets.UTF_8)).use { parser ->
                val converter = JcaX509CertificateConverter().setProvider(bcProvider)
                val keyConverter = JcaPEMKeyConverter().setProvider(bcProvider)
                while (true) {
                    val obj = parser.readObject() ?: break
                    when (obj) {
                        is X509CertificateHolder -> certs.add(converter.getCertificate(obj))
                        is PKCS8EncryptedPrivateKeyInfo -> {
                            val decProv = JceOpenSSLPKCS8DecryptorProviderBuilder()
                                .setProvider(bcProvider)
                                .build(CERT_PASSWORD.toCharArray())
                            val keyInfo: PrivateKeyInfo = obj.decryptPrivateKeyInfo(decProv)
                            privateKey = keyConverter.getPrivateKey(keyInfo)
                        }
                        is PrivateKeyInfo -> {
                            privateKey = keyConverter.getPrivateKey(obj)
                        }
                    }
                }
            }
        }
        val key = privateKey ?: throw IllegalStateException("client.pem: private key not found")
        if (certs.isEmpty()) throw IllegalStateException("client.pem: certificate not found")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("client", key, CERT_PASSWORD.toCharArray(), certs.toTypedArray())

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, CERT_PASSWORD.toCharArray())

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trustManagers = tmf.trustManagers
        val x509Tm = trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager")
        return kmf.keyManagers to x509Tm
    }

    fun encryptRequest(params: Map<String, Any?>, formEncoded: Boolean): Pair<JSONObject, Int> {
        val unsigned = LinkedHashMap<String, String>()
        params.forEach { (k, v) ->
            if (v != null && k != "sign") unsigned[k] = v.toString()
        }
        if (!unsigned.containsKey("app_flag")) unsigned["app_flag"] = Protocol.DEFAULT_CLIENT_FLAG.toString()

        val signed = LinkedHashMap<String, String>(unsigned)
        signed["sign"] = Protocol.computeSign(unsigned)

        val finalMap: Map<String, String> = if (formEncoded) {
            val enc = LinkedHashMap<String, String>()
            signed.forEach { (k, v) -> enc[Protocol.quotePlus(k)] = Protocol.quotePlus(v) }
            enc
        } else signed

        val json = toCompactJson(finalMap)
        val index = (1 + random.nextInt(CIPHERS.size))
        val encrypted = aesEncrypt(json.toByteArray(Charsets.UTF_8), index)
        val envelope = JSONObject()
        envelope.put("encryptIndex", index)
        envelope.put("encryptData", bytesToHex(encrypted))
        return envelope to index
    }

    fun decryptResponse(payload: JSONObject): JSONObject {
        val encData = payload.optString("encryptData", "")
        if (encData.isEmpty()) return payload
        val index = payload.optInt("encryptIndex", -1)
        val decrypted = aesDecrypt(hexToBytes(encData), index)
        val text = String(decrypted, Charsets.UTF_8)
        val obj = JSONObject(text)
        return obj
    }

    private fun cipher(index: Int): Pair<ByteArray, ByteArray> =
        CIPHERS[index] ?: throw IllegalArgumentException("unsupported encryption index $index")

    private fun aesEncrypt(data: ByteArray, index: Int): ByteArray {
        val (key, iv) = cipher(index)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun aesDecrypt(data: ByteArray, index: Int): ByteArray {
        val (key, iv) = cipher(index)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    // Compact JSON with stable insertion order (JSONObject doesn't guarantee order in all impls, but we build string manually)
    fun toCompactJson(map: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(k)).append(':').append(JSONObject.quote(v))
        }
        sb.append('}')
        return sb.toString()
    }
}
