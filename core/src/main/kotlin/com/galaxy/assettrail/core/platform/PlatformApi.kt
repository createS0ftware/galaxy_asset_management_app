package com.galaxy.assettrail.core.platform

import com.galaxy.assettrail.core.geo.GpsFix
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
import java.io.IOException

/**
 * The platform as the driver app sees it: API + payload store, signer service, indexer, and the
 * anchor partner behind it (spec §3.1). The backend and the escrow-contract API are built by
 * another team; this interface is the app's side of that boundary. Until their API is wired in, the
 * demo build runs the in-process stand-in from the :platform-mock module.
 */
interface PlatformApi {

    suspend fun sendPhoneCode(phone: String): CodeSent

    /** Returns the driver id once the code checks out. */
    suspend fun verifyPhone(phone: String, code: String): String

    /** ID check with the anchor (SEP-12). The platform never keeps the ID data. */
    suspend fun submitKyc(driverId: String, kyc: KycSubmission): KycResult

    /** T2: the platform creates and sponsors the driver's account and USDC trustline. */
    suspend fun createWallet(driverId: String, request: WalletRequest): WalletCreated

    suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot

    suspend fun acceptOffer(driverId: String, tripId: String): TripPlan

    suspend fun declineOffer(driverId: String, tripId: String)

    /**
     * Signer service: re-checks the payload (BR-03), co-signs, wraps in a fee bump paid by the
     * fee account, and submits.
     */
    suspend fun submit(driverId: String, submission: Submission): SubmitResult

    /** "Request override": sends the GPS evidence to ops when check-in is blocked by the geofence. */
    suspend fun requestOverride(driverId: String, request: OverrideRequest): OverrideInfo

    suspend fun reportTagMismatch(driverId: String, tripId: String, waypointIndex: Int, scannedTag: String)

    suspend fun requestAdvance(driverId: String, tripId: String, amount: Usdc): AdvanceInfo

    /** After the delivery deadline, with no approval or dispute, the contract releases the final leg. */
    suspend fun claimFinalPay(driverId: String, tripId: String): ContractInfo

    /** SEP-38 firm quote for selling USDC into mobile money. */
    suspend fun quote(driverId: String, sell: Usdc): FirmQuote

    /** SEP-6 withdrawal: where and with which memo to send the USDC. */
    suspend fun startCashOut(driverId: String, quoteId: String, momoNumber: String): CashOutInstructions
}

/** No signal: the request never reached the platform. Nothing changed. */
class OfflineException : IOException("No connection")

/** The platform refused the request. [message] is written for the driver. */
class PlatformException(val code: String, message: String) : Exception(message)

@Serializable
data class CodeSent(val phone: String, val demoCode: String? = null)

@Serializable
data class KycSubmission(val fullName: String, val idType: String, val idNumber: String)

@Serializable
data class KycResult(val approved: Boolean, val provider: String, val message: String? = null)

@Serializable
data class WalletRequest(
    val accountId: String,
    val fullName: String,
    val phone: String,
    val momoNumber: String,
    val recovery: Boolean,
)

@Serializable
data class WalletCreated(val accountId: String, val sequence: Long, val txHash: String)

@Serializable
enum class TxKind { CHECK_IN, CLAIM_ADVANCE, CASH_OUT }

@Serializable
data class Submission(
    val kind: TxKind,
    val envelopeXdr: String,
    val tripId: String? = null,
    /** Check-ins carry the salted payload; it goes to the payload store, never on-chain. */
    val payloadJson: String? = null,
    val refId: String? = null,
)

@Serializable
enum class RejectCode {
    MALFORMED,
    BAD_SIGNATURE,
    OUTSIDE_GEOFENCE,
    TAG_MISMATCH,
    NOT_NEXT_WAYPOINT,
    TOO_LATE,
    ESCROW_UNDERFUNDED,
    UNKNOWN_TRIP,
    CLAIM_UNAVAILABLE,
    CASH_OUT_UNKNOWN,
    INSUFFICIENT_BALANCE,
}

sealed interface SubmitResult {
    data class Recorded(val txHash: String, val ledgerTime: Long, val balance: Usdc) : SubmitResult

    /** The transaction's sequence number isn't the account's next one. */
    data class BadSequence(val accountSequence: Long) : SubmitResult

    /** Refused for good; the transaction was not applied. */
    data class Rejected(val code: RejectCode, val message: String) : SubmitResult

    /** Not now (for example the fee account is being topped up); keep it queued. */
    data class Retry(val message: String) : SubmitResult
}

@Serializable
data class OverrideRequest(val tripId: String, val waypointIndex: Int, val fix: GpsFix, val reason: String)

@Serializable
data class CashOutInstructions(
    val cashOutId: String,
    val anchorAccount: String,
    val memoHashHex: String,
    val amount: Usdc,
    val quote: FirmQuote,
)

@Serializable
data class TripStatus(
    val tripId: String,
    val contract: ContractInfo,
    val advances: List<AdvanceInfo>,
    val overrides: List<OverrideInfo>,
    val latePayments: List<LatePayment>,
    val closed: Boolean,
)

@Serializable
data class DriverSnapshot(
    val serverTime: Long,
    val balance: Usdc,
    /** The driver account's sequence number on the ledger. */
    val sequence: Long,
    val offers: List<TripPlan>,
    val trip: TripStatus?,
    val activity: List<ActivityItem>,
    val cashOuts: List<CashOutInfo>,
)
