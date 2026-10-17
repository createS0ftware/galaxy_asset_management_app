package com.galaxy.assettrail.core.model

import com.galaxy.assettrail.core.geo.GeoPoint
import kotlinx.serialization.Serializable

@Serializable
enum class WaypointType { PICKUP, CHECKPOINT, DELIVERY }

@Serializable
data class Waypoint(
    val index: Int,
    val name: String,
    val type: WaypointType,
    val position: GeoPoint,
    val geofenceRadiusM: Int,
    val windowStart: Long,
    val windowEnd: Long,
    /** Zero at pickup. The delivery leg stays in the contract and is paid when the receiver signs. */
    val legPay: Usdc,
)

/** On-chain, an asset is `(contract id, asset id)`: its history is the contract's hash chain for that id. */
@Serializable
data class Asset(
    val id: String,
    val name: String,
    val shortName: String,
    val tagId: String,
)

/** A trip as dispatched: the contract holds every leg plus the advance limit in escrow (BR-02). */
@Serializable
data class TripPlan(
    val id: String,
    val asset: Asset,
    val waypoints: List<Waypoint>,
    val advanceLimit: Usdc,
    val receiverName: String,
    /** Set at dispatch: after it, with no approval or dispute, the driver can claim the final leg. */
    val deliveryDeadline: Long,
    val fundingTx: String,
    val routePlanHash: String,
    val offeredAt: Long,
    val offerExpiresAt: Long,
) {
    val legTotal: Usdc get() = waypoints.map { it.legPay }.sum()

    val pickup: Waypoint get() = waypoints.first()

    val delivery: Waypoint get() = waypoints.last()

    /** BR-02: what has to be funded before dispatch. */
    val escrowRequired: Usdc get() = legTotal + advanceLimit

    val routeLabel: String get() = "${pickup.name} → ${delivery.name}"
}
