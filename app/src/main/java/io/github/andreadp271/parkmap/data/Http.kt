package io.github.andreadp271.parkmap.data

import android.content.Context
import io.github.andreadp271.parkmap.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client HTTP condiviso: User-Agent identificativo (richiesto da Nominatim e dalle tile policy OSM) e cache su disco. */
object Http {
    const val SITE = "https://andreadp271.github.io/Park_finder/"
    const val REPO = "AndreaDp271/Park_finder"
    const val APK_REPO = "AndreaDp271/Park_finder-APK"
    val userAgent = "ParkMap/${BuildConfig.VERSION_NAME} (+https://github.com/$REPO)"

    lateinit var client: OkHttpClient
        private set

    fun init(ctx: Context) {
        if (::client.isInitialized) return
        client = OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "http"), 40L * 1024 * 1024))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 16 })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
    }

    fun get(url: String, vararg headers: Pair<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { header(it.first, it.second) } }.build()
}

/** Esegue la chiamata senza bloccare il thread; annullando la coroutine si annulla la richiesta. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Corpo della risposta come testo; errore se il server non risponde 2xx. */
suspend fun OkHttpClient.fetchText(request: Request): String = newCall(request).await().use { r ->
    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
    r.body!!.string()
}
