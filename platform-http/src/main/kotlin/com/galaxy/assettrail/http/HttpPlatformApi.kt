package com.galaxy.assettrail.http

import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.ledger.NetworkInfo
import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.ActivityKind
import com.galaxy.assettrail.core.model.ActivityStatus
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.Asset
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.Waypoint
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.platform.CheckInResult
import com.galaxy.assettrail.core.platform.CodeSent
import com.galaxy.assettrail.core.platform.DriverSnapshot
import com.galaxy.assettrail.core.platform.KycResult
import com.galaxy.assettrail.core.platform.KycSubmission
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.PlatformApi
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.PreparedTransaction
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.platform.SignedCheckIn
import com.galaxy.assettrail.core.platform.SignedClaim
import com.galaxy.assettrail.core.platform.TripStatus
import com.galaxy.assettrail.core.platform.WalletCreated
import com.galaxy.assettrail.core.platform.WalletRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * [PlatformApi] over HTTP, for galaxy_asset_management_backend's driver API (`/v1`). Each call
 * maps to the backend route listed in docs/backend-contract.md, and the backend's JSON to the
 * app's models. The session token from the OTP sign-in goes in every driver call; the backend
 * reads the driver from it, so the `driverId` arguments aren't sent.
 */
class HttpPlatformApi(
    baseUrl: String,
    private val transport: HttpTransport,
    private val tokens: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) : PlatformApi {

    private val base = baseUrl.trimEnd('/')

    /** The MoMo number ops registered, for cash-out records. Read once per process. */
    private var momoNumber: String? = null

    // region Sign-in and wallet

    override suspend fun sendPhoneCode(phone: String): CodeSent {
        val normalised = normalisePhone(phone)
        // Answers {sent: true} either way, so it doesn't reveal who's registered. A demo backend
        // without SMS also returns the code, and the sign-in screen shows it.
        val sent: OtpSentDto = post("/v1/auth/driver/otp", PhoneRequest(normalised), auth = false)
        return CodeSent(normalised, demoCode = sent.devCode)
    }

    override suspend fun verifyPhone(phone: String, code: String): String {
        val response: VerifyResponse = post("/v1/auth/driver/verify", VerifyRequest(normalisePhone(phone), code.trim()), auth = false)
        tokens.write(response.token)
        return response.driver.id.toString()
    }

    /** Sample data: the backend has no KYC step; ops registers drivers. */
    override suspend fun submitKyc(driverId: String, kyc: KycSubmission): KycResult =
        KycResult(approved = true, provider = "ops")

    override suspend fun network(): NetworkInfo {
        val network: NetworkDto = get("/v1/network", auth = false)
        return NetworkInfo(network.networkPassphrase, network.contractId, network.payAsset, network.ledgerSeconds, network.maxOfflineHours)
    }

    override suspend fun prepareWallet(driverId: String, request: WalletRequest): PreparedTransaction {
        val prepared: PreparedWalletDto = post("/v1/driver/wallet/prepare", PublicKeyRequest(request.accountId))
        return PreparedTransaction(prepared.xdr, prepared.networkPassphrase, clock.millis() + prepared.expiresInSeconds * 1000)
    }

    override suspend fun submitWallet(driverId: String, signedXdr: String): WalletCreated {
        val submitted: WalletSubmittedDto = post("/v1/driver/wallet/submit", XdrRequest(signedXdr))
        return WalletCreated(submitted.address, submitted.txHash)
    }

    // endregion

    // region Trips

    /** Composed from the driver's trips, wallet and the network's latest ledger: there is no single route. */
    override suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot {
        val trips = decode(call("GET", "/v1/driver/trips", null, auth = true), ListSerializer(TripDto.serializer()))
        val wallet: WalletDto = get("/v1/driver/wallet")
        val network: NetworkDto = get("/v1/network", auth = false)
        val momo = momoNumber ?: get<MeDto>("/v1/driver/me").momoNumber.orEmpty().also { momoNumber = it }
        val now = clock.millis()
        val byId = trips.associateBy { it.id }
        return DriverSnapshot(
            serverTime = now,
            latestLedger = network.latestLedger,
            balance = Usdc.of(wallet.balance),
            offers = trips.filter { it.status == "funded" }.map { it.toPlan() },
            trip = activeTripId?.let(byId::get)?.toStatus(now),
            activity = wallet.activity.mapNotNull { it.toItem(byId) }.sortedByDescending { it.time },
            cashOuts = wallet.cashouts.filter { it.status in PAID_CASH_OUTS }.map { it.toInfo(momo) }.sortedByDescending { it.createdAt },
        )
    }

    override suspend fun acceptOffer(driverId: String, tripId: String): TripPlan =
        post<EmptyBody, TripDto>("/v1/driver/trips/$tripId/accept", EmptyBody).toPlan()

    /**
     * `checkins/batch` answers 200 with one result per check-in it tried. A malformed item fails
     * the whole request with 400; then only the first goes again, so the rest aren't dropped with it.
     */
    override suspend fun submitCheckIns(driverId: String, tripId: String, checkIns: List<SignedCheckIn>): List<CheckInResult> {
        if (checkIns.isEmpty()) return emptyList()
        val body = CheckInBatchDto(checkIns.map { CheckInItemDto(BackendJson.parseToJsonElement(it.payloadJson), it.authEntry) })
        val result = rawCall("POST", "/v1/driver/trips/$tripId/checkins/batch", BackendJson.encodeToString(CheckInBatchDto.serializer(), body), auth = true)
        return when {
            result.status in 200..299 ->
                decode(result.body, CheckInBatchResultDto.serializer()).results.map { it.toResult() }
            result.status == 400 && checkIns.size > 1 -> submitCheckIns(driverId, tripId, checkIns.take(1))
            else -> {
                val error = failure(result, auth = true)
                listOf(if (result.status >= 500 || error.code in RETRY_CODES) CheckInResult.Retry(error.message.orEmpty()) else error.toRejection())
            }
        }
    }

    /** Sample data: the backend alerts ops itself when it refuses a check-in with TAG_MISMATCH. */
    override suspend fun reportTagMismatch(driverId: String, tripId: String, waypointIndex: Int, scannedTag: String) = Unit

    override suspend fun requestAdvance(driverId: String, tripId: String, amount: Usdc): AdvanceInfo =
        post<AmountRequest, AdvanceDto>("/v1/driver/trips/$tripId/advances", AmountRequest(amount.toStellarAmount())).toInfo()

    override suspend fun claimAdvance(driverId: String, tripId: String, advanceId: String, authEntry: String): AdvanceInfo =
        post<AuthEntryRequest, AdvanceDto>("/v1/driver/trips/$tripId/advances/claim", AuthEntryRequest(authEntry)).toInfo()

    override suspend fun claimFinalPay(driverId: String, tripId: String, claim: SignedClaim): ContractInfo {
        val paid: FinalPayDto = post(
            "/v1/driver/trips/$tripId/final-pay",
            FinalPayRequest(BackendJson.parseToJsonElement(claim.payloadJson), claim.authEntry),
        )
        val payout = Usdc.of(paid.payout)
        val deduction = Usdc.of(paid.deduction)
        // The deadline isn't in the answer; the next snapshot fills it in.
        return ContractInfo(
            status = ContractStatus.RELEASED_AFTER_DEADLINE,
            locked = payout + deduction,
            deadline = 0,
            payout = payout,
            deduction = deduction,
            txHash = paid.txHash,
            updatedAt = clock.millis(),
        )
    }

    /**
     * Debug builds only: play the receiver on the backend's tracking routes (`/v1/track/:token`),
     * with the code a demo backend hands back (SHOW_OTP_IN_RESPONSE). Signing releases the final leg.
     */
    suspend fun demoReceiverSigns(tripId: String, tag: String) {
        val trip: TrackingRefDto = get("/v1/driver/trips/$tripId")
        val token = trip.trackingUrl.substringAfterLast('/')
        val otp: OtpSentDto = post("/v1/track/$token/otp", EmptyBody, auth = false)
        val code = otp.devCode
            ?: throw PlatformException("NO_DEMO_CODE", "The backend doesn't hand out demo codes (set SHOW_OTP_IN_RESPONSE=true).")
        post<SignDeliveryRequest, JsonElement>("/v1/track/$token/sign", SignDeliveryRequest(code, tag, noDamage = true), auth = false)
    }

    // endregion

    // region Cash-out

    override suspend fun quote(driverId: String, sell: Usdc): FirmQuote {
        val quote: QuoteDto = post("/v1/driver/cashouts/quote", AmountRequest(sell.toStellarAmount()))
        val receive = quote.youReceive.split(' ', limit = 2)
        return FirmQuote(
            id = quote.id.toString(),
            sell = Usdc.of(quote.youSend),
            fee = Usdc.of(quote.anchorFee),
            rate = quote.rate,
            buyAmount = receive[0],
            buyCurrency = receive.getOrElse(1) { "" },
            expiresAt = millis(quote.validUntil),
            anchorName = ANCHOR_NAME,
            eta = quote.arrival.orEmpty(),
            payTo = quote.payTo,
            memo = quote.memo,
        )
    }

    override suspend fun prepareCashOut(driverId: String, quoteId: String): PreparedTransaction {
        val prepared: PreparedDto = post("/v1/driver/cashouts/$quoteId/prepare", EmptyBody)
        return PreparedTransaction(prepared.xdr, prepared.networkPassphrase)
    }

    override suspend fun submitCashOut(driverId: String, quoteId: String, signedXdr: String): CashOutInfo {
        val row: CashoutRowDto = post("/v1/driver/cashouts/$quoteId/submit", XdrRequest(signedXdr))
        val buyAsset = row.quote?.buyAsset.orEmpty()
        val created = millis(row.createdAt)
        return CashOutInfo(
            id = row.id.toString(),
            sell = Usdc.of(row.amount),
            fee = row.quote?.fee?.toLongOrNull()?.let(::Usdc) ?: Usdc.ZERO,
            buyAmount = row.quote?.buyAmount.orEmpty(),
            buyCurrency = buyAsset.substringAfter(':', buyAsset),
            momoNumber = momoNumber.orEmpty(),
            status = cashOutStatus(row.status),
            createdAt = created,
            updatedAt = clock.millis(),
            anchorName = ANCHOR_NAME,
            eta = row.quote?.arrival.orEmpty(),
            txHash = row.txHash,
            message = row.error,
        )
    }

    // endregion

    // region Plumbing

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true): T =
        decode(call("GET", path, null, auth), serializer<T>())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, auth: Boolean = true): T =
        decode(call("POST", path, BackendJson.encodeToString(serializer<B>(), body), auth), serializer<T>())

    private suspend fun call(method: String, path: String, body: String?, auth: Boolean): String {
        val result = rawCall(method, path, body, auth)
        if (result.status in 200..299) return result.body
        throw failure(result, auth)
    }

    private suspend fun rawCall(method: String, path: String, body: String?, auth: Boolean): HttpResult {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (auth) put("Authorization", "Bearer " + (tokens.read() ?: throw PlatformException(UNAUTHORIZED, SIGN_IN_AGAIN)))
        }
        return try {
            transport.send(method, base + path, body, headers)
        } catch (_: IOException) {
            throw OfflineException()
        }
    }

    /** The backend's `{ error, message }` envelope; an expired session also forgets the token. */
    private fun failure(result: HttpResult, auth: Boolean): PlatformException {
        if (auth && result.status == 401) {
            tokens.clear()
            return PlatformException(UNAUTHORIZED, SIGN_IN_AGAIN)
        }
        val body = runCatching { BackendJson.decodeFromString(ErrorBody.serializer(), result.body) }.getOrNull()
        val message = body?.message?.takeIf { it.isNotBlank() }
            ?: if (result.status >= 500) "The platform is having trouble. Try again shortly." else "The platform refused the request."
        return PlatformException(body?.error ?: "HTTP_${result.status}", message)
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T =
        runCatching { BackendJson.decodeFromString(serializer, text) }
            .getOrElse { throw PlatformException("BAD_RESPONSE", "The platform's answer couldn't be read.") }

    // endregion

    // region Mapping

    private fun TripDto.toPlan(): TripPlan {
        val stops = waypoints.sortedBy { it.idx }.map { it.toWaypoint() }
        val dispatch = events?.lastOrNull { it.kind == Kinds.DISPATCH && it.status == CONFIRMED }
        val assetName = asset?.name ?: "Asset"
        return TripPlan(
            id = id,
            // The backend keeps the tag id server-side and checks the scan itself (TAG_MISMATCH).
            asset = Asset(
                id = asset?.id.orEmpty(),
                name = assetName,
                shortName = asset?.type?.replaceFirstChar(Char::uppercase) ?: assetName,
                tagId = "",
            ),
            waypoints = stops,
            advanceLimit = Usdc.of(escrow.advanceLimit),
            receiverName = receiver?.name ?: "the receiver",
            deliveryDeadline = deliveryDeadline?.let(::millis) ?: Long.MAX_VALUE,
            fundingTx = dispatch?.txHash.orEmpty(),
            routePlanHash = dispatch?.hash.orEmpty(),
            offeredAt = millis(createdAt),
            offerExpiresAt = stops.lastOrNull()?.windowEnd ?: Long.MAX_VALUE,
        )
    }

    private fun WaypointDto.toWaypoint() = Waypoint(
        index = idx,
        name = name,
        type = when (type) {
            "pickup" -> WaypointType.PICKUP
            "delivery" -> WaypointType.DELIVERY
            else -> WaypointType.CHECKPOINT
        },
        position = GeoPoint(lat, lon),
        geofenceRadiusM = radiusM,
        windowStart = millis(windowStart),
        windowEnd = millis(windowEnd),
        legPay = Usdc.of(legPay),
    )

    private fun TripDto.toStatus(now: Long) = TripStatus(
        tripId = id,
        contract = contract(now),
        advances = advances.map { it.toInfo() },
        closed = status in FINISHED,
    )

    /** Where the final leg stands, from the trip's status and its closing custody event. */
    private fun TripDto.contract(now: Long): ContractInfo {
        val locked = waypoints.maxByOrNull { it.idx }?.let { Usdc.of(it.legPay) } ?: Usdc.ZERO
        val closing = events.orEmpty().lastOrNull { it.status == CONFIRMED && it.kind in CLOSING_KINDS }?.takeIf { status in FINISHED }
        val contractStatus = when {
            closing?.kind == Kinds.RELEASE -> ContractStatus.RELEASED_AFTER_DEADLINE
            closing?.kind == Kinds.DELIVERY -> ContractStatus.DELIVERED
            closing?.kind == Kinds.RESOLVE -> ContractStatus.RESOLVED
            closing?.kind == Kinds.CANCEL || status == "cancelled" -> ContractStatus.CANCELLED
            status == "disputed" -> ContractStatus.DISPUTED
            else -> ContractStatus.LOCKED
        }
        val paid = contractStatus in PAID_STATUSES
        return ContractInfo(
            status = contractStatus,
            locked = locked,
            deadline = deliveryDeadline?.let(::millis) ?: Long.MAX_VALUE,
            payout = closing?.takeIf { paid }?.let { Usdc(it.amount) },
            deduction = if (paid) Usdc.of(escrow.advances) else Usdc.ZERO,
            txHash = closing?.txHash,
            updatedAt = closing?.ledgerTime?.let(::millis) ?: now,
        )
    }

    private fun AdvanceDto.toInfo() = AdvanceInfo(
        id = id.toString(),
        amount = Usdc.of(amount),
        status = when (status) {
            "granted" -> AdvanceStatus.READY
            "claimed" -> AdvanceStatus.CLAIMED
            "declined" -> AdvanceStatus.DECLINED
            "expired" -> AdvanceStatus.EXPIRED
            else -> AdvanceStatus.REQUESTED
        },
        requestedAt = millis(createdAt),
        claimDeadline = expiresAt?.let(::millis),
        claimTx = claimTx,
    )

    private fun ActivityDto.toItem(trips: Map<String, TripDto>): ActivityItem? {
        val time = at?.let(::millis) ?: return null
        val amount = Usdc.of(amount.removePrefix("-"))
        return when (type) {
            "leg" -> {
                val stop = trip?.let(trips::get)?.waypoints?.firstOrNull { it.idx == wp }?.name ?: "stop $wp"
                val final = kind in FINAL_KINDS
                ActivityItem(
                    id = "leg-$txHash-$wp",
                    kind = if (final) ActivityKind.FINAL_PAY else ActivityKind.LEG_PAY,
                    title = if (final) "Final leg · $stop" else "Leg $wp · $stop",
                    amount = amount,
                    incoming = true,
                    time = time,
                    tripId = trip,
                    txHash = txHash,
                )
            }
            "advance" -> ActivityItem(
                id = "advance-$txHash",
                kind = ActivityKind.ADVANCE,
                title = "Fuel advance · $trip",
                amount = amount,
                incoming = true,
                time = time,
                tripId = trip,
                txHash = txHash,
                note = "Comes off your final leg pay",
            )
            "cashout" -> if (status !in PAID_CASH_OUTS) null else ActivityItem(
                id = "cashout-$id",
                kind = ActivityKind.CASH_OUT,
                title = "Cash out · mobile money",
                amount = amount,
                incoming = false,
                time = time,
                txHash = txHash,
                status = when (status) {
                    "completed" -> ActivityStatus.COMPLETED
                    "failed" -> ActivityStatus.FAILED
                    else -> ActivityStatus.PENDING
                },
            )
            else -> null
        }
    }

    private fun CashoutDto.toInfo(momo: String): CashOutInfo {
        val created = millis(createdAt)
        return CashOutInfo(
            id = id.toString(),
            sell = Usdc.of(amount),
            fee = Usdc.of(fee),
            buyAmount = buyAmount,
            buyCurrency = buyCurrency.orEmpty(),
            momoNumber = momo,
            status = cashOutStatus(status),
            createdAt = created,
            updatedAt = created,
            anchorName = ANCHOR_NAME,
            eta = arrival.orEmpty(),
            txHash = txHash,
            message = error,
        )
    }

    private fun CheckInResultDto.toResult(): CheckInResult =
        if (ok) {
            CheckInResult.Recorded(
                txHash = txHash ?: event?.txHash.orEmpty(),
                ledgerTime = event?.ledgerTime?.let(::millis) ?: clock.millis(),
                balance = balance?.let(Usdc::of),
                duplicate = duplicate,
            )
        } else {
            val code = code ?: "ERROR"
            if (code in RETRY_CODES) CheckInResult.Retry(message.orEmpty()) else PlatformException(code, message.orEmpty()).toRejection()
        }

    private fun PlatformException.toRejection() = CheckInResult.Rejected(RejectCode.fromWire(code), message.orEmpty())

    private fun cashOutStatus(status: String) = when (status) {
        "completed" -> CashOutStatus.COMPLETED
        "failed" -> CashOutStatus.REFUNDED
        "submitted" -> CashOutStatus.PENDING
        else -> CashOutStatus.AWAITING_PAYMENT
    }

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun normalisePhone(phone: String) = phone.filter { it.isDigit() || it == '+' }

    // endregion

    @kotlinx.serialization.Serializable
    private object EmptyBody

    private companion object {
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val SIGN_IN_AGAIN = "Your session ended. Sign in again."
        const val CONFIRMED = "confirmed"
        const val ANCHOR_NAME = "Payout partner"

        /** Not the check-in's fault: keep it queued and try again. */
        val RETRY_CODES = setOf("ERROR", "INTERNAL", "LEDGER_ERROR", "LEDGER_UNAVAILABLE", "BAD_RESPONSE")

        val FINISHED = setOf("delivered", "cancelled", "closed")
        val CLOSING_KINDS = setOf(Kinds.DELIVERY, Kinds.RESOLVE, Kinds.RELEASE, Kinds.CANCEL)
        val FINAL_KINDS = setOf(Kinds.DELIVERY, Kinds.RESOLVE, Kinds.RELEASE)
        val PAID_STATUSES = setOf(ContractStatus.DELIVERED, ContractStatus.RESOLVED, ContractStatus.RELEASED_AFTER_DEADLINE)

        /** Quoted or prepared cash-outs haven't moved any money yet. */
        val PAID_CASH_OUTS = setOf("submitted", "completed", "failed")
    }
}
