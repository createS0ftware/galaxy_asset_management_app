package com.galaxy.assettrail.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The backend's JSON, as the driver routes return it. Unknown fields are ignored. */
internal val BackendJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Custody event kinds (backend/src/lib/payload.ts `Kind`). */
internal object Kinds {
    const val DISPATCH = 3
    const val DELIVERY = 7
    const val RESOLVE = 9
    const val CANCEL = 12
    const val RELEASE = 15
}

@Serializable
internal class ErrorBody(val error: String? = null, val message: String? = null)

@Serializable
internal class NetworkDto(
    val networkPassphrase: String,
    val contractId: String,
    val payAsset: String,
    val latestLedger: Long,
    val ledgerSeconds: Int = 5,
    val maxOfflineHours: Int = 12,
)

@Serializable
internal class PhoneRequest(val phone: String)

@Serializable
internal class TrackingRefDto(val trackingUrl: String)

@Serializable
internal class SignDeliveryRequest(val code: String, val tag: String, val noDamage: Boolean)

/** `devCode` only when the backend runs a demo set-up without SMS (SHOW_OTP_IN_RESPONSE). */
@Serializable
internal class OtpSentDto(val sent: Boolean = true, val devCode: String? = null)

@Serializable
internal class VerifyRequest(val phone: String, val code: String)

@Serializable
internal class VerifyResponse(val token: String, val driver: DriverRef)

@Serializable
internal class DriverRef(val id: Long, val name: String? = null)

@Serializable
internal class MeDto(val id: Long, val name: String? = null, val walletStatus: String? = null, val momoNumber: String? = null)

@Serializable
internal class PublicKeyRequest(val publicKey: String)

@Serializable
internal class PreparedWalletDto(val xdr: String, val networkPassphrase: String, val expiresInSeconds: Long = 60)

@Serializable
internal class XdrRequest(val xdr: String)

@Serializable
internal class WalletSubmittedDto(val address: String, val txHash: String)

@Serializable
internal class PreparedDto(val xdr: String, val networkPassphrase: String)

@Serializable
internal class WalletDto(
    val balance: String,
    val activity: List<ActivityDto> = emptyList(),
    val cashouts: List<CashoutDto> = emptyList(),
)

@Serializable
internal class ActivityDto(
    val type: String,
    val kind: Int? = null,
    val id: Long? = null,
    val trip: String? = null,
    val wp: Int? = null,
    val amount: String,
    val txHash: String? = null,
    val at: String? = null,
    val status: String? = null,
)

@Serializable
internal class CashoutDto(
    val id: Long,
    val amount: String,
    val fee: String = "0",
    val buyAmount: String = "",
    val buyCurrency: String? = null,
    val arrival: String? = null,
    val status: String,
    val txHash: String? = null,
    val error: String? = null,
    val createdAt: String,
)

@Serializable
internal class TripDto(
    val id: String,
    val status: String,
    val asset: AssetDto? = null,
    val receiver: ReceiverDto? = null,
    val escrow: EscrowDto,
    val deliveryDeadline: String? = null,
    val waypoints: List<WaypointDto>,
    val advances: List<AdvanceDto> = emptyList(),
    val events: List<EventDto>? = null,
    val createdAt: String,
)

@Serializable
internal class AssetDto(val id: String, val name: String, val type: String? = null)

@Serializable
internal class ReceiverDto(val name: String? = null)

@Serializable
internal class EscrowDto(val advances: String = "0", val advanceLimit: String = "0")

@Serializable
internal class WaypointDto(
    val idx: Int,
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val windowStart: String,
    val windowEnd: String,
    val legPay: String,
)

@Serializable
internal class AdvanceDto(
    val id: Long,
    val amount: String,
    val status: String,
    val expiresAt: String? = null,
    val claimTx: String? = null,
    val createdAt: String,
)

@Serializable
internal class EventDto(
    val kind: Int,
    val wp: Int = 0,
    val hash: String? = null,
    /** Stroops. */
    val amount: Long = 0,
    val status: String,
    val txHash: String? = null,
    val ledgerTime: String? = null,
)

@Serializable
internal class CheckInItemDto(val payload: JsonElement, val authEntry: String)

@Serializable
internal class CheckInBatchDto(val items: List<CheckInItemDto>)

@Serializable
internal class CheckInBatchResultDto(val results: List<CheckInResultDto> = emptyList())

@Serializable
internal class CheckInResultDto(
    val ok: Boolean,
    val duplicate: Boolean = false,
    val txHash: String? = null,
    val balance: String? = null,
    val event: EventDto? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
internal class AmountRequest(val amount: String)

@Serializable
internal class AuthEntryRequest(val authEntry: String)

@Serializable
internal class FinalPayRequest(val payload: JsonElement, val authEntry: String)

@Serializable
internal class FinalPayDto(val txHash: String, val payout: String, val deduction: String, val balance: String)

@Serializable
internal class QuoteDto(
    val id: Long,
    val youSend: String,
    val anchorFee: String,
    val rate: String,
    val youReceive: String,
    val arrival: String? = null,
    val validUntil: String,
    val payTo: String? = null,
    val memo: String? = null,
)

@Serializable
internal class CashoutRowDto(
    val id: Long,
    val amount: String,
    val quote: QuoteJson? = null,
    val status: String,
    val txHash: String? = null,
    val error: String? = null,
    val createdAt: String,
)

/** The quote as stored with a cash-out: `fee` in stroops. */
@Serializable
internal class QuoteJson(val fee: String? = null, val buyAmount: String? = null, val buyAsset: String? = null, val arrival: String? = null)
