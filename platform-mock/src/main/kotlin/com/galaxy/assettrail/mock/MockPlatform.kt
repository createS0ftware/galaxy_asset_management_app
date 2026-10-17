package com.galaxy.assettrail.mock

import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.ledger.AuthEntries
import com.galaxy.assettrail.core.ledger.DriverTransactions
import com.galaxy.assettrail.core.ledger.LedgerConfig
import com.galaxy.assettrail.core.ledger.NetworkInfo
import com.galaxy.assettrail.core.ledger.TrailCalls
import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.ActivityKind
import com.galaxy.assettrail.core.model.ActivityStatus
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.payload.FinalPayClaim
import com.galaxy.assettrail.core.payload.Hashes
import com.galaxy.assettrail.core.payload.PayloadVerifier
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.payload.WaypointPayload
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
import com.galaxy.assettrail.core.store.StateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.sdk.Account
import org.stellar.sdk.ChangeTrustAsset
import org.stellar.sdk.FeeBumpTransaction
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.StrKey
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.BeginSponsoringFutureReservesOperation
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.EndSponsoringFutureReservesOperation
import org.stellar.sdk.operations.PaymentOperation
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import kotlin.random.Random

/** The other people in the story: receiver, ops and the platform's own switches. Demo builds only. */
interface DemoControls {
    suspend fun receiverSigns(tripId: String)

    suspend fun receiverReportsProblem(tripId: String)

    suspend fun opsResolvesDisputeForDriver(tripId: String)

    suspend fun expireDeliveryDeadline(tripId: String)

    suspend fun flags(): DemoFlags

    suspend fun updateFlags(change: (DemoFlags) -> DemoFlags)

    suspend fun reset()
}

/**
 * In-process stand-in for the backend (galaxy_asset_management_backend): the driver API, the
 * signer service, the fee account, the Asset Trail contract, the indexer and a SEP-6/SEP-38
 * anchor. It exists so the app runs end to end offline from any network, and it behaves the way
 * the backend does for the phone (docs/backend-contract.md): it checks the phone's real Soroban
 * authorization entries, signature included, applies BR-03, and builds the wallet and cash-out
 * transactions for the phone to sign. Nothing is sent to a Stellar network.
 *
 * Two BR-03 rules are relaxed so the demo can run in minutes from anywhere: time windows aren't
 * checked, and a check-in outside the geofence is accepted (the app warns first). The backend
 * refuses both (OUTSIDE_WINDOW, OUTSIDE_GEOFENCE).
 */
