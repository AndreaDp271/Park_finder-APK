package io.github.andreadp271.parkmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ZtlTest {
    /** Data e ora locali (mese 1-12). */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Calendar =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }

    private fun zone(vararg tags: Pair<String, String>) = Ztl("way:1", tags.toMap(), listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0)), true)

    // Bassano (Via Barbieri)
    private val bassano = zone("motor_vehicle:conditional" to "permit @ (Apr-Oct: Mo-Fr 00:00-05:30,20:30-24:00; Apr-Oct: Th 09:00-14:00; Apr-Oct: Sa 00:00-05:30,09:00-24:00; Apr-Oct: PH 00:00-24:00)")

    @Test fun bassanoSummerNightIsActive() {
        assertEquals(true, bassano.activeAt(at(2026, 7, 1, 21, 0))) // mercoledì di luglio, 21:00
    }

    @Test fun bassanoSummerAfternoonIsNotActive() {
        assertEquals(false, bassano.activeAt(at(2026, 7, 1, 16, 0)))
    }

    @Test fun bassanoWinterIsNotActive() {
        assertEquals(false, bassano.activeAt(at(2026, 11, 4, 21, 0))) // novembre: fuori da apr-ott
    }

    @Test fun bassanoThursdayMorning() {
        assertEquals(true, bassano.activeAt(at(2026, 7, 2, 10, 0))) // giovedì 10:00
        assertEquals(false, bassano.activeAt(at(2026, 7, 1, 10, 0))) // mercoledì 10:00
    }

    @Test fun baseValueWithoutConditionIsAlwaysActive() {
        assertEquals(true, zone("motor_vehicle" to "permit").activeAt(at(2026, 7, 1, 12)))
    }

    @Test fun conditionalYesOverridesBase() {
        val z = zone("vehicle" to "permit", "vehicle:conditional" to "yes @ (20:00-08:00)")
        assertEquals(true, z.activeAt(at(2026, 7, 1, 12)))
        assertEquals(false, z.activeAt(at(2026, 7, 1, 22)))
        assertEquals(false, z.activeAt(at(2026, 7, 2, 7))) // dopo mezzanotte
    }

    @Test fun moreSpecificKeyWins() {
        assertEquals(false, zone("vehicle" to "permit", "motorcar" to "yes").activeAt(at(2026, 7, 1, 12)))
        assertEquals(true, zone("access" to "yes", "motor_vehicle:conditional" to "no @ (22:00-06:00)").activeAt(at(2026, 7, 1, 23)))
    }

    @Test fun noTagsIsUnknown() {
        assertNull(zone("name" to "ZTL Centro").activeAt(at(2026, 7, 1, 12)))
        assertNull(zone("goods:conditional" to "destination @ (00:00-09:00)").activeAt(at(2026, 7, 1, 12)))
    }

    @Test fun temporaryClosuresAreNotStreets() {
        assertFalse(Ztl.restricts(mapOf("access:conditional" to "no @ (Mar 20 2026-May 31 2026)")))
        assertTrue(Ztl.restricts(mapOf("motor_vehicle:conditional" to "no @ (20:00 - 07:00)")))
        assertTrue(Ztl.restricts(mapOf("access" to "permit")))
        assertFalse(Ztl.restricts(mapOf("access" to "destination")))
    }
}
