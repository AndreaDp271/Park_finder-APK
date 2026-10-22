package io.github.andreadp271.parkmap.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Prova su dati veri: scarica una cella statica e una risposta Postpass e le fa passare dal parser e da
 * tutte le regole (titolo, riassunto, scheda, stima del costo). Si esegue con LIVE_API=1.
 */
class LiveDataTest {
    private val live = System.getenv("LIVE_API") == "1"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ParkMap-test")
        c.connectTimeout = 15000; c.readTimeout = 60000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun exercise(els: List<OsmElement>) {
        els.forEach { el ->
            OsmRules.title(el.tags); OsmRules.summary(el.tags); OsmRules.theme(el.tags); OsmRules.mapColors(el.tags)
            val d = Describe.of(el, 120.0)
            if (d.costEstimator) listOf(30, 60, 240, 480).forEach { Describe.costResult(el.tags, it) }
            OsmRules.layerOf(el.tags); el.center
        }
    }

    @Test fun staticCells() {
        assumeTrue(live)
        val idx = Json.parseToJsonElement(get(Http.SITE + "data/index.json")).jsonObject
        val data = idx["data"]!!.jsonArray
        // alcune celle con dati sparse nell'elenco: la somma deve contenere parecchi elementi
        var total = 0
        for (n in 0 until 8) {
            val run = data[(data.size - 1) * n / 7].jsonArray
            val key = "${run[0].jsonPrimitive.content}_${run[1].jsonPrimitive.content}"
            val cell = Json.parseToJsonElement(get(Http.SITE + "data/cells/$key.json")).jsonObject["e"]!!.jsonArray
            val els = ParkingRepo.expandStatic(cell)
            total += els.size
            exercise(els)
        }
        println("celle statiche: $total elementi")
        assertTrue(total > 20)
    }

    private fun post(url: String, form: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 40000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("User-Agent", "ParkMap-test")
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(form, "UTF-8")).toByteArray()) }
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        println("server non disponibile: $e")
        null
    }

    @Test fun postpassMilan() {
        assumeTrue(live)
        val text = post("https://postpass.geofabrik.de/api/interpreter", ParkingRepo.parkingSql(45.46, 9.18, 45.47, 9.20))
        assumeTrue(text != null)
        val els = ParkingRepo.parsePostpass(text!!)
        println("postpass: ${els.size} elementi")
        assertTrue(els.isNotEmpty())
        exercise(els)
    }

    @Test fun overpassMilan() {
        assumeTrue(live)
        val text = post("https://overpass-api.de/api/interpreter", ParkingRepo.overpassQuery(45.46, 9.18, 45.47, 9.20))
        assumeTrue(text != null)
        val els = ParkingRepo.parseOverpass(text!!)
        println("overpass: ${els.size} elementi")
        assertTrue(els.isNotEmpty())
        exercise(els)
    }
}
