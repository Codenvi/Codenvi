package app.codenvi.net

import android.os.Build
import app.codenvi.BuildConfig
import app.codenvi.data.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class RevokedException : Exception("This app was disconnected from the server.")

/** Shared HTTP plumbing: headers, token refresh, optional key pinning (docs/design.md §4.1, §10.2). */
class Http(private val store: SecureStore) {
    val revoked = MutableStateFlow(false)
    val upgradeRequired = MutableStateFlow(false)

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt: Long = 0
    private val refreshLock = Any()
    private var cachedFor: String? = null
    private var cachedClient: OkHttpClient? = null
    private var cachedApi: CodenviApi? = null

    val clientHeader: String
        get() = "android/${BuildConfig.VERSION_NAME.removeSuffix("-debug")}+${BuildConfig.VERSION_CODE} " +
            "(Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    fun baseUrl(host: String = store.data.serverHost ?: "invalid.local") = "https://$host/"

    fun acceptTokens(t: TokenResponse) {
        accessToken = t.accessToken
        accessExpiresAt = t.accessExpiresAtMs
        store.update { it.copy(refreshToken = t.refreshToken, installId = t.installId) }
        revoked.value = false
    }

    fun clearTokens() {
        accessToken = null
        accessExpiresAt = 0
    }

    /** Valid access token; refreshes synchronously when needed. Throws RevokedException when the install was revoked. */
    fun validAccessToken(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (!force && accessExpiresAt - 60_000 > now) return it }
        synchronized(refreshLock) {
            accessToken?.let { if (!force && accessExpiresAt - 60_000 > System.currentTimeMillis()) return it }
            val d = store.data
            val refresh = d.refreshToken ?: throw RevokedException()
            val body = moshi.adapter(RefreshRequest::class.java).toJson(RefreshRequest(d.installId, refresh))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(baseUrl() + "api/v1/token/refresh").post(body)
                .header("X-Codenvi-Install-Id", d.installId).header("X-Codenvi-Client", clientHeader).build()
            baseClient().newCall(req).execute().use { res ->
                if (res.code == 401) { revoked.value = true; throw RevokedException() }
                if (res.code == 426) { upgradeRequired.value = true; throw java.io.IOException("Update Codenvi") }
                if (!res.isSuccessful) throw java.io.IOException("Token refresh failed (${res.code})")
                val t = moshi.adapter(TokenResponse::class.java).fromJson(res.body!!.string())!!
                acceptTokens(t)
                return t.accessToken
            }
        }
    }

    private fun headers() = Interceptor { chain ->
        val d = store.data
        val b = chain.request().newBuilder()
            .header("X-Codenvi-Install-Id", d.installId)
            .header("X-Codenvi-Client", clientHeader)
        val path = chain.request().url.encodedPath
        if (!path.endsWith("/pair") && !path.endsWith("/token/refresh") && d.refreshToken != null) {
            b.header("Authorization", "Bearer " + validAccessToken())
        }
        val res = chain.proceed(b.build())
        if (res.code == 426) upgradeRequired.value = true
        res
    }

    private val authenticator = Authenticator { _: Route?, response: Response ->
        if (response.request.header("X-Codenvi-Retried") != null) return@Authenticator null
        if (response.request.url.encodedPath.endsWith("/pair")) return@Authenticator null
        val token = try { validAccessToken(force = true) } catch (e: Exception) { return@Authenticator null }
        response.request.newBuilder().header("Authorization", "Bearer $token").header("X-Codenvi-Retried", "1").build()
    }

    /** Client without auth (used for refresh, and as the base for the others). Applies the pin if there is one. */
    fun baseClient(pin: String? = store.data.pin): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
        if (!pin.isNullOrBlank()) {
            val tm = PinnedTrustManager(pin)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            b.sslSocketFactory(ctx.socketFactory, tm)
            b.hostnameVerifier { _, _ -> true } // identity is the pinned key itself
        }
        return b.build()
    }

    @Synchronized
    fun client(): OkHttpClient {
        val key = "${store.data.serverHost}|${store.data.pin}"
        if (cachedClient == null || cachedFor != key) {
            val b = baseClient().newBuilder().addInterceptor(headers()).authenticator(authenticator)
            if (BuildConfig.DEBUG) {
                b.addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC })
            }
            cachedClient = b.build()
            cachedApi = null
            cachedFor = key
        }
        return cachedClient!!
    }

    @Synchronized
    fun api(): CodenviApi {
        val c = client()
        cachedApi?.let { return it }
        return Retrofit.Builder().baseUrl(baseUrl()).client(c)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build().create(CodenviApi::class.java).also { cachedApi = it }
    }

    /** One-off API for pairing before anything is stored. */
    fun pairingApi(host: String, pin: String?): CodenviApi {
        val c = baseClient(pin).newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Codenvi-Client", clientHeader).build())
        }.build()
        return Retrofit.Builder().baseUrl(baseUrl(host)).client(c)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(CodenviApi::class.java)
    }
}

/** Accepts exactly the server whose SubjectPublicKeyInfo SHA-256 matches the pin from the connection string. */
class PinnedTrustManager(pin: String) : X509TrustManager {
    private val expected: ByteArray = try {
        Base64.getUrlDecoder().decode(pin.trimEnd('='))
    } catch (e: IllegalArgumentException) {
        Base64.getDecoder().decode(pin)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val spki = chain.firstOrNull()?.publicKey?.encoded ?: throw java.security.cert.CertificateException("No certificate")
        val hash = MessageDigest.getInstance("SHA-256").digest(spki)
        if (!MessageDigest.isEqual(hash, expected)) throw java.security.cert.CertificateException("Server key does not match the pinned key")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = throw UnsupportedOperationException()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
