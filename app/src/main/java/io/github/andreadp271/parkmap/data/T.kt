package io.github.andreadp271.parkmap.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Lingua dell'app: italiano o inglese. Come nella web app, i testi nel codice sono in italiano
 * e [t] li traduce con il dizionario inglese (assets/en.json, generato da index.html).
 */
object T {
    @Volatile var lang: String = "it"
        private set
    @Volatile var locale: Locale = Locale.ITALY
        private set
    private var en: Map<String, String> = emptyMap()

    fun init(ctx: Context, setting: String) {
        if (en.isEmpty()) {
            en = runCatching {
                val o = JSONObject(ctx.assets.open("en.json").bufferedReader().use { it.readText() })
                buildMap { o.keys().forEach { k -> put(k, o.getString(k)) } }
            }.getOrDefault(emptyMap())
        }
        apply(setting)
    }

    fun apply(setting: String) {
        val device = Locale.getDefault()
        lang = when (setting) {
            "it", "en" -> setting
            else -> if (device.language == "it") "it" else "en"
        }
        locale = if (lang == "it") Locale.ITALY else if (device.language == "en") device else Locale.UK
    }

    fun tr(text: String): String = if (lang == "en") en[text] ?: text else text
}

/** Traduce un testo italiano e sostituisce i segnaposto `{nome}`. */
fun t(text: String, vararg vars: Pair<String, Any?>): String {
    var out = T.tr(text)
    if (vars.isNotEmpty()) {
        out = Regex("""\{(\w+)}""").replace(out) { m -> vars.firstOrNull { it.first == m.groupValues[1] }?.second?.toString() ?: m.value }
    }
    return out
}
