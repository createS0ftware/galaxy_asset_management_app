package com.galaxy.assettrail.mock

import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.ledger.DriverTransactions
import com.galaxy.assettrail.core.ledger.LedgerConfig
import com.galaxy.assettrail.core.ledger.TxReader
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
import com.galaxy.assettrail.core.model.LatePayment
import com.galaxy.assettrail.core.model.OverrideInfo
import com.galaxy.assettrail.core.model.OverrideStatus
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.payload.Hashes
import com.galaxy.assettrail.core.payload.WaypointPayload
import com.galaxy.assettrail.core.platform.CashOutInstructions
import com.galaxy.assettrail.core.platform.CodeSent
import com.galaxy.assettrail.core.platform.DriverSnapshot
import com.galaxy.assettrail.core.platform.KycResult
import com.galaxy.assettrail.core.platform.KycSubmission
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.OverrideRequest
import com.galaxy.assettrail.core.platform.PlatformApi
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.platform.Submission
import com.galaxy.assettrail.core.platform.SubmitResult
import com.galaxy.assettrail.core.platform.TripStatus
import com.galaxy.assettrail.core.platform.TxKind
import com.galaxy.assettrail.core.platform.WalletCreated
import com.galaxy.assettrail.core.platform.WalletRequest
import com.galaxy.assettrail.core.store.StateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.sdk.Account
import org.stellar.sdk.Claimant
import org.stellar.sdk.FeeBumpTransaction
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MemoHash
import org.stellar.sdk.Predicate
import org.stellar.sdk.StrKey
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.ClaimClaimableBalanceOperation
import org.stellar.sdk.operations.CreateClaimableBalanceOperation
import org.stellar.sdk.operations.ManageDataOperation
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

    suspend fun opsPaysPendingLegs(tripId: String)

    suspend fun flags(): DemoFlags

    suspend fun updateFlags(change: (DemoFlags) -> DemoFlags)

    suspend fun reset()
}

