package io.github.andreadp271.parkmap.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.andreadp271.parkmap.MainActivity
import io.github.andreadp271.parkmap.R
import java.text.DateFormat
import java.util.Date

/**
 * Salvataggio automatico dell'auto: quando il telefono si scollega dal Bluetooth dell'auto scelta nelle
 * Impostazioni, legge la posizione e la salva come "La mia auto", con una notifica. Quando si ricollega la
 * sosta finisce: auto tolta, promemoria annullati, sosta nello storico.
 *
 * ACTION_ACL_CONNECTED/DISCONNECTED sono tra le trasmissioni di sistema che arrivano anche ai receiver
 * dichiarati nel manifest con l'app chiusa. Per leggere la posizione con l'app in background serve il
 * permesso "Consenti sempre".
 */
class BluetoothCarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (!connected && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val ctx = context.applicationContext
        CarStore.init(ctx)
        AppSettings.init(ctx)
        T.init(ctx, AppSettings.lang.value)

        @Suppress("DEPRECATION")
        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val target = CarStore.btAddress
        if (device == null || target == null || !target.equals(device.address, ignoreCase = true)) return

        if (connected) {
            // Di nuovo in auto: la sosta è finita
            CarStore.endFromBluetooth()
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) // "Auto salvata qui" non serve più
            return
        }

        val deviceName = CarStore.btName
        val pending = goAsync()
        locate(ctx) { loc ->
            try {
                if (loc != null) {
                    CarStore.saveAt(loc.latitude, loc.longitude, "bluetooth", deviceName)
                    notify(
                        ctx, t("Auto salvata qui"),
                        t("Scollegato da {name} alle {time}. Tocca per vederla o impostare il promemoria della sosta.",
                            "name" to (deviceName ?: "Bluetooth"), "time" to DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())),
                        AppAction.SHOW_CAR,
                    )
                } else {
                    notify(ctx, t("Scollegato dall'auto"), t("Posizione non disponibile: tocca per salvare dove hai parcheggiato."), AppAction.SAVE_CAR)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun lastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return best
    }

    private fun isGood(l: Location?) = l != null && System.currentTimeMillis() - l.time < RECENT_FIX_MS && l.hasAccuracy() && l.accuracy <= GOOD_ACCURACY_M

    @Suppress("DEPRECATION")
    private fun locate(ctx: Context, cb: (Location?) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { cb(null); return }
        val known = lastKnown(lm)
        if (isGood(known)) { cb(known); return }

        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
        else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null
        // Senza un nuovo fix si usa l'ultima posizione nota, se è di pochi minuti fa
        val fallback = known?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
        if (provider == null) { cb(fallback); return }

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var listener: LocationListener? = null
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            listener?.let { lm.removeUpdates(it) }
            cb(fallback)
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc ->
                    if (finished) return@getCurrentLocation
                    finished = true
                    handler.removeCallbacks(timeout)
                    cb(loc ?: fallback)
                }
            } else {
                listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacks(timeout)
                        lm.removeUpdates(this)
                        cb(loc)
                    }
                    override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            handler.postDelayed(timeout, FIX_TIMEOUT_MS)
        } catch (_: SecurityException) {
            // Permesso di posizione (in background) non concesso
            finished = true
            cb(null)
        }
    }

    private fun notify(ctx: Context, title: String, text: String, action: String) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, t("La mia auto"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).putExtra(AppAction.EXTRA, action).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_parkmap).setColor(0xFF2563EB.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) } catch (_: SecurityException) { /* l'auto è comunque salvata */ }
    }

    private companion object {
        const val CHANNEL_ID = "parkmap_car"
        const val NOTIFICATION_ID = 7301
        const val FIX_TIMEOUT_MS = 25_000L
        // Una posizione già nota va bene se recente e precisa (es. navigatore acceso fino all'arrivo)
        const val RECENT_FIX_MS = 60_000L
        const val GOOD_ACCURACY_M = 40f
    }
}
