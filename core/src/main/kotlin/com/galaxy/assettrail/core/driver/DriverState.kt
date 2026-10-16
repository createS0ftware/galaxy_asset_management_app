package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.OverrideInfo
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.platform.TxKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything the driver app keeps on the phone. Persisted after every change. */
@Serializable
data class DriverState(
    val onboarding: Onboarding = Onboarding(),
    val profile: DriverProfile? = null,
    /** Last sequence number used for the driver account, on the ledger or in the queue. */
    val sequence: Long = 0,
    val offers: List<TripPlan> = emptyList(),
    val trip: ActiveTrip? = null,
    val balance: Usdc = Usdc.ZERO,
    val balanceAsOf: Long? = null,
    val activity: List<ActivityItem> = emptyList(),
    val cashOuts: List<CashOutInfo> = emptyList(),
    /** BR-09: every driver-sourced transaction, in sequence order. */
    val queue: List<QueuedTx> = emptyList(),
    /** Why the queue is stalled, when the platform asked to retry later. */
    val queueNotice: String? = null,
    val lastSyncAt: Long? = null,
)

@Serializable
enum class OnboardingStep { PHONE, IDENTITY, PAYOUT }

@Serializable
data class Onboarding(
    val step: OnboardingStep = OnboardingStep.PHONE,
    val phone: String? = null,
    val demoCode: String? = null,
    val driverId: String? = null,
    val fullName: String? = null,
    val kycProvider: String? = null,
)

@Serializable
data class DriverProfile(
    val driverId: String,
    val fullName: String,
    val phone: String,
    val momoNumber: String,
    val accountId: String,
    val recoveryEnabled: Boolean,
    val kycProvider: String,
    val walletTx: String,
) {
    val firstName: String get() = fullName.trim().substringBefore(' ')
}

@Serializable
data class ActiveTrip(
    val plan: TripPlan,
    val acceptedAt: Long,
    val contract: ContractInfo,
    val checkIns: List<CheckIn> = emptyList(),
    val advances: List<AdvanceInfo> = emptyList(),
    val overrides: List<OverrideInfo> = emptyList(),
    val closed: Boolean = false,
)

@Serializable
enum class CheckInStatus {
    /** Signed on the phone, waiting in the queue (offline, or not yet submitted). */
    QUEUED,

    /** On the ledger. */
    RECORDED,

    /** The signer service refused it; it is not on the ledger. */
    FAILED,
}

@Serializable
enum class LegPayStatus {
    /** Pickup: no pay. */
    NONE,

    /** Paid in the same transaction as the location record. */
    PAID,

    /** Arrives when the queued check-in records. */
    AWAITING_RECORD,

    /** Recorded without its payment because escrow couldn't pay; ops pays it later (§8). */
    PENDING_ESCROW,

    /** Delivery: the final leg sits in the escrow contract until the receiver approves. */
    IN_CONTRACT,
}

@Serializable
data class CheckIn(
    val waypointIndex: Int,
    val event: WaypointEvent,
    val payloadJson: String,
    val payloadHash: String,
    val deviceTime: Long,
    val tag: String,
    val distanceFromCentreM: Int,
    val savedOffline: Boolean,
    val status: CheckInStatus,
    val pay: LegPayStatus,
    val legPay: Usdc,
    val overrideId: String? = null,
    val txHash: String? = null,
    val ledgerTime: Long? = null,
    val failure: RejectCode? = null,
    val failureMessage: String? = null,
)

@Serializable
data class QueuedTx(
    val id: String,
    val kind: TxKind,
    val sequence: Long,
    val envelopeXdr: String,
    val spec: TxSpec,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** What a queued transaction does, so it can be rebuilt and re-signed with a new sequence number. */
@Serializable
sealed interface TxSpec {
    val maxTimeEpochSeconds: Long

    @Serializable
    @SerialName("check_in")
    data class CheckInTx(
        val tripId: String,
        val waypointIndex: Int,
        val payloadJson: String,
        val payloadHash: String,
        val assetAccount: String,
        val tripAccount: String,
        val legPay: Usdc?,
        override val maxTimeEpochSeconds: Long,
    ) : TxSpec

    @Serializable
    @SerialName("claim_advance")
    data class ClaimAdvanceTx(
        val tripId: String,
        val advanceId: String,
        val balanceId: String,
        override val maxTimeEpochSeconds: Long,
    ) : TxSpec

    @Serializable
    @SerialName("cash_out")
    data class CashOutTx(
        val cashOutId: String,
        val anchorAccount: String,
        val amount: Usdc,
        val memoHash: String,
        override val maxTimeEpochSeconds: Long,
    ) : TxSpec
}