/**
 * In-process stand-in for the backend and escrow-contract API that another team is building:
 * payload store, signer service, fee account, delivery escrow contract, indexer and anchor
 * (SEP-6/SEP-38). It exists so the app runs end to end before their API is available, and it
 * mimics the behaviour the app relies on (see docs/backend-contract.md): it reads the driver's
 * real signed Stellar transactions, enforces BR-03, co-signs, wraps them in a fee bump and applies
 * them to a toy ledger. Nothing is sent to a Stellar network.
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

    private suspend fun load(): SimState = cached ?: (store.read() ?: newState()).also { cached = it }

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

    private fun newState() = SimState(
        seeds = SimSeeds(
            platformSigner = String(KeyPair.random().secretSeed),
            feeAccount = String(KeyPair.random().secretSeed),
            treasury = String(KeyPair.random().secretSeed),
            anchor = String(KeyPair.random().secretSeed),
        ),
    )

    private fun SimState.driver(driverId: String): SimDriver =
        drivers[driverId] ?: throw PlatformException("driver_unknown", "Sign in again.")

    private fun SimState.walletOf(driver: SimDriver): SimAccount =
        driver.accountId?.let { accounts[it] } ?: throw PlatformException("wallet_missing", "Your wallet isn't set up yet.")

    private fun SimState.activeTrip(driverId: String, tripId: String): SimTrip =
        trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.ACTIVE }
            ?: throw PlatformException("trip_inactive", "This trip isn't active.")

    private fun SimState.platformTx(now: Long, label: String): String {
        ledgerSeq++
        val hash = Hashes.hex(Hashes.sha256("$label|$ledgerSeq|$now|${Random.nextLong()}"))
        ledger += SimLedgerEntry(hash, now, label)
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

    // endregion

    // region Timers: decisions and settlements the other parties make while the app polls

    private fun SimState.process(now: Long) {
        trips.values.forEach { trip ->
            trip.advances.filter { it.info.status == AdvanceStatus.REQUESTED && it.decideAt <= now }
                .forEach { decideAdvance(trip, it, now) }
            trip.overrides.filter { it.info.status == OverrideStatus.PENDING && it.decideAt <= now }
                .forEach { it.info = it.info.copy(status = OverrideStatus.APPROVED, decidedBy = "Ama K. (ops)", decidedAt = now) }
        }
        claimables.values.filter { now >= it.deadline }.toList().forEach { claimable ->
            // The trip account is the second claimant once the deadline passes: the money goes back.
            claimables.remove(claimable.id)
            trips[claimable.tripId]?.let { trip ->
                trip.escrow += claimable.amount
                trip.advances.find { it.info.id == claimable.advanceId }
                    ?.let { it.info = it.info.copy(status = AdvanceStatus.EXPIRED) }
            }
        }
        cashOuts.values.filter { it.info.status == CashOutStatus.PENDING && (it.completeAt ?: Long.MAX_VALUE) <= now }
            .forEach { settleCashOut(it, now) }
    }

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
        val driverAccount = drivers.getValue(trip.driverId).accountId!!
        val deadline = now + timings.advanceClaimWindowMillis
        val (balanceId, txHash) = createClaimableBalance(trip, driverAccount, amount, deadline, now)
        trip.escrow -= amount
        claimables[balanceId] = SimClaimable(balanceId, trip.plan.id, advance.info.id, amount, driverAccount, deadline)
        ledger += SimLedgerEntry(txHash, now, "T6 · fuel advance ${amount.display()} USDC for ${trip.plan.id}")
        advance.info = advance.info.copy(
            status = AdvanceStatus.READY,
            decidedAt = now,
            claimDeadline = deadline,
            balanceId = balanceId,
        )
    }

    /** T6: a claimable balance from the trip account; the driver can claim before the deadline, E after it. */
    private fun SimState.createClaimableBalance(
        trip: SimTrip,
        driverAccount: String,
        amount: Usdc,
        deadline: Long,
        now: Long,
    ): Pair<String, String> {
        val fee = KeyPair.fromSecretSeed(seeds.feeAccount)
        val feeAccount = accounts.getOrPut(fee.accountId) { SimAccount(fee.accountId, ledgerSeq shl 32, Usdc.ZERO) }
        val deadlineSeconds = deadline / 1000
        val t6 = TransactionBuilder(Account(fee.accountId, feeAccount.sequence), config.network)
            .setBaseFee(config.baseFee)
            .addPreconditions(TransactionPreconditions.builder().timeBounds(TimeBounds(0, now / 1000 + 300)).build())
            .addOperation(
                CreateClaimableBalanceOperation.builder()
                    .asset(config.usdc)
                    .amount(amount.toBigDecimal())
                    .claimants(
                        listOf(
                            Claimant(driverAccount, Predicate.AbsBefore(deadlineSeconds)),
                            Claimant(trip.plan.tripAccount, Predicate.Not(Predicate.AbsBefore(deadlineSeconds))),
                        ),
                    )
                    .sourceAccount(trip.plan.tripAccount)
                    .build(),
            )
            .build()
        feeAccount.sequence++
        ledgerSeq++
        t6.sign(fee)
        t6.sign(KeyPair.fromSecretSeed(seeds.platformSigner))
        return t6.getClaimableBalanceId(0) to t6.hashHex()
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

    // region Onboarding

    override suspend fun sendPhoneCode(phone: String): CodeSent = online { state, _ ->
        val normalised = normalisePhone(phone)
        if (normalised.count(Char::isDigit) < 9) throw PlatformException("phone_invalid", "Enter your full phone number.")
        state.phoneCodes[normalised] = DEMO_CODE
        CodeSent(normalised, demoCode = DEMO_CODE)
    }

    override suspend fun verifyPhone(phone: String, code: String): String = online { state, _ ->
        val normalised = normalisePhone(phone)
        val expected = state.phoneCodes[normalised] ?: throw PlatformException("code_missing", "Ask for a new code.")
        if (code.trim() != expected) {
            throw PlatformException("code_wrong", "That code doesn't match. Check the SMS and try again.")
        }
        state.phoneCodes.remove(normalised)
        val driver = state.drivers.values.firstOrNull { it.phone == normalised }
            ?: SimDriver(id = "DRV-${shortId()}", phone = normalised).also { state.drivers[it.id] = it }
        driver.id
    }

    override suspend fun submitKyc(driverId: String, kyc: KycSubmission): KycResult = online { state, _ ->
        val driver = state.driver(driverId)
        if (kyc.fullName.trim().length < 3 || kyc.idNumber.filter(Char::isLetterOrDigit).length < 6) {
            throw PlatformException("kyc_incomplete", "Enter your full name and the number on your ID card.")
        }
        driver.fullName = kyc.fullName.trim()
        driver.kycApproved = true
        KycResult(approved = true, provider = ANCHOR_NAME)
    }

    override suspend fun createWallet(driverId: String, request: WalletRequest): WalletCreated = online { state, now ->
        val driver = state.driver(driverId)
        if (!driver.kycApproved) throw PlatformException("kyc_required", "Finish the ID check first.")
        if (!StrKey.isValidEd25519PublicKey(request.accountId)) throw PlatformException("account_invalid", "Wallet key is invalid.")
        if (normalisePhone(request.momoNumber).count(Char::isDigit) < 9) {
            throw PlatformException("momo_invalid", "Enter the full MTN MoMo number.")
        }
        val account = state.accounts.getOrPut(request.accountId) {
            SimAccount(request.accountId, sequence = state.ledgerSeq shl 32, usdc = Usdc.ZERO)
        }
        driver.accountId = account.id
        driver.momo = normalisePhone(request.momoNumber)
        driver.recovery = request.recovery
        val txHash = state.platformTx(now, "T2 · create and sponsor wallet + USDC trustline for ${driver.id}")
        if (state.trips.values.none { it.driverId == driverId && it.state in OPEN_STATES }) {
            state.offerNextTrip(driverId, now)
        }
        WalletCreated(account.id, account.sequence, txHash)
    }

    // endregion

    // region Trips

    override suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot = online { state, now ->
        val driver = state.driver(driverId)
        val account = driver.accountId?.let { state.accounts[it] }
        DriverSnapshot(
            serverTime = now,
            balance = account?.usdc ?: Usdc.ZERO,
            sequence = account?.sequence ?: 0,
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
        overrides = overrides.map { it.info },
        latePayments = latePayments.toList(),
        closed = state == SimTripState.CLOSED,
    )

    override suspend fun acceptOffer(driverId: String, tripId: String): TripPlan = online { state, now ->
        val trip = state.trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.OFFERED }
            ?: throw PlatformException("trip_unavailable", "This trip is no longer available.")
        if (now > trip.plan.offerExpiresAt) throw PlatformException("offer_expired", "This offer has expired.")
        if (state.trips.values.any { it.driverId == driverId && it.state == SimTripState.ACTIVE }) {
            throw PlatformException("trip_active", "Finish your current trip first.")
        }
        trip.state = SimTripState.ACTIVE
        trip.plan
    }

    override suspend fun declineOffer(driverId: String, tripId: String) = online { state, now ->
        val trip = state.trips[tripId]?.takeIf { it.driverId == driverId && it.state == SimTripState.OFFERED }
            ?: throw PlatformException("trip_unavailable", "This trip is no longer available.")
        trip.state = SimTripState.DECLINED
        state.platformTx(now, "T10 · ${trip.plan.id} declined, escrow back to treasury")
        if (state.trips.values.none { it.driverId == driverId && it.state in OPEN_STATES }) {
            state.offerNextTrip(driverId, now)
        }
    }

    private fun SimState.offerNextTrip(driverId: String, now: Long) {
        val number = tripCounter++
        val template = DemoScenario.templateFor(number)
        val tripId = "TR-$number"
        val fundingTx = platformTx(now, "T3 · fund $tripId")
        val plan = DemoScenario.plan(
            template = template,
            tripId = tripId,
            now = now,
            assetAccount = assetAccounts.getOrPut(template.asset.id) { KeyPair.random().accountId },
            tripAccount = KeyPair.random().accountId,
            escrowContract = StrKey.encodeContract(Hashes.randomBytes(32)),
            fundingTx = fundingTx,
        )
        val finalLeg = plan.delivery.legPay
        val trip = SimTrip(
            plan = plan,
            driverId = driverId,
            state = SimTripState.OFFERED,
            escrow = plan.legTotal - finalLeg + plan.advanceLimit,
            contract = ContractInfo(ContractStatus.LOCKED, locked = finalLeg, deadline = plan.deliveryDeadline, updatedAt = now),
        )
        // BR-02: dispatch only when escrow covers every leg plus the advance limit.
        check(trip.escrow + trip.contract.locked >= plan.escrowRequired)
        trips[tripId] = trip
    }

    // endregion

    // region Signer service

    override suspend fun submit(driverId: String, submission: Submission): SubmitResult = online { state, now ->
        state.submitLocked(driverId, submission, now)
    }

    private fun SimState.submitLocked(driverId: String, submission: Submission, now: Long): SubmitResult {
        val driver = driver(driverId)
        val account = walletOf(driver)
        val tx = runCatching { TxReader.parse(submission.envelopeXdr, config) }.getOrNull()
            ?: return SubmitResult.Rejected(RejectCode.MALFORMED, "The transaction couldn't be read.")
        if (tx.sourceAccount != account.id || !TxReader.isSignedBy(tx, account.id)) {
            return SubmitResult.Rejected(RejectCode.BAD_SIGNATURE, "The transaction isn't signed by your wallet.")
        }
        if (tx.sequenceNumber != account.sequence + 1) return SubmitResult.BadSequence(account.sequence)
        val maxTime = tx.preconditions?.timeBounds?.maxTime?.toLong() ?: 0L
        if (maxTime != 0L && now / 1000 > maxTime) {
            return SubmitResult.Rejected(RejectCode.TOO_LATE, "Saved too long ago to record without ops. Ops has been asked to approve it.")
        }
        if (demo.feeAccountLow) {
            return SubmitResult.Retry("$productName is topping up network fees. Saved items will record automatically.")
        }
        return when (submission.kind) {
            TxKind.CHECK_IN -> applyCheckIn(driver, account, tx, submission, now)
            TxKind.CLAIM_ADVANCE -> applyClaim(driver, account, tx, now)
            TxKind.CASH_OUT -> applyCashOut(driver, account, tx, submission, now)
        }
    }

    private fun SimState.applyCheckIn(
        driver: SimDriver,
        account: SimAccount,
        tx: Transaction,
        submission: Submission,
        now: Long,
    ): SubmitResult {
        val trip = submission.tripId?.let { trips[it] }?.takeIf { it.driverId == driver.id && it.state == SimTripState.ACTIVE }
            ?: return SubmitResult.Rejected(RejectCode.UNKNOWN_TRIP, "This trip isn't active.")
        val plan = trip.plan
        val payload = submission.payloadJson?.let { runCatching { WaypointPayload.parse(it) }.getOrNull() }
            ?: return SubmitResult.Rejected(RejectCode.MALFORMED, "The check-in record couldn't be read.")
        val hash = payload.hash()
        val memo = (tx.memo as? MemoHash)?.bytes
        val record = tx.operations.getOrNull(0) as? ManageDataOperation
        if (memo == null || !memo.contentEquals(hash) || record == null ||
            record.name != DriverTransactions.WAYPOINT_DATA_KEY || !record.value.contentEquals(hash) ||
            record.sourceAccount != plan.asset.account || tx.operations.size > 2
        ) {
            return SubmitResult.Rejected(RejectCode.MALFORMED, "The location record doesn't match its payload.")
        }
        if (payload.trip != plan.id || payload.asset != plan.asset.id) {
            return SubmitResult.Rejected(RejectCode.MALFORMED, "The check-in is for another trip.")
        }

        // BR-03: next expected waypoint, matching tag, inside the geofence (or an approved override).
        val expected = plan.waypoints.getOrNull(trip.nextWaypoint)
            ?: return SubmitResult.Rejected(RejectCode.NOT_NEXT_WAYPOINT, "Every stop on this trip is already recorded.")
        if (payload.waypoint != expected.index) {
            return SubmitResult.Rejected(RejectCode.NOT_NEXT_WAYPOINT, "Check in at ${expected.name} first.")
        }
        if (payload.tag != plan.asset.tagId) {
            alerts += "Tag mismatch on ${plan.id} at ${expected.name}: scanned ${payload.tag}"
            return SubmitResult.Rejected(RejectCode.TAG_MISMATCH, "The scanned tag isn't this trip's ${plan.asset.shortName.lowercase()}. Ops has been alerted.")
        }
        val distance = Geo.distanceRoundedM(GeoPoint(payload.lat, payload.lon), expected.position)
        val approvedOverride = payload.override?.let { id ->
            trip.overrides.firstOrNull { it.info.id == id && it.info.waypointIndex == expected.index && it.info.status == OverrideStatus.APPROVED }
        }
        if (distance > expected.geofenceRadiusM && approvedOverride == null) {
            return SubmitResult.Rejected(
                RejectCode.OUTSIDE_GEOFENCE,
                "The location is $distance m from ${expected.name}, outside the ${expected.geofenceRadiusM} m geofence.",
            )
        }

        val payment = tx.operations.getOrNull(1) as? PaymentOperation
        val paysAtCheckIn = expected.type == WaypointType.CHECKPOINT && expected.legPay.isPositive
        if (tx.operations.size == 2 && payment == null || !paysAtCheckIn && payment != null) {
            return SubmitResult.Rejected(RejectCode.MALFORMED, "No payment is due at ${expected.name}.")
        }
        if (payment != null) {
            val matches = payment.sourceAccount == plan.tripAccount && payment.destination == account.id &&
                payment.asset == config.usdc && Usdc.of(payment.amount) == expected.legPay
            if (!matches) return SubmitResult.Rejected(RejectCode.MALFORMED, "The leg payment doesn't match the route plan.")
            if (demo.underfundNextLeg || trip.escrow < expected.legPay) {
                // On-chain the whole transaction would fail, so the location isn't recorded either (§8).
                demo = demo.copy(underfundNextLeg = false)
                return SubmitResult.Rejected(RejectCode.ESCROW_UNDERFUNDED, "The trip escrow can't pay this leg right now.")
            }
        }

        val txHash = coSignAndBump(tx, coSign = true)
        val ledgerTime = now + timings.ledgerCloseMillis
        account.sequence = tx.sequenceNumber
        accounts.getOrPut(plan.asset.account) { SimAccount(plan.asset.account, 0, Usdc.ZERO) }
            .data[DriverTransactions.WAYPOINT_DATA_KEY] = Hashes.hex(hash)
        payloads[Hashes.hex(hash)] = payload.canonical()
        ledger += SimLedgerEntry(txHash, ledgerTime, "${payload.event.wire} · ${plan.id} · ${expected.name}")
        trip.nextWaypoint++
        approvedOverride?.let { it.info = it.info.copy(status = OverrideStatus.USED) }
        if (payment != null) {
            trip.escrow -= expected.legPay
            account.usdc += expected.legPay
            addActivity(driver.id, legActivity(plan, expected.index, expected.legPay, ledgerTime, txHash))
        } else if (paysAtCheckIn) {
            trip.pendingLegs += expected.index
        }
        if (expected.type == WaypointType.DELIVERY) trip.arrivedAt = ledgerTime
        return SubmitResult.Recorded(txHash, ledgerTime, account.usdc)
    }

    private fun legActivity(plan: TripPlan, index: Int, amount: Usdc, time: Long, txHash: String, note: String? = null) =
        ActivityItem(
            id = "act-$txHash",
            kind = ActivityKind.LEG_PAY,
            title = "Leg $index · ${plan.waypoints[index].name}",
            amount = amount,
            incoming = true,
            time = time,
            tripId = plan.id,
            txHash = txHash,
            note = note,
        )

    private fun SimState.applyClaim(driver: SimDriver, account: SimAccount, tx: Transaction, now: Long): SubmitResult {
        val operation = tx.operations.singleOrNull() as? ClaimClaimableBalanceOperation
            ?: return SubmitResult.Rejected(RejectCode.MALFORMED, "That isn't an advance claim.")
        val claimable = claimables[operation.balanceId]
            ?: claimables.values.firstOrNull { it.id.equals(operation.balanceId, ignoreCase = true) }
            ?: return SubmitResult.Rejected(RejectCode.CLAIM_UNAVAILABLE, "This advance can't be claimed any more.")
        if (claimable.claimant != account.id || now >= claimable.deadline) {
            return SubmitResult.Rejected(RejectCode.CLAIM_UNAVAILABLE, "The claim deadline for this advance has passed.")
        }
        val txHash = coSignAndBump(tx, coSign = false)
        val ledgerTime = now + timings.ledgerCloseMillis
        account.sequence = tx.sequenceNumber
        account.usdc += claimable.amount
        claimables.remove(claimable.id)
        ledger += SimLedgerEntry(txHash, ledgerTime, "T7 · claim advance ${claimable.amount.display()} USDC")
        trips[claimable.tripId]?.advances?.find { it.info.id == claimable.advanceId }?.let {
            it.info = it.info.copy(status = AdvanceStatus.CLAIMED, claimTx = txHash, claimedAt = ledgerTime)
        }
        addActivity(
            driver.id,
            ActivityItem(
                id = "act-$txHash",
                kind = ActivityKind.ADVANCE,
                title = "Fuel advance · ${claimable.tripId}",
                amount = claimable.amount,
                incoming = true,
                time = ledgerTime,
                tripId = claimable.tripId,
                txHash = txHash,
                note = "Comes off your final leg pay",
            ),
        )
        return SubmitResult.Recorded(txHash, ledgerTime, account.usdc)
    }

    private fun SimState.applyCashOut(
        driver: SimDriver,
        account: SimAccount,
        tx: Transaction,
        submission: Submission,
        now: Long,
    ): SubmitResult {
        val cashOut = submission.refId?.let { cashOuts[it] }?.takeIf { it.driverId == driver.id }
            ?: return SubmitResult.Rejected(RejectCode.CASH_OUT_UNKNOWN, "The anchor doesn't know this cash out.")
        if (cashOut.info.status != CashOutStatus.AWAITING_PAYMENT) {
            return SubmitResult.Rejected(RejectCode.CASH_OUT_UNKNOWN, "This cash out was already paid.")
        }
        val payment = tx.operations.singleOrNull() as? PaymentOperation
        val memo = (tx.memo as? MemoHash)?.hexValue
        val matches = payment != null && payment.destination == cashOut.anchorAccount && payment.asset == config.usdc &&
            Usdc.of(payment.amount) == cashOut.info.sell && memo.equals(cashOut.memoHash, ignoreCase = true)
        if (!matches) return SubmitResult.Rejected(RejectCode.MALFORMED, "The payment doesn't match the anchor's instructions.")
        if (account.usdc < cashOut.info.sell) {
            return SubmitResult.Rejected(RejectCode.INSUFFICIENT_BALANCE, "Your balance is lower than the cash-out amount.")
        }
        val txHash = coSignAndBump(tx, coSign = false)
        val ledgerTime = now + timings.ledgerCloseMillis
        account.sequence = tx.sequenceNumber
        account.usdc -= cashOut.info.sell
        ledger += SimLedgerEntry(txHash, ledgerTime, "T9 · cash out ${cashOut.info.sell.display()} USDC to anchor")
        cashOut.info = cashOut.info.copy(status = CashOutStatus.PENDING, updatedAt = now, txHash = txHash)
        cashOut.completeAt = now + timings.cashOutSettleMillis
        cashOut.willFail = demo.failNextCashOut
        demo = demo.copy(failNextCashOut = false)
        addActivity(
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
        return SubmitResult.Recorded(txHash, ledgerTime, account.usdc)
    }

    /** The platform signer co-signs (it is a signer on A and E); the fee account pays through a fee bump. */
    private fun SimState.coSignAndBump(tx: Transaction, coSign: Boolean): String {
        if (coSign) tx.sign(KeyPair.fromSecretSeed(seeds.platformSigner))
        val fee = KeyPair.fromSecretSeed(seeds.feeAccount)
        val bump = FeeBumpTransaction.createWithBaseFee(fee.accountId, config.baseFee * 2, tx)
        bump.sign(fee)
        ledgerSeq++
        return bump.hashHex()
    }

    // endregion

    // region Exceptions, advances, delivery

    override suspend fun requestOverride(driverId: String, request: OverrideRequest): OverrideInfo = online { state, now ->
        val trip = state.activeTrip(driverId, request.tripId)
        val waypoint = trip.plan.waypoints.getOrNull(request.waypointIndex)
            ?: throw PlatformException("waypoint_unknown", "That stop isn't on this trip.")
        if (waypoint.index != trip.nextWaypoint) throw PlatformException("not_next", "You can only ask about your next stop.")
        trip.overrides.firstOrNull {
            it.info.waypointIndex == waypoint.index && it.info.status in setOf(OverrideStatus.PENDING, OverrideStatus.APPROVED)
        }?.let { return@online it.info }
        val distance = Geo.distanceRoundedM(request.fix.point, waypoint.position)
        val info = OverrideInfo(
            id = "OVR-${shortId()}",
            waypointIndex = waypoint.index,
            status = OverrideStatus.PENDING,
            requestedAt = now,
            distanceM = distance,
        )
        trip.overrides += SimOverride(info, decideAt = now + timings.overrideDecisionMillis)
        state.alerts += "Override requested on ${trip.plan.id} at ${waypoint.name}: $distance m from centre (${request.reason})"
        info
    }

    override suspend fun reportTagMismatch(driverId: String, tripId: String, waypointIndex: Int, scannedTag: String) =
        online { state, _ ->
            val trip = state.activeTrip(driverId, tripId)
            state.alerts += "Tag mismatch on ${trip.plan.id} at stop $waypointIndex: scanned $scannedTag"
            Unit
        }

    override suspend fun requestAdvance(driverId: String, tripId: String, amount: Usdc): AdvanceInfo = online { state, now ->
        val trip = state.activeTrip(driverId, tripId)
        if (!amount.isPositive) throw PlatformException("amount_invalid", "Enter an amount.")
        val available = AdvanceRules.available(trip.plan, trip.advances.map { it.info })
        if (amount > available) {
            throw PlatformException("over_limit", "You can ask for up to ${available.display()} USDC on this trip.")
        }
        val info = AdvanceInfo(id = "ADV-${shortId()}", amount = amount, status = AdvanceStatus.REQUESTED, requestedAt = now)
        trip.advances += SimAdvance(info, decideAt = now + timings.advanceDecisionMillis)
        info
    }

    override suspend fun claimFinalPay(driverId: String, tripId: String): ContractInfo = online { state, now ->
        val trip = state.activeTrip(driverId, tripId)
        if (trip.contract.status != ContractStatus.LOCKED || trip.arrivedAt == null) {
            throw PlatformException("not_claimable", "The final leg can't be claimed now.")
        }
        if (now <= trip.contract.deadline) throw PlatformException("too_early", "The receiver still has time to sign.")
        state.payFinalLeg(trip, ContractStatus.RELEASED_AFTER_DEADLINE, now, "release_after_deadline")
        trip.contract
    }

    /** The contract pays the final leg, less any advances (BR-06), then the trip closes (T10, BR-10). */
    private fun SimState.payFinalLeg(trip: SimTrip, status: ContractStatus, now: Long, call: String) {
        val driver = drivers.getValue(trip.driverId)
        val account = walletOf(driver)
        val locked = trip.contract.locked
        val deduction = minOf(AdvanceRules.deduction(trip.advances.map { it.info }), locked)
        val payout = locked - deduction
        val txHash = platformTx(now, "Escrow contract · $call · ${trip.plan.id}")
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
        payPendingLegs(trip, now)
        platformTx(now, "T10 · close ${trip.plan.id}, ${trip.escrow.display()} USDC back to treasury")
        trip.escrow = Usdc.ZERO
        trip.state = SimTripState.CLOSED
        if (trips.values.none { it.driverId == trip.driverId && it.state in OPEN_STATES }) {
            offerNextTrip(trip.driverId, now)
        }
    }

    private fun SimState.payPendingLegs(trip: SimTrip, now: Long) {
        val account = walletOf(drivers.getValue(trip.driverId))
        trip.pendingLegs.toList().forEach { index ->
            val amount = trip.plan.waypoints[index].legPay
            val txHash = platformTx(now, "Ops pays pending leg $index of ${trip.plan.id}")
            trip.escrow = (trip.escrow - amount).coerceAtLeastZero()
            account.usdc += amount
            trip.latePayments += LatePayment(index, amount, txHash, now)
            addActivity(trip.driverId, legActivity(trip.plan, index, amount, now, txHash, note = "Paid after the check-in"))
        }
        trip.pendingLegs.clear()
    }

    // endregion

    // region Anchor: SEP-38 quote, SEP-6 withdrawal

    override suspend fun quote(driverId: String, sell: Usdc): FirmQuote = online { state, now ->
        val account = state.walletOf(state.driver(driverId))
        if (!sell.isPositive) throw PlatformException("amount_invalid", "Enter an amount.")
        if (sell > account.usdc) throw PlatformException("insufficient", "You have ${account.usdc.display()} USDC.")
        val onePercent = sell.toBigDecimal().multiply(BigDecimal("0.01")).setScale(2, RoundingMode.UP)
        val fee = minOf(Usdc.of(onePercent.max(MIN_FEE)), sell)
        val buy = (sell - fee).toBigDecimal().multiply(BigDecimal(RATE_GHS)).setScale(2, RoundingMode.DOWN)
        val quote = FirmQuote(
            id = "Q-${shortId()}",
            sell = sell,
            fee = fee,
            rate = RATE_GHS,
            buyAmount = buy.toPlainString(),
            buyCurrency = "GHS",
            expiresAt = now + timings.quoteValidityMillis,
            anchorName = ANCHOR_NAME,
            eta = "Within 5 minutes",
        )
        state.quotes[quote.id] = quote
        quote
    }

    override suspend fun startCashOut(driverId: String, quoteId: String, momoNumber: String): CashOutInstructions =
        online { state, now ->
            val driver = state.driver(driverId)
            val account = state.walletOf(driver)
            val quote = state.quotes[quoteId] ?: throw PlatformException("quote_unknown", "Get a new quote.")
            if (now > quote.expiresAt) throw PlatformException("quote_expired", "The quote expired. Check the new one.")
            if (account.usdc < quote.sell) throw PlatformException("insufficient", "You have ${account.usdc.display()} USDC.")
            val anchorAccount = KeyPair.fromSecretSeed(state.seeds.anchor).accountId
            val memo = Hashes.hex(Hashes.randomBytes(32))
            val id = "CO-${shortId()}"
            state.cashOuts[id] = SimCashOut(
                info = CashOutInfo(
                    id = id,
                    sell = quote.sell,
                    fee = quote.fee,
                    buyAmount = quote.buyAmount,
                    buyCurrency = quote.buyCurrency,
                    momoNumber = normalisePhone(momoNumber),
                    status = CashOutStatus.AWAITING_PAYMENT,
                    createdAt = now,
                    updatedAt = now,
                    anchorName = quote.anchorName,
                    eta = quote.eta,
                ),
                driverId = driver.id,
                anchorAccount = anchorAccount,
                memoHash = memo,
            )
            state.quotes.remove(quoteId)
            CashOutInstructions(id, anchorAccount, memo, quote.sell, quote)
        }

    // endregion

    // region Demo controls

    override suspend fun receiverSigns(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.LOCKED) throw PlatformException("settled", "This delivery is already settled.")
        state.payFinalLeg(trip, ContractStatus.DELIVERED, now, "confirm_delivery (receiver passkey)")
    }

    override suspend fun receiverReportsProblem(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.LOCKED) throw PlatformException("settled", "This delivery is already settled.")
        val txHash = state.platformTx(now, "Escrow contract · dispute (evidence hash anchored) · ${trip.plan.id}")
        trip.contract = trip.contract.copy(status = ContractStatus.DISPUTED, txHash = txHash, updatedAt = now)
    }

    override suspend fun opsResolvesDisputeForDriver(tripId: String) = locked { state, now ->
        val trip = state.settleableTrip(tripId)
        if (trip.contract.status != ContractStatus.DISPUTED) throw PlatformException("not_disputed", "There's no open dispute.")
        state.payFinalLeg(trip, ContractStatus.RESOLVED, now, "resolve (arbiter rules for the driver)")
    }

    override suspend fun expireDeliveryDeadline(tripId: String) = locked { state, now ->
        val trip = state.trips[tripId] ?: throw PlatformException("trip_unknown", "No such trip.")
        trip.contract = trip.contract.copy(deadline = now - 1_000, updatedAt = now)
    }

    override suspend fun opsPaysPendingLegs(tripId: String) = locked { state, now ->
        val trip = state.trips[tripId] ?: throw PlatformException("trip_unknown", "No such trip.")
        state.payPendingLegs(trip, now)
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
            ?: throw PlatformException("trip_inactive", "This trip isn't active.")
        if (trip.arrivedAt == null) {
            throw PlatformException("not_arrived", "The driver hasn't checked in at ${trip.plan.delivery.name} yet.")
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

        fun maskPhone(phone: String): String = "••" + phone.filter(Char::isDigit).takeLast(4)
    }
}
