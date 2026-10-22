package io.github.andreadp271.parkmap.data

import java.util.Calendar

/** Chip informativo della scheda (tono: "", ok, paid, time, ev, warn). */
class Chip(val text: String, val tone: String = "", val icon: String? = null)

class InfoRow(val label: String, val lines: List<String>)

class AccessInfo(val title: String, val restricted: Boolean, val lines: List<String>)

/** Contenuto della scheda di un parcheggio, pronto da disegnare (equivalente del popup della web app). */
class Description(
    val el: OsmElement,
    val theme: OsmRules.ElTheme,
    val title: String,
    val subtitle: String,
    val chips: List<Chip>,
    val rows: List<InfoRow>,
    val access: AccessInfo?,
    val costEstimator: Boolean,
    val muted: String?,
)

object Describe {
    private val PARKING_TYPES = mapOf(
        "surface" to "A raso", "multi-storey" to "Multipiano", "underground" to "Sotterraneo", "rooftop" to "Sul tetto",
        "street_side" to "Lungo la strada", "lane" to "Lungo la strada", "on_kerb" to "Lungo la strada", "half_on_kerb" to "Lungo la strada",
        "garage_boxes" to "Box", "carports" to "Tettoie", "layby" to "Piazzola",
    )
    private val BICYCLE_PARKING_TYPES = mapOf(
        "stands" to "Archetti", "wall_loops" to "Ferma-ruote", "rack" to "Rastrelliera", "shed" to "Deposito coperto",
        "lockers" to "Armadietti", "building" to "Edificio", "bollard" to "Paletti", "anchors" to "Ancoraggi",
        "two_tier" to "Su due livelli", "ground_slots" to "Ferma-ruote a terra", "informal" to "Informale",
    )
    private val PAYMENT = mapOf(
        "cash" to "Contanti", "coins" to "Monete", "notes" to "Banconote", "credit_cards" to "Carte di credito",
        "debit_cards" to "Bancomat / carte di debito", "cards" to "Carte", "contactless" to "Contactless", "visa" to "Visa",
        "mastercard" to "Mastercard", "maestro" to "Maestro", "apple_pay" to "Apple Pay", "google_pay" to "Google Pay",
        "sms" to "SMS", "phone" to "Telefono", "app" to "App",
    )

    fun accessLabel(tags: Map<String, String>): String = when {
        tags["access"] == "private" -> t("Privato")
        tags["access"] == "customers" -> t("Solo clienti")
        tags["access"] == "permissive" -> t("Autorizzato")
        tags["access"] == "destination" -> t("Solo residenti")
        tags["access"] == "permit" || tags["vehicle"] == "permit" -> t("Solo con permesso")
        tags["access"] == "no" -> t("Vietato")
        else -> t("Pubblico")
    }

    private fun paymentText(tags: Map<String, String>): String = tags.keys.filter { it.startsWith("payment:") }.mapNotNull { k ->
        val v = tags[k].orEmpty().lowercase()
        if (v != "yes" && v != "only") return@mapNotNull null
        val method = k.removePrefix("payment:")
        val label = PAYMENT[method.lowercase()]?.let { t(it) } ?: method.replace('_', ' ')
        label + if (v == "only") " " + t("(solo)") else ""
    }.joinToString(", ")

    private fun feeChip(tags: Map<String, String>, freeLabel: String? = null): Chip? {
        if (tags.containsKey("fee:conditional")) {
            return if (OsmRules.feeStateAt(tags, Calendar.getInstance()) == FeeState.FREE) Chip(t("Gratis adesso"), "ok", "clock")
            else Chip(t("A pagamento adesso"), "paid", "clock")
        }
        if (tags["fee"] == "no") return Chip(freeLabel ?: t("Gratuito"), "ok", "circle-check")
        if (tags["fee"] == "yes") return Chip(t("A pagamento"), "paid", "coins")
        return null
    }

    private fun row(label: String, vararg lines: String?): InfoRow? {
        val l = lines.filterNotNull().filter { it.isNotBlank() }
        return if (l.isEmpty()) null else InfoRow(label, l)
    }

