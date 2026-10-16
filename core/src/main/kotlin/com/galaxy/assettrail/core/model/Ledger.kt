package com.galaxy.assettrail.core.model

import kotlinx.serialization.Serializable

/** States of the delivery escrow contract (spec §3.1, §4.6). */
@Serializable
enum class ContractStatus { LOCKED, DELIVERED, DISPUTED, RESOLVED, RELEASED_AFTER_DEADLINE, CANCELLED }

@Serializable
data class ContractInfo(
    val status: ContractStatus,
    /** The final leg locked at dispatch. */
    val locked: Usdc,
    /** If nobody approves or disputes by then, the driver can claim the final leg. */
    val deadline: Long,
    val payout: Usdc? = null,
    /** Advances taken on this trip come off the final leg (BR-06). */
    val deduction: Usdc = Usdc.ZERO,
    val txHash: String? = null,
    val updatedAt: Long,
) {
    val isPaidToDriver: Boolean
        get() = payout != null &&
            status in setOf(ContractStatus.DELIVERED, ContractStatus.RESOLVED, ContractStatus.RELEASED_AFTER_DEADLINE)
}

@Serializable
enum class AdvanceStatus { REQUESTED, READY, CLAIMED, DECLINED, EXPIRED }

@Serializable
data class AdvanceInfo(
    val id: String,
    val amount: Usdc,
    val status: AdvanceStatus,
    val requestedAt: Long,
    val decidedAt: Long? = null,
    /** Claimable-balance deadline (T6): after it the trip account reclaims the money. */
    val claimDeadline: Long? = null,
    val balanceId: String? = null,
    val declineReason: String? = null,
    val claimTx: String? = null,
    val claimedAt: Long? = null,
) {
    /** Requested, waiting to be claimed, or claimed: counts against the advance limit. */
    val countsAgainstLimit: Boolean
        get() = status == AdvanceStatus.REQUESTED || status == AdvanceStatus.READY || status == AdvanceStatus.CLAIMED
}

@Serializable
enum class OverrideStatus { PENDING, APPROVED, DECLINED, USED }

@Serializable
data class OverrideInfo(
    val id: String,
    val waypointIndex: Int,
    val status: OverrideStatus,
    val requestedAt: Long,
    val distanceM: Int,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
)

@Serializable
enum class ActivityKind { LEG_PAY, ADVANCE, FINAL_PAY, CASH_OUT, REFUND }

@Serializable
enum class ActivityStatus { PENDING, COMPLETED, FAILED }

@Serializable
data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val amount: Usdc,
    val incoming: Boolean,
    val time: Long,
    val tripId: String? = null,
    val txHash: String? = null,
    val status: ActivityStatus = ActivityStatus.COMPLETED,
    val note: String? = null,
)

/** SEP-38 firm quote: the price holds until [expiresAt]. */
@Serializable
data class FirmQuote(
    val id: String,
    val sell: Usdc,
    val fee: Usdc,
    /** Local currency per 1 USDC, as text so it shows exactly as the anchor quoted it. */
    val rate: String,
    val buyAmount: String,
    val buyCurrency: String,
    val expiresAt: Long,
    val anchorName: String,
    val eta: String,
)

@Serializable
enum class CashOutStatus { AWAITING_PAYMENT, PENDING, COMPLETED, REFUNDED }

@Serializable
data class CashOutInfo(
    val id: String,
    val sell: Usdc,
    val fee: Usdc,
    val buyAmount: String,
    val buyCurrency: String,
    val momoNumber: String,
    val status: CashOutStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val anchorName: String,
    val eta: String,
    val txHash: String? = null,
    val message: String? = null,
)

/** A leg recorded without its payment (escrow couldn't pay at the time) that ops paid later. */
@Serializable
data class LatePayment(val waypointIndex: Int, val amount: Usdc, val txHash: String, val time: Long)
