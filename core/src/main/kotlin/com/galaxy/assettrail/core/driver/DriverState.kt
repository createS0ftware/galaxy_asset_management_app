package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.ledger.LedgerClock
import com.galaxy.assettrail.core.ledger.NetworkInfo
import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.platform.RejectCode
import kotlinx.serialization.Serializable

/** Everything the driver app keeps on the phone. Persisted after every change. */
@Serializable
data class DriverState(
    val onboarding: Onboarding = Onboarding(),
    val profile: DriverProfile? = null,
    /** The network, contract and pay asset to sign for. Kept so check-ins can be signed offline. */
    val network: NetworkInfo? = null,
    /** The latest ledger seen, to date offline authorizations. */
    val ledger: LedgerClock? = null,
    val offers: List<TripPlan> = emptyList(),
    val trip: ActiveTrip? = null,
    val balance: Usdc = Usdc.ZERO,
    val balanceAsOf: Long? = null,
    val activity: List<ActivityItem> = emptyList(),
    val cashOuts: List<CashOutInfo> = emptyList(),
    /** BR-09: check-ins signed on the phone, in route order, until they record. */
    val queue: List<QueuedCheckIn> = emptyList(),
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

    /** Paid by the contract in the same call that recorded the location. */
    PAID,

    /** Arrives when the queued check-in records. */
    AWAITING_RECORD,

    /** Delivery: the final leg stays in the contract until the receiver signs. */
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
    val txHash: String? = null,
    val ledgerTime: Long? = null,
    val failure: RejectCode? = null,
    val failureMessage: String? = null,
)

/** A check-in signed on the phone and not on the ledger yet. */
@Serializable
data class QueuedCheckIn(
    val id: String,
    val tripId: String,
    val waypointIndex: Int,
    /** The salted payload, canonical form: what the platform stores and hashes. */
    val payloadJson: String,
    val payloadHash: String,
    /** Base64 Soroban authorization entry for `check_in(trip, wp, hash)`, signed by the driver. */
    val authEntry: String,
    /** The last ledger [authEntry] is valid in. */
    val validUntilLedger: Long,
    val createdAt: Long,
)
