package it.snel.melamur.net

import android.content.Context
import android.util.Log
import it.snel.melamur.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Sends on-board GPS fixes to the MelaMur aggregator over mutual TLS:
 *  - the server certificate is verified against the system CAs + hostname, and
 *    optionally pinned (BuildConfig.AGGREGATOR_PIN, "sha256/..." form);
 *  - the app authenticates with a client certificate from assets/client.p12
 *    (see server/make_certs.sh). Without that file the client is a STUB: fixes
 *    are only logged, never sent.
 *
 * Payload contains no personal identifiers, only a random per-trip id.
 */
class AggregatorClient(ctx: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private val buf = JSONArray()
    private var tripId = UUID.randomUUID().toString()
    private val http: OkHttpClient? = build(ctx)

    fun newTrip() = io.execute { flushNow(); tripId = UUID.randomUUID().toString() }

    fun add(t: Long, lat: Double, lon: Double, acc: Float, speed: Float, bearing: Float?, routeId: String?) = io.execute {
        buf.put(JSONObject().put("t", t).put("lat", lat).put("lon", lon).put("acc", acc)
            .put("spd", speed).put("brg", bearing ?: JSONObject.NULL).put("route", routeId ?: JSONObject.NULL))
        if (buf.length() >= BATCH) flushNow()
    }

    fun flush() = io.execute { flushNow() }

    private fun flushNow() {
        if (buf.length() == 0) return
        val body = JSONObject().put("v", 1).put("trip", tripId).put("fixes", JSONArray(buf.toString()))
        while (buf.length() > 0) buf.remove(buf.length() - 1)
        val c = http
        if (c == null) { Log.i(TAG, "stub (no client cert), would POST: $body"); return }
        try {
            val req = Request.Builder().url(BuildConfig.AGGREGATOR_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            c.newCall(req).execute().use { Log.i(TAG, "POST ${it.code}") }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed, dropping batch", e) // TODO: persistent retry queue
        }
    }

    private fun build(ctx: Context): OkHttpClient? = try {
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("client.p12").use { ks.load(it, BuildConfig.CLIENT_P12_PASSWORD.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, BuildConfig.CLIENT_P12_PASSWORD.toCharArray())
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // system trust store => server cert is verified
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .apply {
                if (BuildConfig.AGGREGATOR_PIN.isNotEmpty())
                    certificatePinner(CertificatePinner.Builder().add("melamur.snel.it", BuildConfig.AGGREGATOR_PIN).build())
            }
            .callTimeout(20, TimeUnit.SECONDS).build()
    } catch (e: Exception) {
        Log.w(TAG, "no usable client certificate, aggregator upload disabled: ${e.message}"); null
    }

    companion object { private const val TAG = "Aggregator"; private const val BATCH = 15 }
}
