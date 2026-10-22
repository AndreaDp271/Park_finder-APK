package io.github.andreadp271.parkmap.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.github.andreadp271.parkmap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Aggiornamenti dalle release di GitHub: controllo, download e installazione dell'APK. */
object Updater {
    private const val REPO = "https://github.com/${Http.APK_REPO}"
    private const val API = "https://api.github.com/repos/${Http.APK_REPO}/releases/latest"
    private const val RAW = "https://raw.githubusercontent.com/${Http.APK_REPO}"

    /** Esito dell'installazione (null = riuscita o annullata), ascoltato dall'interfaccia se aperta. */
    var onFinished: ((error: String?) -> Unit)? = null

    data class Release(val version: String, val apkUrl: String, val notes: String, val page: String)

    /** Installata da F-Droid (o da un altro negozio): gli aggiornamenti li gestisce quello. */
    fun fromStore(ctx: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }.getOrNull()
        return installer != null && installer != "com.android.packageinstaller" && installer != "com.google.android.packageinstaller" &&
            installer != "com.android.shell" && installer != ctx.packageName && installer != "com.google.android.apps.nbu.files"
    }

    /**
     * Ultima release pubblicata su GitHub. L'API ha un limite di 60 richieste l'ora per rete: se risponde con
     * un errore si ricava la versione dalla pagina delle release.
     */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        try {
            fromApi()
        } catch (e: java.net.UnknownHostException) {
            throw e
        } catch (e: IOException) {
            runCatching { fromPage() }.getOrElse { throw e }
        }
    }

    /** Senza API: il tag arriva dal redirect di /releases/latest, le note dal changelog nel repository. */
    private fun fromPage(): Release {
        val noRedirect = Http.client.newBuilder().followRedirects(false).build()
        val location = noRedirect.newCall(Request.Builder().url("$REPO/releases/latest").build()).execute().use { it.header("Location") }
            ?: throw IOException("Nessuna release trovata")
        val tag = location.substringAfterLast("/tag/", "").ifEmpty { throw IOException("Nessuna release trovata") }
        val version = tag.removePrefix("v")
        fun raw(path: String) = runCatching {
            Http.client.newCall(Request.Builder().url("$RAW/$tag/$path").build()).execute().use { if (it.isSuccessful) it.body!!.string() else null }
        }.getOrNull()
        val code = raw("app/build.gradle.kts")?.let { Regex("""versionCode = (\d+)""").find(it)?.groupValues?.get(1) }
        val notes = code?.let { raw("fastlane/metadata/android/it-IT/changelogs/$it.txt") }.orEmpty().trim()
        return Release(version, "$REPO/releases/download/$tag/ParkMap-$version.apk", notes, location)
    }

    private fun fromApi(): Release {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
        val body = Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub ha risposto ${r.code}")
            r.body!!.string()
        }
        val o = Json.parseToJsonElement(body).jsonObject
        val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk") }
            ?: throw IOException("Nessun APK nell'ultima release")
        return Release(
            version = o["tag_name"]!!.jsonPrimitive.content.removePrefix("v"),
            apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
            notes = o["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            page = o["html_url"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /** true se [remote] (es. "2.4.1") è successiva a [local]. */
    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Scarica l'APK nella cache, segnalando l'avanzamento (0..1). */
    suspend fun download(ctx: Context, rel: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "ParkMap-${rel.version}.apk")
        val client: OkHttpClient = Http.client
        client.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download non riuscito (${r.code})")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        total?.let { progress(done.toFloat() / it) }
                    }
                }
            }
        }
        verify(ctx, file)
        file
    }

    /** L'APK deve essere della stessa app, più recente e firmato con la stessa chiave. */
    private fun verify(ctx: Context, file: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: run { file.delete(); throw IOException("File scaricato non valido") }
        val me = pm.getPackageInfo(ctx.packageName, flags)
        val ok = apk.packageName == ctx.packageName && signers(apk) == signers(me) && signers(me).isNotEmpty()
        if (!ok) { file.delete(); throw IOException("L'APK scaricato non è firmato come questa app: installazione annullata") }
        @Suppress("DEPRECATION")
        val newer = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode > me.longVersionCode else apk.versionCode > me.versionCode
        if (!newer) { file.delete(); throw IOException("La versione su GitHub non è più recente di quella installata") }
    }

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Avvia l'installazione: Android chiede conferma all'utente. */
    suspend fun install(ctx: Context, file: File) = withContext(Dispatchers.IO) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Esito dell'installazione: se serve la conferma dell'utente apre la schermata di sistema. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onFinished?.invoke(null)
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val msg = when {
                    "DOWNGRADE" in raw -> "La versione scaricata è più vecchia di quella installata"
                    "INSUFFICIENT_STORAGE" in raw -> "Spazio di archiviazione insufficiente"
                    "SIGNATURE" in raw || "UPDATE_INCOMPATIBLE" in raw -> "Firma dell'APK non compatibile con l'app installata"
                    else -> "Installazione non riuscita" + if (raw.isNotBlank()) " ($raw)" else ""
                }
                Updater.onFinished?.invoke(msg) ?: android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
