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
    /** Zero at pickup. The delivery leg is locked in the escrow contract and paid when the receiver signs. */
    val legPay: Usdc,
)

@Serializable
data class Asset(
    val id: String,
    val name: String,
    val shortName: String,
    val tagId: String,
    /** The asset's Stellar account (A). Every custody event writes its `wp` data entry. */
    val account: String,
)

@Serializable
data class TripPlan(
    val id: String,
    val asset: Asset,
    val waypoints: List<Waypoint>,
    val advanceLimit: Usdc,
    /** Trip account (E): holds per-leg pay and the advance limit. */
    val tripAccount: String,
    /** Delivery escrow contract (C): holds the final leg until the receiver approves. */
    val escrowContract: String,
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
