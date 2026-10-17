package com.galaxy.assettrail.core.platform

import com.galaxy.assettrail.core.ledger.NetworkInfo
import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * The platform as the driver app sees it: the backend's driver API (backend/README.md, "Driver"
 * routes), its signer service, indexer and anchor partner. Each call maps to one backend route, or
 * to a short sequence of them; docs/backend-contract.md lists the mapping. Calls marked "sample
 * data" have no backend route yet and are answered by the demo platform only.
 *
 * The phone never builds contract transactions. It signs Soroban authorization entries for
 * `check_in`, `claim_advance` and `claim_final_pay`, and signs the two classic transactions the
 * platform prepares for it (wallet set-up and cash-out) after checking them.
 */
interface PlatformApi {

    /** `POST /v1/auth/driver/otp`. */
    suspend fun sendPhoneCode(phone: String): CodeSent

    /** `POST /v1/auth/driver/verify`. Returns the driver id; the client keeps the session token. */
    suspend fun verifyPhone(phone: String, code: String): String

    /** ID check with the anchor (SEP-12). Sample data: the backend has no KYC step yet. */
    suspend fun submitKyc(driverId: String, kyc: KycSubmission): KycResult

    /** `GET /v1/network`: the network, contract and pay asset the phone signs for. */
    suspend fun network(): NetworkInfo

    /** T2, step 1 (`POST /v1/driver/wallet/prepare`): the sponsored account + USDC trustline transaction to sign. */
    suspend fun prepareWallet(driverId: String, request: WalletRequest): PreparedTransaction

    /** T2, step 2 (`POST /v1/driver/wallet/submit`): the phone's signature goes back; the fee account submits. */
    suspend fun submitWallet(driverId: String, signedXdr: String): WalletCreated

    /** Everything the screens show (`GET /v1/driver/me`, `/wallet`, `/trips`, `/trips/:id`). Polled while online. */
    suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot

    /** `POST /v1/driver/trips/:id/accept`. */
    suspend fun acceptOffer(driverId: String, tripId: String): TripPlan

    /**
     * Signer service (`POST /v1/driver/trips/:id/checkins/batch`): checks BR-03, co-signs each
     * check-in the phone authorized and submits it, in route order. It stops at the first one that
     * doesn't record, so the result list can be shorter than [checkIns].
     */
    suspend fun submitCheckIns(driverId: String, tripId: String, checkIns: List<SignedCheckIn>): List<CheckInResult>

    /** Tag mismatch alert to ops (§8). Sample data: the backend logs it when it refuses the check-in. */
    suspend fun reportTagMismatch(driverId: String, tripId: String, waypointIndex: Int, scannedTag: String)

    /** `POST /v1/driver/trips/:id/advances`. Ops' decision arrives in the snapshot. */
    suspend fun requestAdvance(driverId: String, tripId: String, amount: Usdc): AdvanceInfo

    /** T7 (`POST /v1/driver/trips/:id/advances/claim`): the driver's authorization for `claim_advance(trip)`. */
    suspend fun claimAdvance(driverId: String, tripId: String, advanceId: String, authEntry: String): AdvanceInfo

    /**
     * `POST /v1/driver/trips/:id/final-pay`: after the delivery deadline, with no signature or
     * dispute, the driver's authorization for `claim_final_pay(trip, sha256(claim))` releases the
     * final leg.
     */
    suspend fun claimFinalPay(driverId: String, tripId: String, claim: SignedClaim): ContractInfo

    /** SEP-38 firm quote (`POST /v1/driver/cashouts/quote`). Its id names the cash-out in the next two calls. */
    suspend fun quote(driverId: String, sell: Usdc): FirmQuote

    /** T9, step 1 (`POST /v1/driver/cashouts/:id/prepare`): the payment to the anchor, to check and sign. */
    suspend fun prepareCashOut(driverId: String, quoteId: String): PreparedTransaction

    /** T9, step 2 (`POST /v1/driver/cashouts/:id/submit`): the fee account wraps the signed payment in a fee bump. */
    suspend fun submitCashOut(driverId: String, quoteId: String, signedXdr: String): CashOutInfo
}

/** No signal: the request never reached the platform. Nothing changed. */
class OfflineException : IOException("No connection")

/** The platform refused the request. [code] is the backend's error code; [message] is written for the driver. */
class PlatformException(val code: String, message: String) : Exception(message)

@Serializable
data class CodeSent(val phone: String, val demoCode: String? = null)

@Serializable
data class KycSubmission(val fullName: String, val idType: String, val idNumber: String)

@Serializable
data class KycResult(val approved: Boolean, val provider: String, val message: String? = null)

/** The backend needs only [accountId]; the rest is the demo's sign-up (sample data). */
@Serializable
data class WalletRequest(
    val accountId: String,
    val fullName: String,
    val phone: String,
    val momoNumber: String,
    val recovery: Boolean,
)

/** A classic transaction the platform built for the phone to check and sign. */
@Serializable
data class PreparedTransaction(
    val xdr: String,
    val networkPassphrase: String,
    /** Sign and send it back before this, or prepare it again (wallet set-up holds it for 60 s). */
    val expiresAt: Long? = null,
)

@Serializable
data class WalletCreated(val accountId: String, val txHash: String)

/** One check-in signed on the phone: the salted payload and the authorization for `check_in`. */
@Serializable
data class SignedCheckIn(val payloadJson: String, val authEntry: String)

/** The final-pay claim payload and the authorization for `claim_final_pay`. */
@Serializable
data class SignedClaim(val payloadJson: String, val authEntry: String)

/** The signer service's refusal codes (backend/src/services/signer.ts and checkins.ts). */
@Serializable
enum class RejectCode {
    BAD_PAYLOAD,
    TRIP_MISMATCH,
    ASSET_MISMATCH,
    WRONG_WAYPOINT,
    EVENT_MISMATCH,
    TAG_MISMATCH,
    LOW_ACCURACY,
    OUTSIDE_GEOFENCE,
    FUTURE_TIME,
    STALE_CHECKIN,
    OUTSIDE_WINDOW,
    BAD_AUTH_ENTRY,
    TRIP_NOT_MOVING,
    UNKNOWN,
    ;

    companion object {
        /** Codes the app doesn't know yet still show the platform's message. */
        fun fromWire(code: String): RejectCode = entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

sealed interface CheckInResult {
    /**
     * On the ledger. [duplicate]: it already was, from an earlier upload of the same payload.
     * [balance] is the wallet balance after it, when the platform reports one.
     */
    data class Recorded(val txHash: String, val ledgerTime: Long, val balance: Usdc?, val duplicate: Boolean = false) : CheckInResult

    /** Refused for good; nothing was recorded. */
    data class Rejected(val code: RejectCode, val message: String) : CheckInResult

    /** Not now (the ledger is unreachable, or the fee account is being topped up); keep it queued. */
    data class Retry(val message: String) : CheckInResult
}

@Serializable
data class TripStatus(
    val tripId: String,
    val contract: ContractInfo,
    val advances: List<AdvanceInfo>,
    val closed: Boolean,
)

@Serializable
data class DriverSnapshot(
    val serverTime: Long,
    /** The latest closed ledger: the phone dates its offline authorizations from it. */
    val latestLedger: Long,
    val balance: Usdc,
    val offers: List<TripPlan>,
    val trip: TripStatus?,
    val activity: List<ActivityItem>,
    val cashOuts: List<CashOutInfo>,
)
