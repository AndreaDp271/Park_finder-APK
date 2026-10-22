package io.github.andreadp271.parkmap.data

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Costo "in questo momento" di un parcheggio. */
enum class FeeState { FREE, PAID, UNKNOWN }

/**
 * Interpretazione dei tag OSM dei parcheggi: orari di pagamento, filtri, titoli e riassunti,
 * tariffe e costo stimato. È la traduzione fedele della logica di index.html.
 */
object OsmRules {
    private val DAYS = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    private val RESTRICTED = setOf("private", "customers", "destination", "permit", "no")
    private val NO_LIMIT = setOf("no", "none", "unlimited", "0")

    // ─── Orari OSM: "gratis/a pagamento in questo momento" ───

    /** Come split(';'), ma ignora i ';' dentro le parentesi. */
    fun splitRules(str: String?): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in str.orEmpty()) {
            if (ch == '(') depth++
            if (ch == ')') depth--
            if (ch == ';' && depth <= 0) { parts += cur.toString(); cur.setLength(0) } else cur.append(ch)
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun daysMatch(daysStr: String?, day: Int): Boolean {
        if (daysStr.isNullOrEmpty()) return true
        return daysStr.split(',').any { seg ->
            val p = seg.trim().split('-')
            val ia = DAYS.indexOf(p[0])
            val ib = if (p.size > 1) DAYS.indexOf(p[1]) else ia
            if (ia < 0 || ib < 0) false
            else if (ia <= ib) day in ia..ib else (day >= ia || day <= ib)
        }
    }

    private val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su)"
    private val PART_RE = Regex("^($DAY(?:-$DAY)?(?:\\s*,\\s*$DAY(?:-$DAY)?)*)?\\s*(.*)$")
    private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$")
    private val PH_RE = Regex("^PH\\b")
    private val PH_PREFIX = Regex("^,?\\s*PH\\s*")
    private val OFF_RE = Regex("^(off|closed)$", RegexOption.IGNORE_CASE)

    fun conditionMatches(cond: String, date: Calendar): Boolean {
        val day = date.get(Calendar.DAY_OF_WEEK) - 1
        val prevDay = (day + 6) % 7
        val mins = date.get(Calendar.HOUR_OF_DAY) * 60 + date.get(Calendar.MINUTE)
        return cond.split(';').any anyPart@{ raw ->
            val part = raw.trim()
            if (part.isEmpty() || PH_RE.containsMatchIn(part)) return@anyPart false
            if (part == "24/7") return@anyPart true
            val m = PART_RE.matchEntire(part) ?: return@anyPart false
            val daysStr = m.groups[1]?.value
            val times = (m.groups[2]?.value ?: "").replace(PH_PREFIX, "").trim()
            if (OFF_RE.matches(times)) return@anyPart false
            if (times.isEmpty()) return@anyPart daysMatch(daysStr, day)
            times.split(',').any anyRange@{ r ->
                val tm = TIME_RE.matchEntire(r.trim()) ?: return@anyRange false
                val start = tm.groupValues[1].toInt() * 60 + tm.groupValues[2].toInt()
                val end = tm.groupValues[3].toInt() * 60 + tm.groupValues[4].toInt()
                if (end > start) daysMatch(daysStr, day) && mins >= start && mins < end
                // Fascia che attraversa la mezzanotte (es. 20:00-08:00)
                else (daysMatch(daysStr, day) && mins >= start) || (daysMatch(daysStr, prevDay) && mins < end)
            }
        }
    }

    fun isRestricted(tags: Map<String, String>): Boolean = tags["access"] in RESTRICTED || tags["vehicle"] == "permit"

    fun hasMaxstay(tags: Map<String, String>): Boolean {
        val ms = tags["maxstay"].orEmpty().trim().lowercase()
        return ms.isNotEmpty() && ms !in NO_LIMIT
    }

    fun hasDiscRule(tags: Map<String, String>): Boolean =
        tags["parking:condition"].orEmpty().lowercase().contains("disc") || hasMaxstay(tags) || !tags["maxstay:conditional"].isNullOrEmpty()

    private val FEE_RULE_RE = Regex("^(yes|no)\\s*@\\s*\\(?(.+?)\\)?\\s*$", RegexOption.IGNORE_CASE)

    fun feeStateAt(tags: Map<String, String>, date: Calendar): FeeState {
        var state: FeeState? = when (tags["fee"]) { "no" -> FeeState.FREE; "yes" -> FeeState.PAID; else -> null }
        val conditional = tags["fee:conditional"]
        if (!conditional.isNullOrEmpty()) {
            var matched = false
            splitRules(conditional).forEach { rule ->
                val m = FEE_RULE_RE.matchEntire(rule)
                if (m == null || matched) return@forEach
                val value = if (m.groupValues[1].lowercase() == "yes") FeeState.PAID else FeeState.FREE
                if (conditionMatches(m.groupValues[2], date)) { state = value; matched = true }
                else if (state == null) state = if (value == FeeState.PAID) FeeState.FREE else FeeState.PAID
            }
        }
        if (state == null && hasDiscRule(tags)) state = FeeState.FREE
        return state ?: FeeState.UNKNOWN
    }

    // ─── Filtri ───

    private fun capacityFlag(v: String?): Boolean = v == "yes" || (v?.trim()?.toIntOrNull() ?: 0) > 0

    fun matchesFilter(tags: Map<String, String>, filter: Filter, now: Calendar): Boolean {
        val kind = kindOf(tags)
        if (kind == Kind.METER) return false
        val forCars = kind == Kind.PARKING || kind == Kind.SPACE
        val restricted = isRestricted(tags)
        return when (filter) {
            Filter.FREE -> forCars && !restricted && ((tags["fee"] == "no" && !tags.containsKey("fee:conditional")) ||
                (!tags.containsKey("fee") && !tags.containsKey("fee:conditional") && hasDiscRule(tags)))
            Filter.FREE_NOW -> forCars && !restricted && feeStateAt(tags, now) == FeeState.FREE
            Filter.DISC -> forCars && !restricted && hasDiscRule(tags)
            Filter.DISABLED -> forCars && (tags["parking_space"] == "disabled" || capacityFlag(tags["capacity:disabled"]))
            Filter.EV -> kind == Kind.CHARGING || (forCars && (tags["parking_space"] == "charging" || capacityFlag(tags["capacity:charging"])))
            Filter.MOTO -> kind == Kind.MOTO || (forCars && (tags["parking_space"] == "motorcycle" ||
                capacityFlag(tags["capacity:motorcycle"]) || tags["motorcycle"] == "designated"))
            Filter.BIKE -> kind == Kind.BIKE
        }
    }

    /** Livello (Livelli → "Livelli visibili") a cui appartiene l'elemento. */
    fun layerOf(tags: Map<String, String>): Layer? = when (kindOf(tags)) {
        Kind.PARKING -> Layer.AREAS
        Kind.SPACE -> Layer.SPACES
        Kind.METER -> Layer.METERS
        Kind.CHARGING -> Layer.CHARGING
        Kind.MOTO -> Layer.MOTO
        Kind.BIKE -> Layer.BIKES
        Kind.OTHER -> null
    }

    /** Un elemento si vede se il suo livello è acceso (un filtro attivo mostra il suo livello anche se spento) e passa i filtri. */
    fun isShown(tags: Map<String, String>, layers: Set<Layer>, filters: Set<Filter>, now: Calendar): Boolean {
        val layer = layerOf(tags) ?: return false
        val on = layer in layers || (layer == Layer.CHARGING && Filter.EV in filters) ||
            (layer == Layer.MOTO && Filter.MOTO in filters) || (layer == Layer.BIKES && Filter.BIKE in filters)
        return on && matchesFilters(tags, filters, now)
    }

    fun matchesFilters(tags: Map<String, String>, active: Set<Filter>, now: Calendar = Calendar.getInstance()): Boolean =
        active.isEmpty() || active.any { matchesFilter(tags, it, now) }

    // ─── Traduzione dei valori OSM ───

    fun translateTime(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        if (T.lang == "en") return str.replace("PH", "public holidays").replace(Regex("\\boff\\b"), "closed").replace("24/7", "always open")
        return str.replace("Mo", "Lun").replace("Tu", "Mar").replace("We", "Mer").replace("Th", "Gio").replace("Fr", "Ven")
            .replace("Sa", "Sab").replace("Su", "Dom").replace("PH", "Festivi").replace("off", "chiuso").replace("24/7", "sempre aperto")
    }

    fun maxstayText(v: String?): String {
        val raw = v.orEmpty().trim()
        val s = raw.lowercase()
        if (raw.isEmpty()) return ""
        if (s in setOf("no", "none", "unlimited")) return t("senza limite")
        val m = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(hour|hours|minute|minutes|day|days|week|weeks|month|months|year|years)$").matchEntire(s) ?: return raw
        val numStr = m.groupValues[1]
        val num = numStr.replace(',', '.').toDouble()
        val one = abs(num - 1) < 1e-9
        return when (m.groupValues[2]) {
            "hour", "hours" -> if (one) t("1 ora") else t("{n} ore", "n" to numStr)
            "minute", "minutes" -> if (one) t("1 minuto") else t("{n} minuti", "n" to numStr)
            "day", "days" -> if (one) t("1 giorno") else t("{n} giorni", "n" to numStr)
            "week", "weeks" -> if (one) t("1 settimana") else t("{n} settimane", "n" to numStr)
            "month", "months" -> if (one) t("1 mese") else t("{n} mesi", "n" to numStr)
            "year", "years" -> if (one) t("1 anno") else t("{n} anni", "n" to numStr)
            else -> raw
        }
    }

    private class Conditional(val value: String, val condition: String)

    private fun parseConditional(rule: String): Conditional? {
        val m = Regex("^(.+?)\\s*@\\s*\\((.+)\\)\\s*$").matchEntire(rule) ?: Regex("^(.+?)\\s*@\\s*(.+?)\\s*$").matchEntire(rule) ?: return null
        return Conditional(m.groupValues[1].trim(), translateTime(m.groupValues[2]).trim())
    }

    fun maxstayConditional(str: String): List<String> = splitRules(str).map { rule ->
        val p = parseConditional(rule) ?: return@map t("Tempo massimo (cond): ") + translateTime(rule)
        t("Tempo massimo: {v} nei giorni/orari: {c}", "v" to maxstayText(p.value), "c" to p.condition)
    }

    /** Righe "<sì> nei giorni/orari: ..." / "<no> nei giorni/orari: ..." per access:conditional. */
    fun yesNoConditional(str: String, yesLabel: String, noLabel: String): List<String> = splitRules(str).map { rule ->
        val p = parseConditional(rule) ?: return@map translateTime(rule)
        val value = p.value.lowercase()
        val label = when (value) { "yes" -> yesLabel; "no" -> noLabel; else -> p.value }
        t("{label} nei giorni/orari: {c}", "label" to label, "c" to p.condition)
    }

    private class Range(val start: Int, val end: Int)

    private fun parseRanges(s: String): List<Range> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { r ->
        Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$").matchEntire(r)?.let {
            Range(it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt(), it.groupValues[3].toInt() * 60 + it.groupValues[4].toInt())
        }
    }

    private fun complement(ranges: List<Range>): List<Range> {
        val expanded = mutableListOf<Range>()
        ranges.forEach { r ->
            if (r.end <= r.start) { expanded += Range(r.start, 1440); expanded += Range(0, r.end) } else expanded += r
        }
        expanded.sortBy { it.start }
        val merged = mutableListOf<Range>()
        expanded.forEach { r ->
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end) merged[merged.size - 1] = Range(last.start, max(last.end, r.end)) else merged += Range(r.start, r.end)
        }
        val out = mutableListOf<Range>()
        var cursor = 0
        merged.forEach { r ->
            if (r.start > cursor) out += Range(cursor, r.start)
            cursor = max(cursor, r.end)
        }
        if (cursor < 1440) out += Range(cursor, 1440)
        return out
    }

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    /** Da "Mo-Sa 13:00-15:00,20:00-08:00; Su" (quando è gratis) all'orario complementare (quando si paga). */
    private fun invertToPaidSchedule(raw: String): String {
        val entries = mutableListOf<String>()
        raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { group ->
            val m = Regex("^([A-Za-z]{2}(?:-[A-Za-z]{2})?(?:,\\s*[A-Za-z]{2}(?:-[A-Za-z]{2})?)*)\\s*(.*)$").matchEntire(group) ?: return@forEach
            val timePart = m.groupValues[2].trim()
            if (timePart.isEmpty()) return@forEach // tutto il giorno gratis: nessun orario a pagamento
            val ranges = parseRanges(timePart)
            if (ranges.isEmpty()) return@forEach
            val paid = complement(ranges)
            if (paid.isEmpty()) return@forEach
            entries += translateTime(m.groupValues[1]) + " " + paid.joinToString(",") { hhmm(it.start) + "-" + (if (it.end == 1440) "24:00" else hhmm(it.end)) }
        }
        return entries.joinToString("; ")
    }

    /** fee:conditional come "A pagamento nei giorni/orari: ..." (all'utente interessa quando paga). */
    fun feeConditional(str: String): List<String> = splitRules(str).map { rule ->
        val m = Regex("^(.+?)\\s*@\\s*\\((.+)\\)\\s*$").matchEntire(rule) ?: Regex("^(.+?)\\s*@\\s*(.+?)\\s*$").matchEntire(rule)
            ?: return@map translateTime(rule)
        val value = m.groupValues[1].trim().lowercase()
        val rawCond = m.groupValues[2].trim()
        when (value) {
            "yes" -> t("A pagamento nei giorni/orari: {c}", "c" to translateTime(rawCond))
            "no" -> {
                val paid = invertToPaidSchedule(rawCond)
                if (paid.isNotEmpty()) t("A pagamento nei giorni/orari: {c}", "c" to paid)
                else t("Gratuito nei giorni/orari: {c}", "c" to translateTime(rawCond))
            }
            else -> t("{label} nei giorni/orari: {c}", "label" to value, "c" to translateTime(rawCond))
        }
    }

    // ─── Titoli e riassunti ───

    fun specialStallName(ps: String?): String {
        val names = mapOf(
            "disabled" to "Disabili", "charging" to "Ricarica EV", "loading" to "Carico/Scarico", "delivery" to "Carico/Scarico",
            "hgv" to "Mezzi pesanti", "parent" to "Famiglie", "women" to "Donne", "emergency" to "Emergenza",
            "police" to "Polizia", "motorcycle" to "Moto", "bus" to "Bus",
        )
        return names[ps]?.let { t(it) } ?: ""
    }

    fun title(tags: Map<String, String>): String = when (kindOf(tags)) {
        Kind.METER -> tags["name"] ?: tags["ref"] ?: t("Parcometro")
        Kind.CHARGING -> tags["name"] ?: tags["operator"] ?: tags["network"] ?: tags["brand"] ?: t("Colonnina di ricarica")
        Kind.MOTO -> tags["name"] ?: t("Parcheggio moto")
        Kind.BIKE -> tags["name"] ?: t("Parcheggio bici")
        Kind.SPACE -> tags["name"] ?: tags["ref"] ?: specialStallName(tags["parking_space"]).ifEmpty { t("Stallo speciale") }
        else -> tags["name"] ?: tags["ref"] ?: t("Area di sosta")
    }

    /** Riassunto di una riga: costo (anche "in questo momento"), disco, posti. */
    fun summary(tags: Map<String, String>): String {
        val parts = mutableListOf<String>()
        val kind = kindOf(tags)
        when (kind) {
            Kind.CHARGING -> {
                val sockets = chargingSockets(tags)
                val count = sockets.sumOf { it.count ?: 1 }
                if (count > 0) parts += if (count == 1) t("1 presa") else t("{n} prese", "n" to count)
                chargingMaxKw(tags)?.let { parts += t("fino a {kw}", "kw" to Fmt.kw(it)) }
                if (tags["fee"] == "no") parts += t("Gratuita") else if (tags["fee"] == "yes") parts += t("A pagamento")
                return parts.joinToString(" · ").ifEmpty { t("Colonnina di ricarica") }
            }
            Kind.MOTO, Kind.BIKE -> {
                if (tags.containsKey("name")) parts += if (kind == Kind.MOTO) t("Parcheggio moto") else t("Parcheggio bici")
                tags["capacity"]?.let { parts += t("{n} posti", "n" to it) }
                if (tags["covered"] == "yes") parts += t("Coperto")
                if (tags["fee"] == "yes") parts += t("A pagamento")
                return parts.joinToString(" · ").ifEmpty { t("Posti non indicati") }
            }
            else -> {}
        }
        if (kind == Kind.SPACE) parts += specialStallName(tags["parking_space"]).ifEmpty { t("Stallo speciale") }
        if (isRestricted(tags)) parts += t("Accesso limitato")
        else if (tags.containsKey("fee:conditional")) parts += if (feeStateAt(tags, Calendar.getInstance()) == FeeState.FREE) t("Gratis adesso") else t("A pagamento adesso")
        else if (tags["fee"] == "no") parts += t("Gratuito")
        else if (tags["fee"] == "yes") parts += t("A pagamento")
        if (hasDiscRule(tags)) parts += t("Disco orario")
        tags["capacity"]?.let { parts += t("{n} posti", "n" to it) }
        return parts.joinToString(" · ").ifEmpty { t("Costo non indicato") }
    }

    // ─── Colori per tipo (stessi della mappa web) ───

    class ElTheme(val fill: Int, val stroke: Int, val icon: String)

    private fun c(hex: String): Int = 0xFF000000.toInt() or hex.removePrefix("#").toInt(16)

    fun theme(tags: Map<String, String>): ElTheme {
        val ps = tags["parking_space"].orEmpty()
        when (tags["amenity"]) {
            "charging_station" -> return ElTheme(c("#34d399"), c("#047857"), "ev")
            "motorcycle_parking" -> return ElTheme(c("#f87171"), c("#b91c1c"), "bike")
            "bicycle_parking" -> return ElTheme(c("#a3e635"), c("#4d7c0f"), "bike")
            "vending_machine" -> return ElTheme(c("#fdba74"), c("#f97316"), "ticket")
        }
        if (tags["amenity"] == "parking_space" && ps != "normal") {
            return when (ps) {
                "disabled" -> ElTheme(c("#fde047"), c("#eab308"), "accessible")
                "charging" -> ElTheme(c("#7dd3fc"), c("#0284c7"), "zap")
                "emergency", "police" -> ElTheme(c("#60a5fa"), c("#1e40af"), "shield")
                "motorcycle" -> ElTheme(c("#f87171"), c("#b91c1c"), "bike")
                "delivery", "loading", "hgv" -> ElTheme(c("#fed7aa"), c("#d97706"), "truck")
                "parent", "women" -> ElTheme(c("#fbcfe8"), c("#db2777"), "baby")
                "bus" -> ElTheme(c("#67e8f9"), c("#0e7490"), "bus")
                else -> ElTheme(c("#9ca3af"), c("#374151"), "parking")
            }
        }
        val fee = tags["fee"].orEmpty()
        return when {
            tags["access"] in setOf("private", "customers", "destination", "permit") || tags["vehicle"] == "permit" -> ElTheme(c("#fb923c"), c("#ea580c"), "parking")
            fee == "yes" || fee == "conditional" || tags.containsKey("fee:conditional") -> ElTheme(c("#60a5fa"), c("#1d4ed8"), "parking")
            hasDiscRule(tags) -> ElTheme(c("#a78bfa"), c("#7c3aed"), "parking")
            fee == "no" -> ElTheme(c("#4ade80"), c("#15803d"), "parking")
            else -> ElTheme(c("#94a3b8"), c("#64748b"), "parking")
        }
    }

    /** Colori di riempimento e contorno con cui si disegna l'elemento sulla mappa. */
    fun mapColors(tags: Map<String, String>): Pair<Int, Int> { // (fill, stroke)
        val kind = kindOf(tags)
        if (kind == Kind.SPACE) {
            return when (tags["parking_space"]) {
                "disabled" -> c("#fde047") to c("#eab308")
                "charging" -> c("#f0f9ff") to c("#0284c7")
                "emergency", "police" -> c("#60a5fa") to c("#1e40af")
                "delivery", "loading", "hgv" -> c("#fed7aa") to c("#d97706")
                "parent", "women" -> c("#fbcfe8") to c("#db2777")
                "bus" -> c("#67e8f9") to c("#0e7490")
                else -> c("#6b7280") to c("#374151")
            }
        }
        if (kind == Kind.MOTO) return c("#fca5a5") to c("#b91c1c")
        if (kind == Kind.BIKE) return c("#bef264") to c("#4d7c0f")
        if (kind == Kind.METER) return c("#f97316") to c("#111827")
        val t = theme(tags)
        return t.fill to t.stroke
    }

    // ─── Colonnine di ricarica ───

    private val SOCKET_NAMES = mapOf(
        "type2" to "Type 2", "type2_cable" to "Type 2 (cavo)", "type2_combo" to "CCS Combo 2", "chademo" to "CHAdeMO",
        "type1" to "Type 1", "type1_combo" to "CCS Combo 1", "tesla_supercharger" to "Tesla Supercharger",
        "tesla_destination" to "Tesla Destination", "tesla_standard" to "Tesla", "nacs" to "NACS (Tesla)",
        "schuko" to "Schuko", "typee" to "Type E", "type3a" to "Type 3A", "type3c" to "Type 3C", "cee_blue" to "CEE blu",
        "cee_red_16a" to "CEE rossa 16A", "cee_red_32a" to "CEE rossa 32A", "gb_dc" to "GB/T DC", "gb_ac" to "GB/T AC",
        "bs1363" to "BS 1363", "as3112" to "AS/NZS 3112",
    )

    class Socket(val name: String, val count: Int?, val kw: Double?)

    fun parsePowerKw(v: String?): Double? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kw|w|kva)?", RegexOption.IGNORE_CASE).find(v.orEmpty()) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDouble()
        return if (m.groupValues[2].lowercase() == "w") n / 1000 else n
    }

    fun chargingSockets(tags: Map<String, String>): List<Socket> {
        val out = mutableListOf<Socket>()
        tags.keys.forEach { k ->
            val m = Regex("^socket:([a-z0-9_]+)$", RegexOption.IGNORE_CASE).matchEntire(k) ?: return@forEach
            if (tags[k] == "no" || tags[k] == "0") return@forEach
            val type = m.groupValues[1].lowercase()
            val outputs = tags["$k:output"].orEmpty().split(';').mapNotNull { parsePowerKw(it) }
            val count = tags[k]?.trim()?.toIntOrNull()
            out += Socket(SOCKET_NAMES[type]?.let { t(it) } ?: type.replace('_', ' '), count?.takeIf { it > 0 }, outputs.maxOrNull())
        }
        return out.sortedByDescending { it.kw ?: 0.0 }
    }

    fun chargingMaxKw(tags: Map<String, String>): Double? {
        val list = chargingSockets(tags).mapNotNull { it.kw }.toMutableList()
        parsePowerKw(tags["charging_station:output"])?.let { list += it }
        return list.maxOrNull()
    }

    // ─── Tariffe e costo stimato ───

    private val CURRENCIES = mapOf("€" to "EUR", "$" to "USD", "£" to "GBP")

    class Rate(val amount: Double, val currency: String, val minutes: Double)

    private val RATE_RE = Regex(
        "^(?:(EUR|USD|GBP|CHF|€|\\$|£)\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(EUR|USD|GBP|CHF|€|\\$|£)?\\s*(?:/|per\\s+|a\\s+)\\s*(\\d+(?:[.,]\\d+)?)?\\s*(hours?|hrs?|h|ora|ore|minutes?|mins?|minuti|m|days?|d|giorn[oi])\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parseRate(str: String?): Rate? {
        val m = RATE_RE.find(str.orEmpty().trim()) ?: return null
        if (m.range.first != 0) return null
        val n = m.groupValues[4].takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDouble() ?: 1.0
        val unit = m.groupValues[5].lowercase()
        val minutes = when {
            Regex("^(h|hrs?|hours?|ora|ore)$").matches(unit) -> 60 * n
            Regex("^(m|mins?|minutes?|minuti)$").matches(unit) -> n
            else -> 1440 * n
        }
        val cur = (m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { "EUR" }).uppercase()
        return Rate(m.groupValues[2].replace(',', '.').toDouble(), CURRENCIES[cur] ?: cur, minutes)
    }

    /** "5.00 EUR/hour" → "5,00 €/ora"; se la tariffa non è leggibile resta com'è su OSM. */
    fun formatChargeRule(rule: String): String {
        val m = Regex("^(.+?)\\s*@\\s*\\(?(.+?)\\)?\\s*$").matchEntire(rule)
        val rate = parseRate(m?.groupValues?.get(1) ?: rule) ?: return translateTime(rule)
        val unit = when {
            rate.minutes == 60.0 -> t("ora")
            rate.minutes == 1440.0 -> t("giorno")
            rate.minutes % 60 == 0.0 -> "${(rate.minutes / 60).toInt()} h"
            else -> "${rate.minutes.toInt()} min"
        }
        return Fmt.money(rate.amount, rate.currency) + "/" + unit + (if (m != null) " · " + translateTime(m.groupValues[2]) else "")
    }

    fun chargeLines(tags: Map<String, String>): List<String> =
        (splitRules(tags["charge"]) + splitRules(tags["charge:conditional"])).map { formatChargeRule(it) }

    private class Conditioned(val rate: Rate, val cond: String)
    private class ChargeRules(val base: Rate?, val dailyCap: Rate?, val conditional: List<Conditioned>)

    private fun chargeRules(tags: Map<String, String>): ChargeRules {
        val rates = splitRules(tags["charge"]).mapNotNull { parseRate(it) }
        val conditional = splitRules(tags["charge:conditional"]).mapNotNull { r ->
            val m = Regex("^(.+?)\\s*@\\s*\\(?(.+?)\\)?\\s*$").matchEntire(r) ?: return@mapNotNull null
            parseRate(m.groupValues[1])?.let { Conditioned(it, m.groupValues[2]) }
        }
        return ChargeRules(
            base = rates.firstOrNull { it.minutes < 1440 } ?: rates.firstOrNull(),
            dailyCap = rates.firstOrNull { it.minutes >= 1440 && rates.any { o -> o.minutes < 1440 } },
            conditional = conditional,
        )
    }

    fun hasCostInfo(tags: Map<String, String>): Boolean {
        val kind = kindOf(tags)
        if (kind != Kind.PARKING && kind != Kind.SPACE) return false
        return tags.containsKey("charge") || tags.containsKey("charge:conditional") || tags.containsKey("fee:conditional") || tags["fee"] == "yes"
    }

    class Estimate(val total: Double, val currency: String, val freeMinutes: Int)

    /** Costo stimato di una sosta di [minutes] da [from], o null se la tariffa non è leggibile. */
    fun estimateCost(tags: Map<String, String>, minutes: Int, from: Calendar): Estimate? {
        val rules = chargeRules(tags)
        val step = 5
        var total = 0.0; var freeMinutes = 0; var paidMinutes = 0
        var currency: String? = null
        var dailyOnly: Rate? = null
        var m = 0
        while (m < minutes) {
            val at = (from.clone() as Calendar).apply { add(Calendar.MINUTE, m) }
            val st = min(step, minutes - m)
            m += step
            if (feeStateAt(tags, at) == FeeState.FREE) { freeMinutes += st; continue }
            val rule = rules.conditional.firstOrNull { conditionMatches(it.cond, at) }
            val rate = rule?.rate ?: rules.base ?: return null
            currency = currency ?: rate.currency
            paidMinutes += st
            if (rate.minutes >= 1440) dailyOnly = rate else total += rate.amount * st / rate.minutes
        }
        dailyOnly?.let { total += it.amount * ceil(paidMinutes / 1440.0) }
        rules.dailyCap?.let { total = min(total, it.amount * ceil(minutes / 1440.0)) }
        return Estimate(total, currency ?: rules.base?.currency ?: "EUR", freeMinutes)
    }
}
