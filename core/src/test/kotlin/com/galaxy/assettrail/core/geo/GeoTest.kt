package com.galaxy.assettrail.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoTest {
    private val konongo = GeoPoint(6.6167, -1.2167)
    private val kumasi = GeoPoint(6.6885, -1.6244)

    @Test
    fun `distance between Konongo and Kumasi is about 46 km in a straight line`() {
        val km = Geo.distanceM(konongo, kumasi) / 1000
        assertTrue(km in 45.0..47.0, "was $km km")
    }

    @Test
    fun `offset then distance round-trips`() {
        listOf(0.0, 45.0, 135.0, 270.0).forEach { bearing ->
            val moved = Geo.offset(konongo, 82.0, bearing)
            assertTrue(abs(Geo.distanceM(konongo, moved) - 82.0) < 0.5, "bearing $bearing")
        }
    }

    @Test
    fun `geofence includes its edge and excludes beyond it`() {
        assertTrue(Geo.isInside(Geo.offset(konongo, 299.0, 10.0), konongo, 300))
        assertFalse(Geo.isInside(Geo.offset(konongo, 302.0, 10.0), konongo, 300))
    }

    @Test
    fun `bearing points the right way`() {
        val west = Geo.bearingDeg(konongo, kumasi)
        assertTrue(west in 270.0..290.0, "was $west")
    }
}
