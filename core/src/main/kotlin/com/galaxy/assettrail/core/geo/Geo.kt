package com.galaxy.assettrail.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** One GPS fix as the phone reported it. */
@Serializable
data class GpsFix(
    val point: GeoPoint,
    val accuracyM: Int,
    val timeMillis: Long,
    val isMock: Boolean = false,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceRoundedM(a: GeoPoint, b: GeoPoint): Int = distanceM(a, b).roundToInt()

    fun isInside(point: GeoPoint, centre: GeoPoint, radiusM: Int): Boolean =
        distanceM(point, centre) <= radiusM

    /** The point [distanceM] metres from [from] along [bearingDeg] (0 = north). */
    fun offset(from: GeoPoint, distanceM: Double, bearingDeg: Double): GeoPoint {
        val angular = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(Math.toDegrees(lat2), normaliseLon(Math.toDegrees(lon2)))
    }

    /** Initial bearing in degrees from [from] towards [to]. */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun normaliseLon(lon: Double): Double = ((lon + 540) % 360) - 180
}