class MockPlatform(
    private val store: StateStore<SimState>,
    private val config: LedgerConfig,
    private val clock: Clock,
    private val isOnline: () -> Boolean,
    private val productName: String = "Asset Trail",
    private val timings: SimTimings = SimTimings(),
    private val latency: suspend () -> Unit = { delay(Random.nextLong(250, 700)) },
) : PlatformApi, DemoControls {

    private val mutex = Mutex()
    private var cached: SimState? = null

    // region Plumbing

    private suspend fun load(): SimState = cached ?: (store.read() ?: newState(clock.millis())).also { cached = it }

    private suspend fun <T> online(block: (SimState, Long) -> T): T {
        if (!isOnline()) throw OfflineException()
        latency()
        if (!isOnline()) throw OfflineException()
        return locked(block)
    }

    private suspend fun <T> locked(block: (SimState, Long) -> T): T = mutex.withLock {
        val state = load()
        val now = clock.millis()
        state.process(now)
        try {
            block(state, now)
        } finally {
            store.write(state)
        }
    }

    private fun newState(now: Long) = SimState(
        seeds = SimSeeds(
            platformSigner = String(KeyPair.random().secretSeed),
            feeAccount = String(KeyPair.random().secretSeed),
            treasury = String(KeyPair.random().secretSeed),
            anchor = String(KeyPair.random().secretSeed),
        ),
        contractId = StrKey.encodeContract(Hashes.randomBytes(32)),
        ledgerEpoch = now,
    )

    /** The ledger the toy network has reached: one every [NetworkInfo.ledgerSeconds] since [SimState.ledgerEpoch]. */
    private fun SimState.latestLedger(now: Long): Long =
        ledgerSeq + maxOf(0L, (now - ledgerEpoch) / 1000 / NetworkInfo.DEFAULT_LEDGER_SECONDS)

    private fun SimState.driver(driverId: String): SimDriver =
        drivers[driverId] ?: throw PlatformException("UNAUTHORIZED", "Sign in again.")

    private fun SimState.walletOf(driver: SimDriver): SimAccount =
        driver.accountId?.let { accounts[it] } ?: throw PlatformException("DRIVER_WALLET_NOT_READY", "Your wallet isn't set up yet.")

    private fun SimState.activeTrip(driverId: String, tripId: String): SimTrip =
        trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.ACTIVE }
            ?: throw PlatformException("TRIP_NOT_MOVING", "This trip isn't active.")

    /** A transaction the platform submits from the fee account; returns its hash. */
    private fun SimState.platformTx(time: Long, label: String): String {
        txCounter++
        val hash = Hashes.hex(Hashes.sha256("$label|$txCounter|$time|${Random.nextLong()}"))
        ledger += SimLedgerEntry(hash, time, label)
        return hash
    }

    private fun SimState.addActivity(driverId: String, item: ActivityItem) {
        activity.getOrPut(driverId) { mutableListOf() }.add(item)
    }

    private fun SimState.updateActivity(driverId: String, id: String, change: (ActivityItem) -> ActivityItem) {
        val items = activity[driverId] ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = change(items[index])
    }

    private fun shortId(): String = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")

    private fun normalisePhone(phone: String) = phone.filter { it.isDigit() || it == '+' }

    private fun parse(xdr: String): Transaction =
        runCatching { DriverTransactions.parse(xdr, config.network) }.getOrNull()
            ?: throw PlatformException("BAD_XDR", "The signed transaction couldn't be read.")

    // endregion

    // region Timers: decisions and settlements the other parties make while the app polls

    private fun SimState.process(now: Long) {
        trips.values.forEach { trip ->
            trip.advances.filter { it.info.status == AdvanceStatus.REQUESTED && it.decideAt <= now }
                .forEach { decideAdvance(trip, it, now) }
            // grant_advance's expiry: an unclaimed advance stays in escrow.
            trip.advances.filter { it.info.status == AdvanceStatus.READY && now >= (it.info.claimDeadline ?: Long.MAX_VALUE) }
                .forEach { it.info = it.info.copy(status = AdvanceStatus.EXPIRED) }
        }
        cashOuts.values.filter { it.info.status == CashOutStatus.PENDING && (it.completeAt ?: Long.MAX_VALUE) <= now }
            .forEach { settleCashOut(it, now) }
    }

    /** T6: ops approves, and `grant_advance` makes it claimable from escrow until the deadline. */
    private fun SimState.decideAdvance(trip: SimTrip, advance: SimAdvance, now: Long) {
        val amount = advance.info.amount
        val reason = when {
            demo.declineNextAdvance -> "Ops declined it: the next leg pays within the hour."
            trip.escrow < amount -> "The trip's advance budget is used up."
            else -> null
        }
        if (reason != null) {
            demo = demo.copy(declineNextAdvance = false)
            advance.info = advance.info.copy(status = AdvanceStatus.DECLINED, decidedAt = now, declineReason = reason)
            return
        }
        val deadline = now + timings.advanceClaimWindowMillis
        platformTx(now, "grant_advance · ${amount.display()} USDC for ${trip.plan.id}")
        advance.info = advance.info.copy(status = AdvanceStatus.READY, decidedAt = now, claimDeadline = deadline)
    }

    private fun SimState.settleCashOut(cashOut: SimCashOut, now: Long) {
        val info = cashOut.info
        if (cashOut.willFail) {
            val driver = drivers.getValue(cashOut.driverId)
            walletOf(driver).usdc += info.sell
            val refundTx = platformTx(now, "Anchor refund ${info.sell.display()} USDC for ${info.id}")
            cashOut.info = info.copy(
                status = CashOutStatus.REFUNDED,
                updatedAt = now,
                message = "MTN MoMo didn't accept the payout. Your ${info.sell.display()} USDC is back in your wallet.",
            )
            updateActivity(driver.id, activityIdFor(info.id)) { it.copy(status = ActivityStatus.FAILED, note = "Failed · refunded") }
            addActivity(
                driver.id,
                ActivityItem(
                    id = "act-refund-${info.id}",
                    kind = ActivityKind.REFUND,
                    title = "Refund · cash out failed",
                    amount = info.sell,
                    incoming = true,
                    time = now,
                    txHash = refundTx,
                ),
            )
        } else {
            cashOut.info = info.copy(
                status = CashOutStatus.COMPLETED,
                updatedAt = now,
                message = "${info.buyCurrency} ${info.buyAmount} sent to MTN MoMo ${maskPhone(info.momoNumber)}.",
            )
            updateActivity(cashOut.driverId, activityIdFor(info.id)) { it.copy(status = ActivityStatus.COMPLETED) }
        }
    }

    private fun activityIdFor(cashOutId: String) = "act-$cashOutId"

    // endregion

    // region Sign-in and wallet (sample data where the backend has no route yet)

    override suspend fun sendPhoneCode(phone: String): CodeSent = online { state, _ ->
        val normalised = normalisePhone(phone)
        if (normalised.count(Char::isDigit) < 9) throw PlatformException("BAD_REQUEST", "Enter your full phone number.")
        state.phoneCodes[normalised] = DEMO_CODE
        CodeSent(normalised, demoCode = DEMO_CODE)
    }

    override suspend fun verifyPhone(phone: String, code: String): String = online { state, _ ->
        val normalised = normalisePhone(phone)
        val expected = state.phoneCodes[normalised] ?: throw PlatformException("UNAUTHORIZED", "Ask for a new code.")
        if (code.trim() != expected) {
            throw PlatformException("UNAUTHORIZED", "That code doesn't match. Check the SMS and try again.")
        }
        state.phoneCodes.remove(normalised)
        // Sample data: the backend only signs in drivers ops registered; the demo registers on first sign-in.
        val driver = state.drivers.values.firstOrNull { it.phone == normalised }
            ?: SimDriver(id = "DRV-${shortId()}", phone = normalised).also { state.drivers[it.id] = it }
        driver.id
    }

    override suspend fun submitKyc(driverId: String, kyc: KycSubmission): KycResult = online { state, _ ->
        val driver = state.driver(driverId)
        if (kyc.fullName.trim().length < 3 || kyc.idNumber.filter(Char::isLetterOrDigit).length < 6) {
            throw PlatformException("BAD_REQUEST", "Enter your full name and the number on your ID card.")
        }
        driver.fullName = kyc.fullName.trim()
        driver.kycApproved = true
        KycResult(approved = true, provider = ANCHOR_NAME)
    }

    override suspend fun network(): NetworkInfo = online { state, _ -> config.networkInfo(state.contractId) }

    /** T2 as the backend builds it: the fee account sponsors the account and trustline; the phone signs. */
    override suspend fun prepareWallet(driverId: String, request: WalletRequest): PreparedTransaction = online { state, now ->
        val driver = state.driver(driverId)
        if (!driver.kycApproved) throw PlatformException("BAD_REQUEST", "Finish the ID check first.")
        if (driver.accountId != null) throw PlatformException("WALLET_READY", "Your wallet is already set up.")
        if (!StrKey.isValidEd25519PublicKey(request.accountId)) throw PlatformException("BAD_PUBLIC_KEY", "Wallet key is invalid.")
        if (state.drivers.values.any { it.id != driverId && it.accountId == request.accountId }) {
            throw PlatformException("KEY_IN_USE", "This wallet key belongs to another driver.")
        }
        if (normalisePhone(request.momoNumber).count(Char::isDigit) < 9) {
            throw PlatformException("BAD_REQUEST", "Enter the full MTN MoMo number.")
        }
        val fee = KeyPair.fromSecretSeed(state.seeds.feeAccount).accountId
        val expiresAt = now + timings.walletPrepareMillis
        val tx = TransactionBuilder(Account(fee, state.feeSequence), config.network)
            .setBaseFee(config.baseFee)
            .addPreconditions(TransactionPreconditions.builder().timeBounds(TimeBounds(0, expiresAt / 1000)).build())
            .addOperation(BeginSponsoringFutureReservesOperation.builder().sponsoredId(request.accountId).sourceAccount(fee).build())
            .addOperation(CreateAccountOperation.builder().destination(request.accountId).startingBalance(BigDecimal.ZERO).sourceAccount(fee).build())
            .addOperation(
                ChangeTrustOperation.builder()
                    .asset(ChangeTrustAsset(config.usdc))
                    .limit(MAX_TRUST_LIMIT)
                    .sourceAccount(request.accountId)
                    .build(),
            )
            .addOperation(EndSponsoringFutureReservesOperation.builder().sourceAccount(request.accountId).build())
            .build()
        driver.pendingWallet = SimPendingWallet(
            accountId = request.accountId,
            txHash = tx.hashHex(),
            expiresAt = expiresAt,
            momo = normalisePhone(request.momoNumber),
            recovery = request.recovery,
        )
        PreparedTransaction(tx.toEnvelopeXdrBase64(), config.network.networkPassphrase, expiresAt)
    }

    override suspend fun submitWallet(driverId: String, signedXdr: String): WalletCreated = online { state, now ->
        val driver = state.driver(driverId)
        val pending = driver.pendingWallet?.takeIf { now <= it.expiresAt }
            ?: throw PlatformException("NO_PENDING_WALLET", "Prepare the wallet again.")
        val tx = parse(signedXdr)
        if (tx.hashHex() != pending.txHash) throw PlatformException("TX_MISMATCH", "Signed transaction differs from the prepared one.")
        if (!DriverTransactions.isSignedBy(tx, pending.accountId)) throw PlatformException("NOT_SIGNED", "Missing the wallet key signature.")
        tx.sign(KeyPair.fromSecretSeed(state.seeds.feeAccount))
        state.feeSequence++
        state.accounts[pending.accountId] = SimAccount(pending.accountId, sequence = state.latestLedger(now) shl 32, usdc = Usdc.ZERO)
        driver.accountId = pending.accountId
        driver.momo = pending.momo
        driver.recovery = pending.recovery
        driver.pendingWallet = null
        val txHash = tx.hashHex()
        state.ledger += SimLedgerEntry(txHash, now + timings.ledgerCloseMillis, "T2 · sponsored wallet + USDC trustline for ${driver.id}")
        if (state.trips.values.none { it.driverId == driverId && it.state in OPEN_STATES }) {
            state.offerNextTrip(driverId, now)
        }
        WalletCreated(pending.accountId, txHash)
    }

    // endregion

    // region Trips

    override suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot = online { state, now ->
        val driver = state.driver(driverId)
        val account = driver.accountId?.let { state.accounts[it] }
        DriverSnapshot(
            serverTime = now,
            latestLedger = state.latestLedger(now),
            balance = account?.usdc ?: Usdc.ZERO,
            offers = state.trips.values
                .filter { it.driverId == driverId && it.state == SimTripState.OFFERED }
                .map { it.plan },
            trip = activeTripId?.let { state.trips[it] }?.takeIf { it.driverId == driverId }?.toStatus(),
            activity = state.activity[driverId].orEmpty().sortedByDescending { it.time },
            cashOuts = state.cashOuts.values.filter { it.driverId == driverId }.map { it.info }.sortedByDescending { it.createdAt },
        )
    }

    private fun SimTrip.toStatus() = TripStatus(
        tripId = plan.id,
        contract = contract,
        advances = advances.map { it.info },
        closed = state == SimTripState.CLOSED,
    )

    override suspend fun acceptOffer(driverId: String, tripId: String): TripPlan = online { state, now ->
        val trip = state.trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.OFFERED }
            ?: throw PlatformException("NOT_OFFERED", "This trip is no longer available.")
        if (now > trip.plan.offerExpiresAt) throw PlatformException("NOT_OFFERED", "This offer has expired.")
        if (state.trips.values.any { it.driverId == driverId && it.state == SimTripState.ACTIVE }) {
            throw PlatformException("TRIP_ACTIVE", "Finish your current trip first.")
        }
        trip.state = SimTripState.ACTIVE
        trip.plan
    }

    /** T3: `create_trip` pulls every leg plus the advance limit into the contract (BR-02). */
    private fun SimState.offerNextTrip(driverId: String, now: Long) {
        val number = tripCounter++
        val template = DemoScenario.templateFor(number)
        val tripId = "TR-$number"
        val fundingTx = platformTx(now, "create_trip · fund $tripId")
        val plan = DemoScenario.plan(template = template, tripId = tripId, now = now, fundingTx = fundingTx)
        val finalLeg = plan.delivery.legPay
        val trip = SimTrip(
            plan = plan,
            driverId = driverId,
            state = SimTripState.OFFERED,
            escrow = plan.legTotal - finalLeg + plan.advanceLimit,
            contract = ContractInfo(ContractStatus.LOCKED, locked = finalLeg, deadline = plan.deliveryDeadline, updatedAt = now),
        )
        check(trip.escrow + trip.contract.locked >= plan.escrowRequired)
        trips[tripId] = trip
    }

    // endregion

    // region Signer service

    override suspend fun submitCheckIns(driverId: String, tripId: String, checkIns: List<SignedCheckIn>): List<CheckInResult> =
        online { state, now ->
            val results = mutableListOf<CheckInResult>()
            for (item in checkIns) {
                val result = state.checkIn(driverId, tripId, item, now)
                results += result
                if (result !is CheckInResult.Recorded) break
            }
            results
        }

    /** One check-in, the way the backend's signer service handles it (backend/src/services/checkins.ts). */
    private fun SimState.checkIn(driverId: String, tripId: String, item: SignedCheckIn, now: Long): CheckInResult {
        fun reject(code: RejectCode, message: String) = CheckInResult.Rejected(code, message)

        val driver = driver(driverId)
        val account = walletOf(driver)
        val trip = trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.ACTIVE }
            ?: return reject(RejectCode.TRIP_NOT_MOVING, "This trip isn't active.")
        val plan = trip.plan
        val hash = runCatching { PayloadVerifier.hash(item.payloadJson) }.getOrNull()
            ?: return reject(RejectCode.BAD_PAYLOAD, "The check-in record couldn't be read.")
        recorded[Hashes.hex(hash)]?.let { return CheckInResult.Recorded(it.txHash, it.ledgerTime, account.usdc, duplicate = true) }
        val payload = runCatching { WaypointPayload.parse(item.payloadJson) }.getOrNull()
            ?: return reject(RejectCode.BAD_PAYLOAD, "The check-in record couldn't be read.")

        // BR-03 (backend/src/services/signer.ts checkWaypoint).
        val expected = plan.waypoints.getOrNull(trip.nextWaypoint)
            ?: return reject(RejectCode.WRONG_WAYPOINT, "Every stop on this trip is already recorded.")
        val last = plan.delivery.index
        when {
            payload.version != 1 -> return reject(RejectCode.BAD_PAYLOAD, "Unsupported payload version.")
            payload.trip != plan.id -> return reject(RejectCode.TRIP_MISMATCH, "Payload is for a different trip.")
            payload.asset != plan.asset.id -> return reject(RejectCode.ASSET_MISMATCH, "Payload is for a different asset.")
            payload.waypoint != expected.index -> return reject(RejectCode.WRONG_WAYPOINT, "Check in at ${expected.name} first.")
            payload.event != WaypointEvent.at(payload.waypoint, last) ->
                return reject(RejectCode.EVENT_MISMATCH, "Waypoint ${payload.waypoint} expects event \"${WaypointEvent.at(payload.waypoint, last).wire}\".")
            payload.tag != plan.asset.tagId -> {
                alerts += "Tag mismatch on ${plan.id} at ${expected.name}: scanned ${payload.tag}"
                return reject(RejectCode.TAG_MISMATCH, "The scanned tag isn't this trip's ${plan.asset.shortName.lowercase()}. Ops has been alerted.")
            }
            payload.photos.any { !PHOTO_HASH.matches(it) } -> return reject(RejectCode.BAD_PAYLOAD, "Photos must be \"sha256:<hex>\" strings.")
            payload.accuracyM > expected.geofenceRadiusM ->
                return reject(RejectCode.LOW_ACCURACY, "GPS accuracy ${payload.accuracyM} m is worse than the ${expected.geofenceRadiusM} m geofence.")
        }
        val distance = Geo.distanceRoundedM(GeoPoint(payload.lat, payload.lon), expected.position)
        if (distance > expected.geofenceRadiusM) {
            // Demo only: the backend refuses this with OUTSIDE_GEOFENCE.
            alerts += "Outside the geofence on ${plan.id} at ${expected.name}: $distance m from the centre (demo: accepted)"
        }
        val deviceTime = payload.deviceTime.toEpochMilli()
        if (deviceTime > now + FUTURE_TOLERANCE_MILLIS) return reject(RejectCode.FUTURE_TIME, "Device time is in the future.")
        if (now - deviceTime > NetworkInfo.DEFAULT_MAX_OFFLINE_HOURS * 3_600_000L) {
            return reject(
                RejectCode.STALE_CHECKIN,
                "Saved more than ${NetworkInfo.DEFAULT_MAX_OFFLINE_HOURS} hours ago, so it can't record without ops. Ops has been asked to look at it.",
            )
        }

        val invocation = TrailCalls.checkIn(contractId, plan.id, expected.index, hash)
        AuthEntries.problem(item.authEntry, account.id, invocation, latestLedger(now), config.network)
            ?.let { return reject(RejectCode.BAD_AUTH_ENTRY, it) }
        if (demo.feeAccountLow) {
            return CheckInResult.Retry("$productName is topping up network fees. Saved check-ins will record automatically.")
        }

        // check_in: one contract call records the hash and pays the leg (BR-04).
        val ledgerTime = now + timings.ledgerCloseMillis
        val txHash = platformTx(ledgerTime, "check_in · ${payload.event.wire} · ${plan.id} · ${expected.name}")
        payloads[Hashes.hex(hash)] = PayloadVerifier.canonical(item.payloadJson)
        recorded[Hashes.hex(hash)] = SimRecord(txHash, ledgerTime)
        trip.nextWaypoint++
        val legPay = if (expected.index == last) Usdc.ZERO else expected.legPay
        if (legPay.isPositive) {
            trip.escrow -= legPay
            account.usdc += legPay
            addActivity(driver.id, legActivity(plan, expected.index, legPay, ledgerTime, txHash))
        }
        if (expected.index == last) trip.arrivedAt = ledgerTime
        return CheckInResult.Recorded(txHash, ledgerTime, account.usdc)
    }

    private fun legActivity(plan: TripPlan, index: Int, amount: Usdc, time: Long, txHash: String) =
        ActivityItem(
            id = "act-$txHash",
            kind = ActivityKind.LEG_PAY,
            title = "Leg $index · ${plan.waypoints[index].name}",
            amount = amount,
            incoming = true,
            time = time,
            tripId = plan.id,
            txHash = txHash,
        )

    override suspend fun reportTagMismatch(driverId: String, tripId: String, waypointIndex: Int, scannedTag: String) =
        online { state, _ ->
            val trip = state.activeTrip(driverId, tripId)
            state.alerts += "Tag mismatch on ${trip.plan.id} at stop $waypointIndex: scanned $scannedTag"
            Unit
        }

    // endregion

    // region Advances and final pay

    override suspend fun requestAdvance(driverId: String, tripId: String, amount: Usdc): AdvanceInfo = online { state, now ->
        val trip = state.activeTrip(driverId, tripId)
        val available = AdvanceRules.available(trip.plan, trip.advances.map { it.info })
        if (!amount.isPositive || amount > available) {
            throw PlatformException("ADVANCE_OVER_LIMIT", "You can ask for up to ${available.display()} USDC on this trip.")
        }
        val info = AdvanceInfo(id = "ADV-${shortId()}", amount = amount, status = AdvanceStatus.REQUESTED, requestedAt = now)
        trip.advances += SimAdvance(info, decideAt = now + timings.advanceDecisionMillis)
        info
    }

    /** T7: `claim_advance(trip)` with the driver's authorization; the contract pays from escrow. */
    override suspend fun claimAdvance(driverId: String, tripId: String, advanceId: String, authEntry: String): AdvanceInfo =
        online { state, now ->
            val trip = state.activeTrip(driverId, tripId)
            val account = state.walletOf(state.driver(driverId))
            val advance = trip.advances.firstOrNull { it.info.id == advanceId && it.info.status == AdvanceStatus.READY }
                ?: throw PlatformException("NOT_FOUND", "Granted advance not found.")
            AuthEntries.problem(authEntry, account.id, TrailCalls.claimAdvance(state.contractId, tripId), state.latestLedger(now), config.network)
                ?.let { throw PlatformException("BAD_AUTH_ENTRY", it) }
            val amount = advance.info.amount
            val ledgerTime = now + timings.ledgerCloseMillis
            val txHash = state.platformTx(ledgerTime, "claim_advance · ${amount.display()} USDC · $tripId")
            trip.escrow -= amount
            account.usdc += amount
            advance.info = advance.info.copy(status = AdvanceStatus.CLAIMED, claimTx = txHash, claimedAt = ledgerTime)
            state.addActivity(
                driverId,
                ActivityItem(
                    id = "act-$txHash",
                    kind = ActivityKind.ADVANCE,
                    title = "Fuel advance · $tripId",
                    amount = amount,
                    incoming = true,
                    time = ledgerTime,
                    tripId = tripId,
                    txHash = txHash,
                    note = "Comes off your final leg pay",
                ),
            )
            advance.info
        }

    /** `claim_final_pay(trip, claim hash)`: after the deadline, the driver's authorization alone releases the final leg. */
    override suspend fun claimFinalPay(driverId: String, tripId: String, claim: SignedClaim): ContractInfo = online { state, now ->
        val trip = state.activeTrip(driverId, tripId)
        val account = state.walletOf(state.driver(driverId))
        if (trip.contract.status != ContractStatus.LOCKED || trip.arrivedAt == null) {
            throw PlatformException("TRIP_NOT_AWAITING_SIGNATURE", "The final leg can't be claimed now.")
        }
        if (now <= trip.contract.deadline) throw PlatformException("DEADLINE_NOT_PASSED", "The receiver still has time to sign.")
        val payload = runCatching { FinalPayClaim.parse(claim.payloadJson) }.getOrNull()
            ?: throw PlatformException("BAD_PAYLOAD", "The claim couldn't be read.")
        if (payload.trip != tripId) throw PlatformException("TRIP_MISMATCH", "The claim is for a different trip.")
        if (payload.asset != trip.plan.asset.id) throw PlatformException("ASSET_MISMATCH", "The claim is for a different asset.")
        val hash = PayloadVerifier.hash(claim.payloadJson)
        AuthEntries.problem(claim.authEntry, account.id, TrailCalls.claimFinalPay(state.contractId, tripId, hash), state.latestLedger(now), config.network)
            ?.let { throw PlatformException("BAD_AUTH_ENTRY", it) }
        state.payloads[Hashes.hex(hash)] = PayloadVerifier.canonical(claim.payloadJson)
        state.payFinalLeg(trip, ContractStatus.RELEASED_AFTER_DEADLINE, now, "claim_final_pay (driver, after the deadline)")
        trip.contract
    }

    /** The contract pays the final leg, less any advances (BR-06), then the trip closes (T10, BR-10). */
    private fun SimState.payFinalLeg(trip: SimTrip, status: ContractStatus, now: Long, call: String) {
        val driver = drivers.getValue(trip.driverId)
        val account = walletOf(driver)
        val locked = trip.contract.locked
        val deduction = minOf(AdvanceRules.deduction(trip.advances.map { it.info }), locked)
        val payout = locked - deduction
        val txHash = platformTx(now, "$call · ${trip.plan.id}")
        account.usdc += payout
        trip.escrow += deduction
        trip.contract = trip.contract.copy(status = status, payout = payout, deduction = deduction, txHash = txHash, updatedAt = now)
        if (payout.isPositive) {
            addActivity(
                driver.id,
                ActivityItem(
                    id = "act-$txHash",
                    kind = ActivityKind.FINAL_PAY,
                    title = "Final leg · ${trip.plan.delivery.name}",
                    amount = payout,
                    incoming = true,
                    time = now,
                    tripId = trip.plan.id,
                    txHash = txHash,
                    note = if (deduction.isPositive) "${locked.display()} − ${deduction.display()} fuel advance" else null,
                ),
            )
        }
        closeTrip(trip, now)
    }

    private fun SimState.closeTrip(trip: SimTrip, now: Long) {
        platformTx(now, "close_trip · ${trip.plan.id}, ${trip.escrow.display()} USDC back to the funder")
        trip.escrow = Usdc.ZERO
        trip.state = SimTripState.CLOSED
        if (trips.values.none { it.driverId == trip.driverId && it.state in OPEN_STATES }) {
            offerNextTrip(trip.driverId, now)
        }
    }

    // endregion

    // region Anchor: SEP-38 quote, SEP-6 withdrawal

    override suspend fun quote(driverId: String, sell: Usdc): FirmQuote = online { state, now ->
        val driver = state.driver(driverId)
        val account = state.walletOf(driver)
        if (driver.momo == null) throw PlatformException("NO_MOMO", "Link a mobile money number first.")
        if (!sell.isPositive || sell > account.usdc) {
            throw PlatformException("INSUFFICIENT_BALANCE", "You have ${account.usdc.display()} USDC.")
        }
        val onePercent = sell.toBigDecimal().multiply(BigDecimal("0.01")).setScale(2, RoundingMode.UP)
        val fee = minOf(Usdc.of(onePercent.max(MIN_FEE)), sell)
        val buy = (sell - fee).toBigDecimal().multiply(BigDecimal(RATE_GHS)).setScale(2, RoundingMode.DOWN)
        val quote = FirmQuote(
            id = "CO-${shortId()}",
            sell = sell,
            fee = fee,
            rate = RATE_GHS,
            buyAmount = buy.toPlainString(),
            buyCurrency = "GHS",
            expiresAt = now + timings.quoteValidityMillis,
            anchorName = ANCHOR_NAME,
            eta = "Within 5 minutes",
            payTo = KeyPair.fromSecretSeed(state.seeds.anchor).accountId,
            memo = "WD-${shortId()}${shortId()}",
        )
        state.quotes[quote.id] = quote
        quote
    }

    /** T9 as the backend builds it: one payment from the driver's account to the anchor, with its text memo. */
    override suspend fun prepareCashOut(driverId: String, quoteId: String): PreparedTransaction = online { state, now ->
        val driver = state.driver(driverId)
        val account = state.walletOf(driver)
        val quote = state.quotes[quoteId] ?: throw PlatformException("NOT_FOUND", "Get a new quote.")
        if (now > quote.expiresAt) throw PlatformException("QUOTE_EXPIRED", "The quote expired. Check the new one.")
        if (account.usdc < quote.sell) throw PlatformException("INSUFFICIENT_BALANCE", "You have ${account.usdc.display()} USDC.")
        val payTo = quote.payTo!!
        val memo = quote.memo!!
        val tx = TransactionBuilder(Account(account.id, account.sequence), config.network)
            .setBaseFee(config.baseFee)
            .addPreconditions(TransactionPreconditions.builder().timeBounds(TimeBounds(0, now / 1000 + CASH_OUT_TIMEOUT_SECONDS)).build())
            .addMemo(Memo.text(memo))
            .addOperation(PaymentOperation.builder().destination(payTo).asset(config.usdc).amount(quote.sell.toBigDecimal()).build())
            .build()
        state.cashOuts[quote.id] = SimCashOut(
            info = CashOutInfo(
                id = quote.id,
                sell = quote.sell,
                fee = quote.fee,
                buyAmount = quote.buyAmount,
                buyCurrency = quote.buyCurrency,
                momoNumber = driver.momo.orEmpty(),
                status = CashOutStatus.AWAITING_PAYMENT,
                createdAt = now,
                updatedAt = now,
                anchorName = quote.anchorName,
                eta = quote.eta,
            ),
            driverId = driver.id,
            anchorAccount = payTo,
            memo = memo,
            preparedHash = tx.hashHex(),
        )
        PreparedTransaction(tx.toEnvelopeXdrBase64(), config.network.networkPassphrase, now + CASH_OUT_TIMEOUT_SECONDS * 1000)
    }

    override suspend fun submitCashOut(driverId: String, quoteId: String, signedXdr: String): CashOutInfo = online { state, now ->
        val driver = state.driver(driverId)
        val account = state.walletOf(driver)
        val cashOut = state.cashOuts[quoteId]?.takeIf { it.driverId == driver.id }
            ?: throw PlatformException("NOT_FOUND", "Cash-out not found.")
        if (cashOut.info.status != CashOutStatus.AWAITING_PAYMENT) throw PlatformException("CASHOUT_NOT_PREPARED", "This cash out was already sent.")
        val tx = parse(signedXdr)
        if (tx.hashHex() != cashOut.preparedHash) throw PlatformException("TX_MISMATCH", "Signed transaction differs from the prepared one.")
        if (!DriverTransactions.isSignedBy(tx, account.id)) throw PlatformException("NOT_SIGNED", "Missing the wallet key signature.")
        if (account.usdc < cashOut.info.sell) throw PlatformException("INSUFFICIENT_BALANCE", "You have ${account.usdc.display()} USDC.")
        val txHash = state.feeBump(tx)
        val ledgerTime = now + timings.ledgerCloseMillis
        state.ledger += SimLedgerEntry(txHash, ledgerTime, "T9 · cash out ${cashOut.info.sell.display()} USDC to the anchor")
        account.sequence = tx.sequenceNumber
        account.usdc -= cashOut.info.sell
        cashOut.info = cashOut.info.copy(status = CashOutStatus.PENDING, updatedAt = now, txHash = txHash)
        cashOut.completeAt = now + timings.cashOutSettleMillis
        cashOut.willFail = state.demo.failNextCashOut
        state.demo = state.demo.copy(failNextCashOut = false)
        state.quotes.remove(quoteId)
        state.addActivity(
            driver.id,
            ActivityItem(
                id = activityIdFor(cashOut.info.id),
                kind = ActivityKind.CASH_OUT,
                title = "Cash out · MTN MoMo ${maskPhone(cashOut.info.momoNumber)}",
                amount = cashOut.info.sell,
                incoming = false,
                time = ledgerTime,
                txHash = txHash,
                status = ActivityStatus.PENDING,
            ),
        )
        cashOut.info
    }

    /** The fee account pays for the driver's transaction through a fee bump. */
    private fun SimState.feeBump(tx: Transaction): String {
        val fee = KeyPair.fromSecretSeed(seeds.feeAccount)
        val bump = FeeBumpTransaction.createWithBaseFee(fee.accountId, config.baseFee * 2, tx)
        bump.sign(fee)
        return bump.hashHex()
    }

    // endregion

    // region Demo controls

    override suspend fun receiverSigns(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.LOCKED) throw PlatformException("WRONG_STATUS", "This delivery is already settled.")
        state.payFinalLeg(trip, ContractStatus.DELIVERED, now, "confirm_delivery (receiver)")
    }

    /** `report_problem` holds the final leg, but only until the deadline: after it the driver's claim can't be blocked. */
    override suspend fun receiverReportsProblem(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.LOCKED) throw PlatformException("WRONG_STATUS", "This delivery is already settled.")
        if (now > trip.contract.deadline) throw PlatformException("DEADLINE_PASSED", "The deadline to report a problem has passed.")
        val txHash = state.platformTx(now, "report_problem (evidence hash anchored) · ${trip.plan.id}")
        trip.contract = trip.contract.copy(status = ContractStatus.DISPUTED, txHash = txHash, updatedAt = now)
    }

    override suspend fun opsResolvesDisputeForDriver(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.DISPUTED) throw PlatformException("WRONG_STATUS", "There's no open dispute.")
        state.payFinalLeg(trip, ContractStatus.RESOLVED, now, "resolve_dispute (ops rules for the driver)")
    }

    override suspend fun expireDeliveryDeadline(tripId: String) = locked { state, now ->
        val trip = state.trips[tripId] ?: throw PlatformException("NOT_FOUND", "No such trip.")
        trip.contract = trip.contract.copy(deadline = now - 1_000, updatedAt = now)
    }

    override suspend fun flags(): DemoFlags = locked { state, _ -> state.demo }

    override suspend fun updateFlags(change: (DemoFlags) -> DemoFlags) = locked { state, _ ->
        state.demo = change(state.demo)
    }

    override suspend fun reset() = mutex.withLock {
        cached = null
        store.clear()
    }

    private fun SimState.settleableTrip(tripId: String): SimTrip {
        val trip = trips[tripId]?.takeIf { it.state == SimTripState.ACTIVE }
            ?: throw PlatformException("TRIP_NOT_MOVING", "This trip isn't active.")
        if (trip.arrivedAt == null) {
            throw PlatformException("WRONG_STATUS", "The driver hasn't checked in at ${trip.plan.delivery.name} yet.")
        }
        return trip
    }

    // endregion

    companion object {
        const val DEMO_CODE = "246810"
        const val ANCHOR_NAME = "Demo Anchor"

        /** Demo rate only. The real rate comes from the anchor partner's SEP-38 quote. */
        const val RATE_GHS = "15.40"
        private val MIN_FEE = BigDecimal("0.25")
        private val OPEN_STATES = setOf(SimTripState.OFFERED, SimTripState.ACTIVE)
        private val MAX_TRUST_LIMIT = BigDecimal("922337203685.4775807")
        private val PHOTO_HASH = Regex("^sha256:[0-9a-f]{64}$")
        private const val FUTURE_TOLERANCE_MILLIS = 5 * 60_000L
        private const val CASH_OUT_TIMEOUT_SECONDS = 300L

        fun maskPhone(phone: String): String = "••" + phone.filter(Char::isDigit).takeLast(4)
    }
}