    private fun rowList(label: String, lines: List<String>): InfoRow? = row(label, *lines.toTypedArray())

    /** Scheda dell'elemento; [distanceM] = distanza dalla posizione dell'utente, se nota. */
    fun of(el: OsmElement, distanceM: Double?): Description {
        val tags = el.tags
        val theme = OsmRules.theme(tags)
        val kind = el.kind
        val dist = distanceM?.let { Fmt.distance(it) }
        fun sub(vararg parts: String?) = (parts.filterNotNull().filter { it.isNotEmpty() } + listOfNotNull(dist?.let { t("a {d} da te", "d" to it) })).joinToString(" · ")
        val title = OsmRules.title(tags)

        return when (kind) {
            Kind.CHARGING -> {
                val sockets = OsmRules.chargingSockets(tags)
                val kw = OsmRules.chargingMaxKw(tags)
                val count = sockets.sumOf { it.count ?: 1 }
                val network = tags["network"]?.takeIf { it != tags["operator"] && it != tags["name"] }
                val operator = listOfNotNull(tags["operator"]?.takeIf { it != tags["name"] }, network).joinToString(" · ")
                Description(
                    el, theme, title,
                    sub(if (tags.containsKey("name") || tags.containsKey("operator") || tags.containsKey("network") || tags.containsKey("brand")) t("Colonnina di ricarica") else null),
                    listOfNotNull(
                        kw?.let { Chip(t("fino a {kw}", "kw" to Fmt.kw(it)), "ev", "zap") },
                        if (kw != null && kw >= 50) Chip(t("Ricarica rapida"), "ev", "gauge") else null,
                        if (count > 0) Chip(if (count == 1) t("1 presa") else t("{n} prese", "n" to count), "", "plug") else null,
                        feeChip(tags, t("Gratuita")),
                        if (OsmRules.isRestricted(tags)) Chip(accessLabel(tags), "warn", "lock") else null,
                    ),
                    listOfNotNull(
                        rowList(t("Prese"), sockets.map { (if (it.count != null) "${it.count} × " else "") + it.name + (it.kw?.let { k -> " · " + Fmt.kw(k) } ?: "") }),
                        rowList(t("Tariffa"), OsmRules.chargeLines(tags)),
                        row(t("Orari"), tags["opening_hours"]?.let { OsmRules.translateTime(it) }),
                        row(t("Si paga con"), paymentText(tags)),
                        row(t("Veicoli insieme"), tags["capacity"]),
                        row(t("Gestore"), operator),
                        row(t("Note"), tags["note"]),
                    ),
                    null, false,
                    if (sockets.isEmpty() && kw == null) t("Prese e potenza non indicate su OpenStreetMap.") else null,
                )
            }
            Kind.MOTO, Kind.BIKE -> {
                val moto = kind == Kind.MOTO
                val bikeType = tags["bicycle_parking"]?.let { BICYCLE_PARKING_TYPES[it] }
                Description(
                    el, theme, title,
                    sub(
                        if (tags.containsKey("name")) (if (moto) t("Parcheggio moto") else t("Parcheggio bici")) else null,
                        bikeType?.let { t(it) },
                    ),
                    listOfNotNull(
                        if (OsmRules.isRestricted(tags)) Chip(accessLabel(tags), "warn", "lock") else null,
                        feeChip(tags),
                        tags["capacity"]?.let { Chip(t("{n} posti", "n" to it), "", "bike") },
                        if (tags["covered"] == "yes") Chip(t("Coperto"), "", "umbrella") else null,
                    ),
                    listOfNotNull(
                        rowList(t("Tariffa"), OsmRules.chargeLines(tags)),
                        row(t("Gestore"), tags["operator"]),
                        row(t("Note"), tags["note"]),
                    ),
                    null, false, null,
                )
            }
            Kind.METER -> Description(
                el, theme, title, sub(if (tags.containsKey("name") || tags.containsKey("ref")) t("Parcometro") else null), emptyList(),
                listOfNotNull(
                    row(t("Si paga con"), paymentText(tags)),
                    row(t("Gestore"), tags["operator"]),
                    row(t("Note"), tags["note"]),
                ),
                null, false, null,
            )
            else -> {
                val cond = tags["parking:condition"].orEmpty().lowercase()
                val hasMaxstay = OsmRules.hasMaxstay(tags)
                val stall = if (kind == Kind.SPACE) OsmRules.specialStallName(tags["parking_space"]) else ""
                val type = PARKING_TYPES[tags["parking"]]?.let { t(it) } ?: ""
                val subtitle = listOf(
                    if (stall.isNotEmpty() && tags.containsKey("name")) stall else "",
                    type.ifEmpty { if (tags.containsKey("name")) (if (kind == Kind.SPACE) t("Stallo speciale") else t("Area di sosta")) else "" },
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                val chips = listOfNotNull(
                    feeChip(tags) ?: if (!OsmRules.hasDiscRule(tags) && !OsmRules.isRestricted(tags)) Chip(t("Costo non indicato"), "", "circle-help") else null,
                    if (OsmRules.hasDiscRule(tags)) Chip(t("Disco orario"), "time", "timer") else null,
                    if (hasMaxstay) Chip(t("Max {v}", "v" to OsmRules.maxstayText(tags["maxstay"])), "time", "hourglass") else null,
                    tags["capacity"]?.let { Chip(t("{n} posti", "n" to it), "", "car") },
                )
                val limits = listOfNotNull(
                    tags["parking:condition:time_interval"]?.let { t("Validità: ") + OsmRules.translateTime(it) },
                ) + (tags["maxstay:conditional"]?.let { OsmRules.maxstayConditional(it) } ?: emptyList()) + listOfNotNull(
                    if (cond.contains("ticket")) t("Sosta a ticket") else null,
                    if (cond.contains("resident")) t("Riservato residenti") else null,
                )
                val restricted = OsmRules.isRestricted(tags)
                val accessCond = tags["access:conditional"]?.let { OsmRules.yesNoConditional(it, t("Aperto"), t("Vietato")) } ?: emptyList()
                Description(
                    el, theme, title, sub(subtitle), chips,
                    listOfNotNull(
                        row(t("Fasce orarie"), *(tags["fee:conditional"]?.let { OsmRules.feeConditional(it) } ?: emptyList()).toTypedArray()),
                        rowList(t("Tariffa"), OsmRules.chargeLines(tags)),
                        rowList(t("Limitazioni"), limits),
                        row(t("Si paga con"), paymentText(tags)),
                        row(t("Gestore"), tags["operator"]),
                        row(t("Note"), tags["note"]),
                    ),
                    if (restricted || accessCond.isNotEmpty()) AccessInfo(if (restricted) accessLabel(tags) else t("Accesso"), restricted, accessCond) else null,
                    OsmRules.hasCostInfo(tags), null,
                )
            }
        }
    }

    /** Esito della stima per [minutes] di sosta: titolo grande, tono e righe piccole. */
    class CostResult(val big: String, val label: String, val ok: Boolean, val notes: List<String>)

    fun costResult(tags: Map<String, String>, minutes: Int): CostResult {
        val from = Calendar.getInstance()
        val until = Fmt.clock(from.timeInMillis + minutes * 60000L)
        if (tags["fee"] == "no" && !tags.containsKey("fee:conditional")) return CostResult(t("Gratis"), t("Nessun costo"), true, emptyList())
        val est = OsmRules.estimateCost(tags, minutes, from)
            ?: return CostResult("", "", false, listOf(t("A pagamento, ma la tariffa non è indicata su OpenStreetMap.")))
        if (est.total < 0.005) return CostResult(t("Gratis"), t("fino alle {time}", "time" to until), true, emptyList())
        val notes = listOfNotNull(
            if (est.freeMinutes > 0) t("di cui {d} gratis", "d" to Fmt.duration(est.freeMinutes * 60000L)) else null,
            t("Stima dalle tariffe su OpenStreetMap: controlla sul posto."),
        )
        return CostResult("~ " + Fmt.money(est.total, est.currency), t("fino alle {time}", "time" to until), false, notes)
    }
}
