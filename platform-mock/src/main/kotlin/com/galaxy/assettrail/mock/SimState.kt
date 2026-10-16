package com.galaxy.assettrail.mock

import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.LatePayment
import com.galaxy.assettrail.core.model.OverrideInfo
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import kotlinx.serialization.Serializable

/**
 * The simulated platform's whole world: off-chain records plus a toy ledger. Persisted so a demo
 * survives app restarts. Mutable on purpose; only [MockPlatform] touches it, under a lock.
 */
@Serializable
class SimState(
    val seeds: SimSeeds,
    var ledgerSeq: Long = 61_234_567,
    var tripCounter: Int = 2041,
    val drivers: MutableMap<String, SimDriver> = mutableMapOf(),
    val phoneCodes: MutableMap<String, String> = mutableMapOf(),
    val accounts: MutableMap<String, SimAccount> = mutableMapOf(),
    val assetAccounts: MutableMap<String, String> = mutableMapOf(),
    val trips: MutableMap<String, SimTrip> = mutableMapOf(),
    val claimables: MutableMap<String, SimClaimable> = mutableMapOf(),
    val quotes: MutableMap<String, FirmQuote> = mutableMapOf(),
    val cashOuts: MutableMap<String, SimCashOut> = mutableMapOf(),
    val activity: MutableMap<String, MutableList<ActivityItem>> = mutableMapOf(),
    /** The payload store: salted payloads by their hash. Released only through proof links (BR-08). */
    val payloads: MutableMap<String, String> = mutableMapOf(),
    val ledger: MutableList<SimLedgerEntry> = mutableListOf(),
    val alerts: MutableList<String> = mutableListOf(),
    var demo: DemoFlags = DemoFlags(),
)

/** Demo-only secrets for the platform's own accounts. A real platform keeps these in an HSM/KMS. */
@Serializable
data class SimSeeds(
    val platformSigner: String,
    val feeAccount: String,
    val treasury: String,
    val anchor: String,
)

@Serializable
class SimDriver(
    val id: String,
    val phone: String,
    var fullName: String? = null,
    var kycApproved: Boolean = false,
    var accountId: String? = null,
    var momo: String? = null,
    var recovery: Boolean = false,
)

@Serializable
class SimAccount(
    val id: String,
    var sequence: Long,
    var usdc: Usdc,
    val data: MutableMap<String, String> = mutableMapOf(),
)

@Serializable
enum class SimTripState { OFFERED, ACTIVE, DECLINED, CLOSED }

@Serializable
class SimTrip(
    val plan: TripPlan,
    val driverId: String,
    var state: SimTripState,
    /** USDC in the trip account (E): intermediate legs plus the advance limit. */
    var escrow: Usdc,
    /** The final leg in the delivery escrow contract (C). */
    var contract: ContractInfo,
    var nextWaypoint: Int = 0,
    var arrivedAt: Long? = null,
    val advances: MutableList<SimAdvance> = mutableListOf(),
    val overrides: MutableList<SimOverride> = mutableListOf(),
    /** Legs recorded without pay because escrow couldn't pay at the time. */
    val pendingLegs: MutableList<Int> = mutableListOf(),
    val latePayments: MutableList<LatePayment> = mutableListOf(),
)

@Serializable
class SimAdvance(var info: AdvanceInfo, val decideAt: Long)

@Serializable
class SimOverride(var info: OverrideInfo, val decideAt: Long)

@Serializable
class SimClaimable(
    val id: String,
    val tripId: String,
    val advanceId: String,
    val amount: Usdc,
    val claimant: String,
    val deadline: Long,
)

@Serializable
class SimCashOut(
    var info: CashOutInfo,
    val driverId: String,
    val anchorAccount: String,
    val memoHash: String,
    var completeAt: Long? = null,
    var willFail: Boolean = false,
)

@Serializable
data class SimLedgerEntry(val hash: String, val time: Long, val label: String)

/** Switches that make the other parties misbehave, to walk through the spec's edge cases (§8). */
@Serializable
data class DemoFlags(
    val declineNextAdvance: Boolean = false,
    val failNextCashOut: Boolean = false,
    val feeAccountLow: Boolean = false,
    val underfundNextLeg: Boolean = false,
)

/** How long the simulated people and systems take. */
data class SimTimings(
    val ledgerCloseMillis: Long = 4_000,
    val advanceDecisionMillis: Long = 5_000,
    val overrideDecisionMillis: Long = 6_000,
    val cashOutSettleMillis: Long = 8_000,
    val quoteValidityMillis: Long = 60_000,
    val advanceClaimWindowMillis: Long = 48 * 3_600_000L,
)
