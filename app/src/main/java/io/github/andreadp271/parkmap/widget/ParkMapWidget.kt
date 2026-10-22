package io.github.andreadp271.parkmap.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.andreadp271.parkmap.MainActivity
import io.github.andreadp271.parkmap.R
import io.github.andreadp271.parkmap.data.AppAction
import io.github.andreadp271.parkmap.data.CarStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Widget "La mia auto": senza auto salvata propone "Ho parcheggiato qui"; con l'auto salvata mostra da quando
 * è parcheggiata e il conto alla rovescia della sosta. Tocco sul widget o sul pulsante = apre l'app sull'auto.
 */
class ParkMapWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        CarStore.init(ctx)
        val views = buildViews(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) { CarStore.init(ctx); updateAll(ctx) }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.andreadp271.parkmap.WIDGET_REFRESH"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ParkMapWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        /** Aggiorna il widget a un'ora precisa (non serve l'allarme esatto: basta il secondo giusto). */
        fun scheduleRefresh(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 7201, Intent(ctx, ParkMapWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
            if (at > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }

        private fun openApp(ctx: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, requestCode,
                Intent(ctx, MainActivity::class.java).putExtra(AppAction.EXTRA, action).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun clock(ts: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

        private fun isToday(ts: Long): Boolean {
            val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = ts }
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }

        fun buildViews(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.parkmap_widget)
            val car = CarStore.car.value
            val now = System.currentTimeMillis()

            if (car == null) {
                v.setTextViewText(R.id.widget_title, ctx.getString(R.string.parkmap_widget_no_car))
                v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_no_car_hint))
                v.setViewVisibility(R.id.widget_timer, View.GONE)
                v.setTextViewText(R.id.widget_button, ctx.getString(R.string.parkmap_widget_save))
                v.setOnClickPendingIntent(R.id.widget_button, openApp(ctx, AppAction.SAVE_CAR, 1))
                v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, AppAction.SAVE_CAR, 1))
                return v
            }

            val since = if (isToday(car.ts)) clock(car.ts) else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(car.ts)) + " " + clock(car.ts)
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.parkmap_widget_parked))
            v.setTextViewText(R.id.widget_button, ctx.getString(R.string.parkmap_widget_open))
            v.setOnClickPendingIntent(R.id.widget_button, openApp(ctx, AppAction.SHOW_CAR, 2))
            v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, AppAction.SHOW_CAR, 2))

            val expiry = car.expiry ?: 0L
            if (expiry > now) {
                v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_expires, clock(expiry)))
                // Conto alla rovescia gestito dal sistema: nessun aggiornamento ogni secondo
                v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + (expiry - now), null, true)
                v.setChronometerCountDown(R.id.widget_timer, true)
                v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
            } else if (expiry > 0) {
                v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_expired, clock(expiry)))
                v.setViewVisibility(R.id.widget_timer, View.GONE)
            } else {
                v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_since, since))
                v.setViewVisibility(R.id.widget_timer, View.GONE)
            }
            return v
        }
    }
}
