package io.github.andreadp271.parkmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun postpassGeoJson() {
        val text = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"osm_type":"W","osm_id":123,"tags":{"amenity":"parking","fee":"yes","capacity":"40"}},
           "geometry":{"type":"Polygon","coordinates":[[[9.0,45.0],[9.001,45.0],[9.001,45.001],[9.0,45.0]]]}},
          {"type":"Feature","properties":{"osm_type":"N","osm_id":7,"tags":{"amenity":"charging_station","socket:type2":"2","socket:type2:output":"22 kW"}},
           "geometry":{"type":"Point","coordinates":[9.1,45.1]}},
          {"type":"Feature","properties":{"osm_type":"R","osm_id":9,"tags":{"amenity":"parking"}},
           "geometry":{"type":"MultiPolygon","coordinates":[[[[9.0,45.0],[9.1,45.0],[9.1,45.1],[9.0,45.0]]],[[[9.0,45.0],[9.01,45.0],[9.01,45.01],[9.0,45.0]]]]}},
          {"type":"Feature","properties":{"osm_type":"W","osm_id":1,"tags":{}},"geometry":null}
        ]}"""
        val els = ParkingRepo.parsePostpass(text)
        assertEquals(3, els.size)
        val way = els[0]
        assertEquals("way:123", way.key)
        assertTrue(way.isClosed)
        assertEquals(45.0, way.g!![0], 1e-9) // lat prima di lon
        assertEquals(9.0, way.g!![1], 1e-9)
        val ev = els[1]
        assertEquals("node:7", ev.key)
        assertEquals(45.1, ev.lat!!, 1e-9)
        assertEquals(22.0, OsmRules.chargingMaxKw(ev.tags)!!, 1e-9)
        assertEquals(Kind.CHARGING, ev.kind)
        assertEquals(8, els[2].g!!.size) // l'anello esterno più grande (4 punti)
    }

    @Test fun overpassJson() {
        val text = """{"elements":[
          {"type":"way","id":5,"tags":{"amenity":"parking","fee":"no"},"geometry":[{"lat":45.0,"lon":9.0},{"lat":45.0,"lon":9.001},{"lat":45.001,"lon":9.001},{"lat":45.0,"lon":9.0}]},
          {"type":"node","id":6,"lat":45.2,"lon":9.2,"tags":{"amenity":"vending_machine","vending":"parking_tickets"}},
          {"type":"relation","id":7,"tags":{"amenity":"parking"},"bounds":{"minlat":45.0,"minlon":9.0,"maxlat":45.1,"maxlon":9.1}}
        ]}"""
        val els = ParkingRepo.parseOverpass(text)
        // la relazione senza geometria non si può disegnare
        assertEquals(2, els.size)
        assertEquals(Kind.PARKING, els[0].kind)
        assertEquals(Kind.METER, els[1].kind)
        assertNotNull(els[1].anchor)
        assertNull(els[1].g)
    }

    @Test fun staticRecords() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """[["n",11,{"amenity":"bicycle_parking","capacity":"10"},45.5,9.5],["w",12,{"amenity":"parking","fee":"no"},[45.0,9.0,45.0,9.001,45.001,9.001,45.0,9.0]]]""",
        ) as kotlinx.serialization.json.JsonArray
        val els = ParkingRepo.expandStatic(json)
        assertEquals("node:11", els[0].key)
        assertEquals(Kind.BIKE, els[0].kind)
        assertEquals("way:12", els[1].key)
        assertTrue(els[1].isClosed)
    }

    @Test fun describeDoesNotCrashOnStrangeTags() {
        val tags = mapOf(
            "amenity" to "parking", "fee" to "yes", "fee:conditional" to "no @ (Mo-Fr 20:00-08:00; Sa,Su); yes @ (PH 00:00-24:00)",
            "charge" to "2 EUR/hour", "charge:conditional" to "1 EUR/hour @ (Mo-Fr 08:00-12:00); 3 EUR/day", "maxstay" to "2 hours",
            "maxstay:conditional" to "30 minutes @ (Mo-Fr 08:00-20:00)", "access" to "customers", "access:conditional" to "yes @ (Sa 08:00-12:00)",
            "payment:cash" to "yes", "payment:app" to "only", "capacity" to "abc", "operator" to "Comune",
        )
        val el = OsmElement("node", 1, tags, 45.0, 9.0)
        val d = Describe.of(el, 350.0)
        assertTrue(d.rows.isNotEmpty())
        assertNotNull(d.access)
        assertTrue(d.costEstimator)
        listOf(30, 60, 120, 240, 480).forEach { Describe.costResult(tags, it) }
    }
}
