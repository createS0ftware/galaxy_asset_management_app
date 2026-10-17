package com.galaxy.assettrail.mock

import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.model.Asset
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.Waypoint
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.payload.CanonicalJson
import com.galaxy.assettrail.core.payload.Hashes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Trips from the spec's sample data (§12): TR-2041 Tema Port → Kumasi with a 400 kVA generator,
 * then a return run. Time windows are laid out from "now" so the demo works at any hour.
 */
internal object DemoScenario {

    data class Stop(
        val name: String,
        val type: WaypointType,
        val position: GeoPoint,
        val radiusM: Int,
        val windowStartMin: Int,
        val windowEndMin: Int,
        val pay: String,
    )

    data class Template(
        val asset: Asset,
        val stops: List<Stop>,
        val advanceLimit: Usdc,
        val receiverName: String,
    )

    private val temaPort = GeoPoint(5.6358, 0.0138)
    private val nsawam = GeoPoint(5.8089, -0.3503)
    private val nkawkaw = GeoPoint(6.5506, -0.7660)
    private val konongo = GeoPoint(6.6167, -1.2167)
    private val kumasiWarehouse = GeoPoint(6.6885, -1.6244)

    val outbound = Template(
        asset = Asset("GX400-0041", "Diesel generator 400 kVA", "Generator", "04:A3:7E:91"),
        stops = listOf(
            Stop("Tema Port", WaypointType.PICKUP, temaPort, 300, 0, 60, "0"),
            Stop("Nsawam", WaypointType.CHECKPOINT, nsawam, 200, 120, 180, "18.00"),
            Stop("Nkawkaw", WaypointType.CHECKPOINT, nkawkaw, 200, 195, 255, "22.00"),
            Stop("Konongo", WaypointType.CHECKPOINT, konongo, 300, 270, 330, "15.00"),
            Stop("Kumasi · Warehouse B", WaypointType.DELIVERY, kumasiWarehouse, 150, 345, 420, "25.00"),
        ),
        advanceLimit = Usdc.of("40.00"),
        receiverName = "Warehouse B, Kumasi",
    )

    val returnRun = Template(
        asset = Asset("TX250-0107", "Transformer 250 kVA", "Transformer", "04:5B:22:C8"),
        stops = listOf(
            Stop("Kumasi · Warehouse B", WaypointType.PICKUP, kumasiWarehouse, 150, 0, 60, "0"),
            Stop("Konongo", WaypointType.CHECKPOINT, konongo, 300, 75, 135, "16.00"),
            Stop("Nkawkaw", WaypointType.CHECKPOINT, nkawkaw, 200, 150, 210, "20.00"),
            Stop("Nsawam", WaypointType.CHECKPOINT, nsawam, 200, 255, 315, "22.00"),
            Stop("Tema Port · Gate 3", WaypointType.DELIVERY, temaPort, 300, 345, 420, "26.00"),
        ),
        advanceLimit = Usdc.of("30.00"),
        receiverName = "Tema Port, Gate 3",
    )

    fun templateFor(tripNumber: Int): Template = if (tripNumber % 2 == 1) outbound else returnRun

    fun plan(template: Template, tripId: String, now: Long, fundingTx: String): TripPlan {
        val quarterHour = 15 * 60_000L
        val start = Math.floorDiv(now, quarterHour) * quarterHour
        val waypoints = template.stops.mapIndexed { index, stop ->
            Waypoint(
                index = index,
                name = stop.name,
                type = stop.type,
                position = stop.position,
                geofenceRadiusM = stop.radiusM,
                windowStart = start + stop.windowStartMin * 60_000L,
                windowEnd = start + stop.windowEndMin * 60_000L,
                legPay = Usdc.of(stop.pay),
            )
        }
        return TripPlan(
            id = tripId,
            asset = template.asset,
            waypoints = waypoints,
            advanceLimit = template.advanceLimit,
            receiverName = template.receiverName,
            deliveryDeadline = waypoints.last().windowEnd + 24 * 3_600_000L,
            fundingTx = fundingTx,
            routePlanHash = routePlanHash(tripId, template, waypoints),
            offeredAt = now,
            offerExpiresAt = now + 12 * 3_600_000L,
        )
    }

    /** The plan hash `create_trip` anchors: the route plan, canonicalised the same way as waypoint payloads. */
    private fun routePlanHash(tripId: String, template: Template, waypoints: List<Waypoint>): String {
        val json = buildJsonObject {
            put("v", 1)
            put("trip", tripId)
            put("asset", template.asset.id)
            put(
                "waypoints",
                JsonArray(
                    waypoints.map { wp ->
                        buildJsonObject {
                            put("name", wp.name)
                            put("type", wp.type.name.lowercase())
                            put("lat", wp.position.lat)
                            put("lon", wp.position.lon)
                            put("radius_m", wp.geofenceRadiusM)
                            put("window", "${wp.windowStart}/${wp.windowEnd}")
                            put("pay", wp.legPay.toStellarAmount())
                        }
                    },
                ),
            )
            put("advance_limit", template.advanceLimit.toStellarAmount())
        }
        return Hashes.hex(Hashes.sha256(CanonicalJson.encode(json)))
    }
}
