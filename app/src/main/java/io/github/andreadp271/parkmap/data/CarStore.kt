package io.github.andreadp271.parkmap.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.exifinterface.media.ExifInterface
import io.github.andreadp271.parkmap.MainActivity
import io.github.andreadp271.parkmap.R
import io.github.andreadp271.parkmap.widget.ParkMapWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/** La mia auto: dove è parcheggiata, fino a quando, con nota e foto. [source] = gps | map | bluetooth | shared. */
class Car(
    val lat: Double, val lon: Double, val ts: Long,
    val expiry: Long? = null, val source: String = "gps", val address: String = "",
    val note: String = "", val photo: Boolean = false, val device: String? = null,
)

/** Sosta conclusa, nello storico. */
class Stop(val lat: Double, val lon: Double, val ts: Long, val end: Long, val address: String, val note: String)

/**
 * Stato della sosta, condiviso tra l'app, il widget, le scorciatoie e il salvataggio automatico via Bluetooth
 * (che gira anche con l'app chiusa): tutto passa da qui, salvato in SharedPreferences.
 */
object CarStore {
    const val HISTORY_MAX = 30
    const val WARNING_MS = 10 * 60 * 1000L
    // Riconnessione subito dopo il salvataggio: era un falso distacco (es. motore riacceso)
    private const val FALSE_ALARM_MS = 3 * 60 * 1000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val _car = MutableStateFlow<Car?>(null)
    val car: StateFlow<Car?> = _car
    private val _history = MutableStateFlow<List<Stop>>(emptyList())
    val history: StateFlow<List<Stop>> = _history
    /** Cambia a ogni modifica della foto, per ricaricarla. */
    private val _photoVersion = MutableStateFlow(0)
    val photoVersion: StateFlow<Int> = _photoVersion

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("car", Context.MODE_PRIVATE)
        _car.value = runCatching { prefs.getString("car", null)?.let { carOf(JSONObject(it)) } }.getOrNull()
        _history.value = runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Stop(o.getDouble("lat"), o.getDouble("lon"), o.getLong("ts"), o.getLong("end"), o.optString("address"), o.optString("note"))
            }
        }.getOrDefault(emptyList())
    }

    private fun carOf(o: JSONObject) = Car(
        o.getDouble("lat"), o.getDouble("lon"), o.getLong("ts"),
        if (o.isNull("expiry") || !o.has("expiry")) null else o.getLong("expiry"),
        o.optString("source", "gps"), o.optString("address"), o.optString("note"), o.optBoolean("photo"),
        if (o.has("device")) o.optString("device") else null,
    )

    private fun json(c: Car) = JSONObject().put("lat", c.lat).put("lon", c.lon).put("ts", c.ts)
        .put("expiry", c.expiry ?: JSONObject.NULL).put("source", c.source).put("address", c.address)
        .put("note", c.note).put("photo", c.photo).apply { c.device?.let { put("device", it) } }

    /** Salva l'auto (e ripianifica promemoria e widget). */
    private fun commit(c: Car?) {
        _car.value = c
        prefs.edit().apply { if (c == null) remove("car") else putString("car", json(c).toString()) }.apply()
        CarReminders.schedule(app, c)
        ParkMapWidget.updateAll(app)
    }

    private fun saveHistory() {
        val a = JSONArray()
        _history.value.forEach { h ->
            a.put(JSONObject().put("lat", h.lat).put("lon", h.lon).put("ts", h.ts).put("end", h.end).put("address", h.address).put("note", h.note))
        }
        prefs.edit().putString("history", a.toString()).apply()
    }

    private fun archive(c: Car, end: Long = System.currentTimeMillis()) {
        _history.value = (listOf(Stop(c.lat, c.lon, c.ts, end, c.address, c.note)) + _history.value).sortedByDescending { it.ts }.take(HISTORY_MAX)
        saveHistory()
        if (c.photo) deletePhoto()
    }

    /** Nuova sosta: la precedente, se c'era, va nello storico. */
    fun saveAt(lat: Double, lon: Double, source: String, device: String? = null, note: String = "") {
        _car.value?.let { archive(it) }
        commit(Car(Math.round(lat * 1e6) / 1e6, Math.round(lon * 1e6) / 1e6, System.currentTimeMillis(), source = source, device = device, note = note))
    }

    /** "Ho lasciato il parcheggio": la sosta va nello storico. */
    fun leave() {
        _car.value?.let { archive(it) }
        commit(null)
    }

    fun setExpiry(expiry: Long?) {
        val c = _car.value ?: return
        commit(Car(c.lat, c.lon, c.ts, expiry, c.source, c.address, c.note, c.photo, c.device))
    }

    fun setNote(note: String) {
        val c = _car.value ?: return
        _car.value = Car(c.lat, c.lon, c.ts, c.expiry, c.source, c.address, note, c.photo, c.device)
        prefs.edit().putString("car", json(_car.value!!).toString()).apply()
    }

    fun setAddress(target: Car, address: String) {
        val c = _car.value ?: return
        if (c.ts != target.ts) return
        _car.value = Car(c.lat, c.lon, c.ts, c.expiry, c.source, address, c.note, c.photo, c.device)
        prefs.edit().putString("car", json(_car.value!!).toString()).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        saveHistory()
    }

    // ─── Bluetooth dell'auto ───

    var btAddress: String?
        get() = prefs.getString("bt_address", null)
        set(v) = prefs.edit().apply { if (v == null) remove("bt_address").remove("bt_name") else putString("bt_address", v) }.apply()

    var btName: String?
        get() = prefs.getString("bt_name", null)
        set(v) = prefs.edit().putString("bt_name", v).apply()

    /**
     * Ricollegato al Bluetooth dell'auto: la sosta è finita. L'auto va nello storico (a meno che sia stata
     * salvata da meno di 3 minuti: era un falso distacco) e promemoria e widget si aggiornano.
     * @return la durata della sosta in ms, oppure -1 se non c'era un'auto.
     */
    fun endFromBluetooth(): Long {
        val c = _car.value ?: return -1
        val now = System.currentTimeMillis()
        if (!(c.source == "bluetooth" && now - c.ts < FALSE_ALARM_MS)) archive(c, now)
        else if (c.photo) deletePhoto()
        commit(null)
        return now - c.ts
    }

    // ─── Foto ───

    val photoFile: File get() = File(app.filesDir, "car_photo.jpg")

    /** Salva la foto ridotta (lato lungo 1280 px, JPEG): qualche centinaio di KB invece di MB. */
    fun setPhoto(uri: Uri): Boolean {
        val c = _car.value ?: return false
        return runCatching {
            val bmp = decodeScaled(uri, 1280) ?: return false
            photoFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            bmp.recycle()
            commit(Car(c.lat, c.lon, c.ts, c.expiry, c.source, c.address, c.note, true, c.device))
            _photoVersion.value++
            true
        }.getOrDefault(false)
    }

    fun removePhoto() {
        val c = _car.value ?: return
        deletePhoto()
        commit(Car(c.lat, c.lon, c.ts, c.expiry, c.source, c.address, c.note, false, c.device))
    }

    private fun deletePhoto() {
        photoFile.delete()
        _photoVersion.value++
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val k = minOf(1f, maxSide.toFloat() / max(raw.width, raw.height))
        val m = android.graphics.Matrix().apply { postScale(k, k); postRotate(rotation) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
}

/** Promemoria della sosta: notifica 10 minuti prima della scadenza e alla scadenza. */
object CarReminders {
    private const val ID_WARNING = 7101
    private const val ID_EXPIRED = 7102
    private const val CHANNEL = "parkmap_reminders"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Promemoria sosta"), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pending(ctx: Context, id: Int, title: String = "", body: String = "", flags: Int = 0): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, CarReminderReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("body", body),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notificationsAllowed(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun schedule(ctx: Context, car: Car?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(ID_WARNING, ID_EXPIRED).forEach { id ->
            pending(ctx, id, flags = PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            nm.cancel(id)
        }
        val expiry = car?.expiry ?: return
        val now = System.currentTimeMillis()
        if (expiry - CarStore.WARNING_MS > now + 30_000) {
            set(am, ctx, expiry - CarStore.WARNING_MS, ID_WARNING, t("Sosta in scadenza"),
                t("Il tuo parcheggio scade alle {time}: mancano 10 minuti.", "time" to Fmt.clock(expiry)))
        }
        if (expiry > now) set(am, ctx, expiry, ID_EXPIRED, t("Sosta scaduta"), t("Il tempo del parcheggio è finito."))
        // Alla scadenza il widget passa da "scade alle" a "scaduta"
        ParkMapWidget.scheduleRefresh(ctx, expiry + 1000)
    }

    private fun set(am: AlarmManager, ctx: Context, at: Long, id: Int, title: String, body: String) {
        val pi = pending(ctx, id, title, body, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun notify(ctx: Context, id: Int, title: String, body: String) {
        createChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra(AppAction.EXTRA, AppAction.SHOW_CAR)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_parkmap).setColor(0xFF2563EB.toInt())
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { /* notifiche non consentite */ }
    }
}

/** Dopo il riavvio (o l'aggiornamento dell'app) gli allarmi sono spariti: si ripianificano i promemoria e il widget. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        CarStore.init(ctx)
        CarReminders.schedule(ctx, CarStore.car.value)
        ParkMapWidget.updateAll(ctx)
    }
}

class CarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        CarStore.init(ctx)
        AppSettings.init(ctx)
        T.init(ctx, AppSettings.lang.value)
        if (CarStore.car.value?.expiry == null) return // promemoria rimasto di una sosta già chiusa
        CarReminders.notify(ctx, intent.getIntExtra("id", 7101), intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty())
    }
}

/** Azioni chieste da widget, scorciatoie dell'icona e notifiche (extra [EXTRA] dell'intent che apre l'app). */
object AppAction {
    const val EXTRA = "parkmap_action"
    const val SAVE_CAR = "saveCar"
    const val SHOW_CAR = "showCar"
    const val NEARBY = "nearby"
}
