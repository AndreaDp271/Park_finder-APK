package io.github.andreadp271.parkmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OsmRulesTest {
    // mercoledì 21 ottobre 2026 (domenica = 25)
    private fun at(day: Int, h: Int, m: Int = 0): Calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, h, m) }

    @Test fun splitRulesIgnoresSemicolonInParentheses() {
        val rules = OsmRules.splitRules("no @ (Mo-Sa 13:00-15:00,20:00-08:00; Su); yes @ (Mo-Fr 08:00-19:00)")
        assertEquals(2, rules.size)
        assertEquals("no @ (Mo-Sa 13:00-15:00,20:00-08:00; Su)", rules[0])
    }

    @Test fun feeConditionalFreeWindows() {
        val tags = mapOf("amenity" to "parking", "fee" to "yes", "fee:conditional" to "no @ (Mo-Sa 13:00-15:00,20:00-08:00; Su)")
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(21, 14)))
        assertEquals(FeeState.PAID, OsmRules.feeStateAt(tags, at(21, 10)))
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(21, 21)))
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(21, 7))) // fascia notturna del giorno prima
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(25, 10))) // domenica tutto il giorno
    }

    @Test fun feeConditionalPaidWindowWithoutBaseFee() {
        val tags = mapOf("amenity" to "parking", "fee:conditional" to "yes @ (Mo-Fr 08:00-19:00)")
        assertEquals(FeeState.PAID, OsmRules.feeStateAt(tags, at(21, 10)))
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(21, 20)))
    }

    @Test fun discRuleMakesParkingFreeWhenFeeUnknown() {
        val tags = mapOf("amenity" to "parking", "maxstay" to "2 hours")
        assertTrue(OsmRules.hasDiscRule(tags))
        assertEquals(FeeState.FREE, OsmRules.feeStateAt(tags, at(21, 10)))
    }

    @Test fun parsesRates() {
        val a = OsmRules.parseRate("1.50 EUR/hour")!!
        assertEquals(1.5, a.amount, 1e-9); assertEquals("EUR", a.currency); assertEquals(60.0, a.minutes, 1e-9)
        val b = OsmRules.parseRate("0,50 €/30 min")!!
        assertEquals(0.5, b.amount, 1e-9); assertEquals(30.0, b.minutes, 1e-9)
        val c = OsmRules.parseRate("EUR 2/day")!!
        assertEquals(1440.0, c.minutes, 1e-9)
        assertNull(OsmRules.parseRate("gratis"))
    }

    @Test fun estimatesCost() {
        val tags = mapOf("amenity" to "parking", "fee" to "yes", "charge" to "1.50 EUR/hour")
        val est = OsmRules.estimateCost(tags, 120, at(21, 10))!!
        assertEquals(3.0, est.total, 1e-6)
        assertEquals("EUR", est.currency)
    }

    @Test fun estimateSkipsFreeHours() {
        val tags = mapOf("amenity" to "parking", "fee" to "yes", "fee:conditional" to "no @ (Mo-Su 12:00-14:00)", "charge" to "1.00 EUR/hour")
        val est = OsmRules.estimateCost(tags, 240, at(21, 10))!! // 10-14: due ore a pagamento, due gratis
        assertEquals(2.0, est.total, 1e-6)
        assertEquals(120, est.freeMinutes)
    }

    @Test fun filters() {
        val free = mapOf("amenity" to "parking", "fee" to "no")
        val paid = mapOf("amenity" to "parking", "fee" to "yes")
        val ev = mapOf("amenity" to "charging_station")
        val meter = mapOf("amenity" to "vending_machine", "vending" to "parking_tickets")
        val now = at(21, 10)
        assertTrue(OsmRules.matchesFilter(free, Filter.FREE, now))
        assertFalse(OsmRules.matchesFilter(paid, Filter.FREE, now))
        assertTrue(OsmRules.matchesFilter(ev, Filter.EV, now))
        assertFalse(OsmRules.matchesFilter(meter, Filter.FREE, now))
        assertTrue(OsmRules.matchesFilters(meter, emptySet(), now)) // senza filtri si vede tutto
    }

    @Test fun layerVisibility() {
        val bike = mapOf("amenity" to "bicycle_parking")
        val now = at(21, 10)
        val defaults = setOf(Layer.AREAS, Layer.SPACES, Layer.METERS, Layer.CHARGING, Layer.MOTO)
        assertFalse(OsmRules.isShown(bike, defaults, emptySet(), now))
        assertTrue(OsmRules.isShown(bike, defaults, setOf(Filter.BIKE), now)) // un filtro attivo mostra il suo livello
    }

    @Test fun feeConditionalIsShownAsPaidSchedule() {
        val lines = OsmRules.feeConditional("no @ (Mo-Sa 13:00-15:00)")
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("00:00-13:00,15:00-24:00"))
    }

    @Test fun elementCenterAndClosed() {
        val el = OsmElement("way", 1, mapOf("amenity" to "parking"), g = doubleArrayOf(45.0, 9.0, 45.0, 9.002, 45.002, 9.002, 45.0, 9.0))
        assertTrue(el.isClosed)
        assertNotNull(el.center)
        assertEquals("way:1", el.key)
    }

    @Test fun versionComparison() {
        assertTrue(Updater.isNewer("2.0.1", "2.0.0"))
        assertTrue(Updater.isNewer("2.1", "2.0.9"))
        assertFalse(Updater.isNewer("2.0.0", "2.0.0"))
        assertFalse(Updater.isNewer("1.9.9", "2.0.0"))
    }

    @Test fun translationPlaceholders() {
        assertEquals("12 posti", t("{n} posti", "n" to 12))
    }
}
