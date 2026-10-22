package io.github.andreadp271.parkmap.data

import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** Formattazioni condivise (distanze, durate, orari, importi), nella lingua scelta. */
object Fmt {
    fun distance(m: Double): String {
        if (m < 1000) return "${(m / 10).roundToInt() * 10} m"
        val f = DecimalFormat(if (m < 10000) "0.#" else "0", DecimalFormatSymbols(T.locale))
        return f.format(m / 1000) + " km"
    }

    /** Minuti a piedi: in linea d'aria × 1,25 (le strade non sono dritte), 80 m al minuto. */
    fun walkingMinutes(m: Double): Int = max(1, (m * 1.25 / 80).roundToInt())

    fun duration(ms: Long): String {
        val totalMin = max(0L, (ms / 60000.0).roundToInt().toLong())
        val d = totalMin / 1440; val h = totalMin % 1440 / 60; val m = totalMin % 60
        if (d > 0) return "$d ${if (d == 1L) t("giorno") else t("giorni")}${if (h > 0) " $h h" else ""}"
        return if (h > 0) "$h h${if (m > 0) " $m min" else ""}" else "$m min"
    }

    fun clock(ts: Long): String = SimpleDateFormat("HH:mm", T.locale).format(Date(ts))

    fun parkedAt(ts: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance()
        if (d.get(Calendar.YEAR) == today.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR))
            return t("oggi alle {time}", "time" to clock(ts))
        return t("{date} alle {time}", "date" to SimpleDateFormat("EEE d MMM", T.locale).format(Date(ts)), "time" to clock(ts))
    }

    fun dateTime(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, T.locale).format(Date(ts))

    fun money(amount: Double, currency: String): String = try {
        NumberFormat.getCurrencyInstance(T.locale).apply { this.currency = Currency.getInstance(currency) }.format(amount)
    } catch (e: Exception) {
        "%.2f %s".format(T.locale, amount, currency)
    }

    fun kw(kw: Double): String = DecimalFormat("0.#", DecimalFormatSymbols(T.locale)).format(kw) + " kW"
}
