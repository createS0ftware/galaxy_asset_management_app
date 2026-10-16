package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.OverrideStatus
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.Waypoint
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.model.sum
import com.galaxy.assettrail.core.platform.TxKind

enum class LegState {
    /** Recorded on the ledger: drawn solid. */
    VERIFIED,

    /** Signed and saved on the phone, not on the ledger yet: never drawn as verified. */
    SAVED,

    NEXT,
    UPCOMING,
}

data class LegRow(val waypoint: Waypoint, val state: LegState, val checkIn: CheckIn?)

/** The check-in that stands for a waypoint: the latest one that didn't fail. */
fun ActiveTrip.checkInAt(index: Int): CheckIn? =
    checkIns.lastOrNull { it.waypointIndex == index && it.status != CheckInStatus.FAILED }

fun ActiveTrip.latestFailureAt(index: Int): CheckIn? =
    checkIns.lastOrNull { it.waypointIndex == index }?.takeIf { it.status == CheckInStatus.FAILED }

/** BR-03: check-ins go in route order; the next one is the first waypoint without a check-in. */
fun ActiveTrip.nextWaypoint(): Waypoint? = plan.waypoints.firstOrNull { checkInAt(it.index) == null }

fun ActiveTrip.legRows(): List<LegRow> {
    val next = nextWaypoint()?.index
    return plan.waypoints.map { waypoint ->
        val checkIn = checkInAt(waypoint.index)
        val state = when {
            checkIn?.status == CheckInStatus.RECORDED -> LegState.VERIFIED
            checkIn?.status == CheckInStatus.QUEUED -> LegState.SAVED
            waypoint.index == next -> LegState.NEXT
            else -> LegState.UPCOMING
        }
        LegRow(waypoint, state, checkIn)
    }
}

/** Pay that has reached the wallet on this trip, final leg included once the contract paid it. */
fun ActiveTrip.earned(): Usdc {
    val legs = checkIns.filter { it.status == CheckInStatus.RECORDED && it.pay == LegPayStatus.PAID }.map { it.legPay }.sum()
    val finalLeg = if (contract.isPaidToDriver) contract.locked else Usdc.ZERO
    return legs + finalLeg
}

fun ActiveTrip.hasArrivedAtDelivery(): Boolean = checkInAt(plan.delivery.index)?.status == CheckInStatus.RECORDED

fun ActiveTrip.isAwaitingSignature(): Boolean = hasArrivedAtDelivery() && contract.status == ContractStatus.LOCKED

fun ActiveTrip.canClaimFinalPay(now: Long): Boolean = isAwaitingSignature() && now > contract.deadline

fun ActiveTrip.approvedOverrideFor(index: Int) =
    overrides.lastOrNull { it.waypointIndex == index && it.status == OverrideStatus.APPROVED }

fun ActiveTrip.pendingOverrideFor(index: Int) =
    overrides.lastOrNull { it.waypointIndex == index && it.status == OverrideStatus.PENDING }

fun ActiveTrip.distanceTo(waypoint: Waypoint, position: GeoPoint): Int = Geo.distanceRoundedM(position, waypoint.position)

/** Check-ins saved on the phone and not yet on the ledger. */
fun DriverState.pendingCheckIns(): List<QueuedTx> = queue.filter { it.kind == TxKind.CHECK_IN }

/**
 * §4.4 sequence rule: cash-out and claims share the driver's sequence number with check-ins, so
 * they wait until saved check-ins are recorded.
 */
fun DriverState.canMoveMoney(): Boolean = pendingCheckIns().isEmpty()

object AdvanceRules {
    /**
     * BR-06: capped at the trip's advance limit; and because advances come off the final leg,
     * never more than that leg pays.
     */
    fun cap(plan: TripPlan): Usdc = minOf(plan.advanceLimit, plan.delivery.legPay)

    fun available(plan: TripPlan, advances: List<AdvanceInfo>): Usdc =
        (cap(plan) - advances.filter { it.countsAgainstLimit }.map { it.amount }.sum()).coerceAtLeastZero()

    /** Approved advances, claimed or still claimable, come off the final leg. */
    fun deduction(advances: List<AdvanceInfo>): Usdc =
        advances.filter { it.status == AdvanceStatus.READY || it.status == AdvanceStatus.CLAIMED }
            .map { it.amount }.sum()
}

val Waypoint.isPaidAtCheckIn: Boolean get() = type == WaypointType.CHECKPOINT && legPay.isPositive
