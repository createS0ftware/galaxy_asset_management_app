package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.ledger.AuthEntries
import com.galaxy.assettrail.core.ledger.DriverTransactions
import com.galaxy.assettrail.core.ledger.LedgerClock
import com.galaxy.assettrail.core.ledger.NetworkInfo
import com.galaxy.assettrail.core.ledger.TrailCalls
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.payload.FinalPayClaim
import com.galaxy.assettrail.core.payload.Hashes
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.payload.WaypointPayload
import com.galaxy.assettrail.core.platform.CheckInResult
import com.galaxy.assettrail.core.platform.DriverSnapshot
import com.galaxy.assettrail.core.platform.KycSubmission
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.PlatformApi
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.PreparedTransaction
import com.galaxy.assettrail.core.platform.SignedCheckIn
import com.galaxy.assettrail.core.platform.SignedClaim
import com.galaxy.assettrail.core.platform.WalletRequest
import com.galaxy.assettrail.core.store.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.sdk.Transaction
import java.time.Clock
import java.util.UUID

data class CheckInRequest(
    val waypointIndex: Int,
    val scannedTag: String,
    val fix: GpsFix,
    val photoHashes: List<String> = emptyList(),
)

sealed interface CheckInOutcome {
    /** On the ledger, with its leg pay when one is due. */
    data class Recorded(val checkIn: CheckIn) : CheckInOutcome

    /** Signed and saved on the phone; records automatically ([notice] says why it is waiting). */
    data class Saved(val checkIn: CheckIn, val notice: String?) : CheckInOutcome

    /** The signer service refused it. */
    data class Failed(val checkIn: CheckIn) : CheckInOutcome

    /** Stopped on the phone before anything was signed. */
    data class Blocked(val reason: BlockReason, val message: String) : CheckInOutcome
}

enum class BlockReason { NO_TRIP, NOT_NEXT, TAG_MISMATCH, MOCK_LOCATION, NOT_SET_UP }

/**
 * The driver app's single source of truth. Talks to the platform only through [PlatformApi],
 * signs with [DriverKeyStore], and keeps check-ins signed on the phone in one ordered queue that
 * survives restarts and lost signal (BR-09).
 *
 * Check-ins and claims are Soroban authorization entries: the fee account is the transaction
 * source, so nothing here tracks a sequence number. The two classic transactions the driver signs
 * (wallet set-up and cash-out) are built by the platform and checked here before signing.
 */
class DriverRepository(
    private val platform: PlatformApi,
    private val store: StateStore<DriverState>,
    private val keys: DriverKeyStore,
    private val online: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 4_000,
) {
    private val stateLock = Mutex()
    private val flushLock = Mutex()

    /** The contract can be redeployed between app starts, so the network info is re-read once per process. */
    private var networkChecked = false

    private val _state = MutableStateFlow(DriverState())
    val state: StateFlow<DriverState> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val isOnline: StateFlow<Boolean> get() = online

    fun now(): Long = clock.millis()

    suspend fun load() {
        _state.value = store.read() ?: DriverState()
        _loaded.value = true
    }

    /** Submits the queue whenever signal comes back, and polls the platform while online. */
    fun startSync(): Job = scope.launch {
        launch {
            online.filter { it }.collect {
                flush()
                refresh()
            }
        }
        while (isActive) {
            delay(pollIntervalMillis)
            if (online.value) {
                flush()
                refresh()
            }
        }
    }

    private suspend fun update(transform: (DriverState) -> DriverState): DriverState = stateLock.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            store.write(next)
        }
        next
    }

    private fun requireProfile(): DriverProfile =
        state.value.profile ?: throw IllegalStateException("Finish setting up your wallet first.")

    private fun requireTrip(): ActiveTrip =
        state.value.trip ?: throw IllegalStateException("You don't have an active trip.")

    private fun requireOnline() {
        if (!online.value) throw OfflineException()
    }

    /** The network info, from the platform when the phone doesn't have it yet. */
    private suspend fun network(): NetworkInfo {
        state.value.network?.let { return it }
        val info = platform.network()
        update { it.copy(network = info) }
        networkChecked = true
        return info
    }

    /** For a call made online: valid for [ONLINE_AUTH_LEDGERS] ledgers from the network's latest. */
    private suspend fun onlineValidUntil(network: NetworkInfo): Long {
        val ledger = state.value.ledger ?: run {
            refresh()
            state.value.ledger
        } ?: throw IllegalStateException("Still syncing with the network. Try again in a moment.")
        return ledger.estimate(now(), network.ledgerSeconds) + ONLINE_AUTH_LEDGERS
    }

    // region Onboarding: phone, ID check with the anchor, MoMo + wallet (T2)

    suspend fun sendCode(phone: String) {
        val sent = platform.sendPhoneCode(phone)
        update { it.copy(onboarding = it.onboarding.copy(phone = sent.phone, demoCode = sent.demoCode)) }
    }

    suspend fun verifyCode(code: String) {
        val phone = state.value.onboarding.phone ?: throw IllegalStateException("Ask for a code first.")
        val driverId = platform.verifyPhone(phone, code)
        update { it.copy(onboarding = it.onboarding.copy(driverId = driverId, step = OnboardingStep.IDENTITY)) }
    }

    suspend fun changePhone() {
        update { it.copy(onboarding = Onboarding()) }
    }

    suspend fun submitIdentity(fullName: String, idNumber: String) {
        val driverId = state.value.onboarding.driverId ?: throw IllegalStateException("Verify your phone first.")
        val result = platform.submitKyc(driverId, KycSubmission(fullName.trim(), "Ghana Card", idNumber.trim()))
        if (!result.approved) throw PlatformException("kyc_declined", result.message ?: "Your ID couldn't be checked.")
        update {
            it.copy(
                onboarding = it.onboarding.copy(
                    fullName = fullName.trim(),
                    kycProvider = result.provider,
                    step = OnboardingStep.PAYOUT,
                ),
            )
        }
    }

    /**
     * T2: the key is made here, on the phone, and only its public address goes to the platform. The
     * platform prepares the sponsored account + trustline; the phone checks it and signs.
     */
    suspend fun finishOnboarding(momoNumber: String, recovery: Boolean) {
        val onboarding = state.value.onboarding
        val driverId = onboarding.driverId ?: throw IllegalStateException("Verify your phone first.")
        val fullName = onboarding.fullName ?: throw IllegalStateException("Check your ID first.")
        val network = network()
        val accountId = keys.accountId() ?: keys.create()
        val prepared = platform.prepareWallet(
            driverId,
            WalletRequest(accountId, fullName, onboarding.phone.orEmpty(), momoNumber.trim(), recovery),
        )
        val transaction = checkedTransaction(prepared, network) { tx ->
            DriverTransactions.walletProblem(tx, accountId, network.stellarAsset)
        }
        val wallet = platform.submitWallet(driverId, DriverTransactions.sign(transaction, keys.signer()))
        update {
            it.copy(
                profile = DriverProfile(
                    driverId = driverId,
                    fullName = fullName,
                    phone = onboarding.phone.orEmpty(),
                    momoNumber = momoNumber.filter { c -> c.isDigit() || c == '+' },
                    accountId = wallet.accountId,
                    recoveryEnabled = recovery,
                    kycProvider = onboarding.kycProvider.orEmpty(),
                    walletTx = wallet.txHash,
                ),
                onboarding = Onboarding(),
            )
        }
        refresh()
    }

    suspend fun updateMomoNumber(momoNumber: String) {
        update { s -> s.copy(profile = s.profile?.copy(momoNumber = momoNumber.filter { it.isDigit() || it == '+' })) }
    }

    /**
     * A signature can't be taken back: refuse anything that isn't for this network or that does
     * more than the platform said it would.
     */
    private fun checkedTransaction(prepared: PreparedTransaction, network: NetworkInfo, problem: (Transaction) -> String?): Transaction {
        if (prepared.networkPassphrase != network.networkPassphrase) {
            throw PlatformException(TX_REFUSED, "The platform prepared a transaction for another network, so it wasn't signed.")
        }
        val transaction = runCatching { DriverTransactions.parse(prepared.xdr, network.network) }
            .getOrElse { throw PlatformException(TX_REFUSED, "The platform's transaction couldn't be read, so it wasn't signed.") }
        problem(transaction)?.let { throw PlatformException(TX_REFUSED, "Not signed: $it.") }
        return transaction
    }

    // endregion

    // region Sync

    /** Pulls balance, offers, contract and advance status, and the latest ledger. Returns false when offline. */
    suspend fun refresh(): Boolean {
        val profile = state.value.profile ?: return false
        if (!online.value) return false
        val snapshot = try {
            if (!networkChecked) {
                val info = platform.network()
                update { it.copy(network = info) }
                networkChecked = true
            }
            platform.snapshot(profile.driverId, state.value.trip?.plan?.id)
        } catch (_: OfflineException) {
            return false
        } catch (_: PlatformException) {
            return false
        }
        val seenAt = now()
        update { it.merge(snapshot, seenAt) }
        return true
    }

    private fun DriverState.merge(snapshot: DriverSnapshot, seenAt: Long): DriverState {
        val mergedTrip = trip?.let { current ->
            val status = snapshot.trip?.takeIf { it.tripId == current.plan.id } ?: return@let current
            current.copy(contract = status.contract, advances = status.advances, closed = status.closed)
        }
        return copy(
            offers = snapshot.offers,
            trip = mergedTrip,
            balance = snapshot.balance,
            balanceAsOf = snapshot.serverTime,
            activity = snapshot.activity,
            cashOuts = snapshot.cashOuts,
            ledger = LedgerClock(snapshot.latestLedger, seenAt),
            lastSyncAt = snapshot.serverTime,
        )
    }

    // endregion

    // region Trips

    suspend fun acceptOffer(tripId: String) {
        val profile = requireProfile()
        val plan = platform.acceptOffer(profile.driverId, tripId)
        val now = now()
        update {
            it.copy(
                trip = ActiveTrip(
                    plan = plan,
                    acceptedAt = now,
                    contract = ContractInfo(ContractStatus.LOCKED, plan.delivery.legPay, plan.deliveryDeadline, updatedAt = now),
                ),
                offers = it.offers.filterNot { offer -> offer.id == tripId },
            )
        }
        refresh()
    }

    /** After the completion screen: forget the closed trip. */
    suspend fun finishTrip() {
        update { if (it.trip?.closed == true) it.copy(trip = null) else it }
    }

    /**
     * Builds the salted payload, hashes it, signs the `check_in` authorization on the phone and
     * queues it. Online, it is submitted straight away; offline, it records on reconnect, in order.
     * Outside the geofence the check-in still goes ahead; the trip screen warns first.
     */
    suspend fun checkIn(request: CheckInRequest): CheckInOutcome {
        val trip = state.value.trip ?: return CheckInOutcome.Blocked(BlockReason.NO_TRIP, "You don't have an active trip.")
        val plan = trip.plan
        val waypoint = trip.nextWaypoint()
            ?: return CheckInOutcome.Blocked(BlockReason.NOT_NEXT, "Every stop on this trip is checked in.")
        if (request.waypointIndex != waypoint.index) {
            return CheckInOutcome.Blocked(BlockReason.NOT_NEXT, "Check in at ${waypoint.name} first.")
        }
        if (request.fix.isMock) {
            return CheckInOutcome.Blocked(BlockReason.MOCK_LOCATION, "Turn off mock locations in your phone's settings to check in.")
        }
        val tag = request.scannedTag.trim().uppercase()
        if (tag != plan.asset.tagId.uppercase()) {
            if (online.value) {
                runCatching { platform.reportTagMismatch(requireProfile().driverId, plan.id, waypoint.index, tag) }
            }
            return CheckInOutcome.Blocked(
                BlockReason.TAG_MISMATCH,
                "This tag ($tag) isn't the ${plan.asset.shortName.lowercase()} on this trip. Check-in is blocked and ops has been alerted.",
            )
        }
        val network = state.value.network
        val ledger = state.value.ledger
        if (network == null || ledger == null) {
            return CheckInOutcome.Blocked(BlockReason.NOT_SET_UP, "Connect to the internet once so this phone can sign check-ins.")
        }

        val now = now()
        val payload = WaypointPayload(
            trip = plan.id,
            waypoint = waypoint.index,
            event = WaypointEvent.at(waypoint.index, plan.delivery.index),
            asset = plan.asset.id,
            tag = tag,
            lat = WaypointPayload.roundCoordinate(request.fix.point.lat),
            lon = WaypointPayload.roundCoordinate(request.fix.point.lon),
            accuracyM = request.fix.accuracyM,
            deviceTime = WaypointPayload.truncateTime(now),
            photos = request.photoHashes,
            salt = WaypointPayload.newSalt(),
        )
        val canonical = payload.canonical()
        val hash = Hashes.sha256(canonical)
        val validUntil = ledger.offlineValidUntil(now, network)
        val authEntry = AuthEntries.authorize(
            keys.signer(),
            TrailCalls.checkIn(network.contractId, plan.id, waypoint.index, hash),
            validUntil,
            network.network,
        )
        val legPay = waypoint.legPay.takeIf { waypoint.isPaidAtCheckIn }
        val checkIn = CheckIn(
            waypointIndex = waypoint.index,
            event = payload.event,
            payloadJson = canonical,
            payloadHash = Hashes.hex(hash),
            deviceTime = now,
            tag = tag,
            distanceFromCentreM = Geo.distanceRoundedM(request.fix.point, waypoint.position),
            savedOffline = !online.value,
            status = CheckInStatus.QUEUED,
            pay = when {
                waypoint.type == WaypointType.DELIVERY -> LegPayStatus.IN_CONTRACT
                legPay != null -> LegPayStatus.AWAITING_RECORD
                else -> LegPayStatus.NONE
            },
            legPay = waypoint.legPay,
        )
        val queued = QueuedCheckIn(
            id = UUID.randomUUID().toString(),
            tripId = plan.id,
            waypointIndex = waypoint.index,
            payloadJson = canonical,
            payloadHash = checkIn.payloadHash,
            authEntry = authEntry,
            validUntilLedger = validUntil,
            createdAt = now,
        )
        update { s -> s.copy(queue = s.queue + queued, trip = s.trip?.copy(checkIns = s.trip.checkIns + checkIn)) }

        flush()

        val result = state.value.trip?.checkIns?.lastOrNull { it.waypointIndex == waypoint.index } ?: checkIn
        return when (result.status) {
            CheckInStatus.RECORDED -> CheckInOutcome.Recorded(result)
            CheckInStatus.FAILED -> CheckInOutcome.Failed(result)
            CheckInStatus.QUEUED -> CheckInOutcome.Saved(result, state.value.queueNotice)
        }
    }

    /**
     * After the deadline, with no signature or dispute, the driver releases the final leg with
     * `claim_final_pay`. Only the driver's authorization is needed.
     */
    suspend fun claimFinalPay() {
        requireOnline()
        val trip = requireTrip()
        val network = network()
        val claim = FinalPayClaim(
            trip = trip.plan.id,
            asset = trip.plan.asset.id,
            deviceTime = WaypointPayload.truncateTime(now()),
            salt = WaypointPayload.newSalt(),
        )
        val authEntry = AuthEntries.authorize(
            keys.signer(),
            TrailCalls.claimFinalPay(network.contractId, trip.plan.id, claim.hash()),
            onlineValidUntil(network),
            network.network,
        )
        val contract = platform.claimFinalPay(requireProfile().driverId, trip.plan.id, SignedClaim(claim.canonical(), authEntry))
        update { s -> s.copy(trip = s.trip?.copy(contract = contract)) }
        refresh()
    }

    // endregion

    // region Money: advances (T6/T7) and cash-out (SEP-38 quote, SEP-6 withdrawal, T9)

    suspend fun requestAdvance(amount: Usdc): AdvanceInfo {
        requireOnline()
        val trip = requireTrip()
        val available = AdvanceRules.available(trip.plan, trip.advances)
        if (amount > available) throw IllegalArgumentException("You can ask for up to ${available.display()} USDC on this trip.")
        val info = platform.requestAdvance(requireProfile().driverId, trip.plan.id, amount)
        update { s -> s.copy(trip = s.trip?.copy(advances = s.trip.advances + info)) }
        return info
    }

    /** T7: authorizes `claim_advance(trip)`; the contract pays the advance from escrow. */
    suspend fun claimAdvance(advanceId: String) {
        requireOnline()
        val trip = requireTrip()
        val advance = trip.advances.firstOrNull { it.id == advanceId && it.status == AdvanceStatus.READY }
            ?: throw IllegalStateException("This advance can't be claimed.")
        val network = network()
        val authEntry = AuthEntries.authorize(
            keys.signer(),
            TrailCalls.claimAdvance(network.contractId, trip.plan.id),
            onlineValidUntil(network),
            network.network,
        )
        val claimed = platform.claimAdvance(requireProfile().driverId, trip.plan.id, advance.id, authEntry)
        update { s -> s.copy(trip = s.trip?.copy(advances = s.trip.advances.map { if (it.id == claimed.id) claimed else it })) }
        refresh()
    }

    suspend fun quote(amount: Usdc): FirmQuote {
        requireOnline()
        return platform.quote(requireProfile().driverId, amount)
    }

    /** Confirms a firm quote: the platform prepares the payment to the anchor, the phone checks and signs it (T9). */
    suspend fun cashOut(quote: FirmQuote): CashOutInfo {
        requireOnline()
        check(state.value.canMoveMoney()) { "Your saved check-ins record first." }
        val profile = requireProfile()
        val network = network()
        val prepared = platform.prepareCashOut(profile.driverId, quote.id)
        val transaction = checkedTransaction(prepared, network) { tx ->
            DriverTransactions.cashOutProblem(tx, profile.accountId, network.stellarAsset, quote.sell, quote.payTo, quote.memo)
        }
        val cashOut = platform.submitCashOut(profile.driverId, quote.id, DriverTransactions.sign(transaction, keys.signer()))
        update { s -> s.copy(cashOuts = listOf(cashOut) + s.cashOuts.filterNot { it.id == cashOut.id }) }
        refresh()
        return state.value.cashOuts.firstOrNull { it.id == cashOut.id } ?: cashOut
    }

    // endregion

    // region The queue

    /**
     * Submits saved check-ins strictly in route order (§4.4), one batch per trip. The platform stops
     * at the first one that doesn't record: a refused one leaves the queue (the next is judged on
     * its own), a "not now" stops here and keeps everything queued.
     */
    suspend fun flush() {
        flushLock.withLock {
            val profile = state.value.profile ?: return
            var recordedAny = false
            while (online.value) {
                val head = state.value.queue.firstOrNull() ?: break
                val batch = renewExpired(state.value.queue.filter { it.tripId == head.tripId }.take(MAX_BATCH))
                val results = try {
                    platform.submitCheckIns(profile.driverId, head.tripId, batch.map { SignedCheckIn(it.payloadJson, it.authEntry) })
                } catch (_: OfflineException) {
                    break
                } catch (e: PlatformException) {
                    update { it.copy(queueNotice = e.message) }
                    break
                }
                var waiting = results.isEmpty()
                results.zip(batch).forEach { (result, item) ->
                    when (result) {
                        is CheckInResult.Recorded -> {
                            update { it.recorded(item, result) }
                            recordedAny = true
                        }
                        is CheckInResult.Rejected -> update { it.rejected(item, result) }
                        is CheckInResult.Retry -> {
                            update { it.copy(queueNotice = result.message) }
                            waiting = true
                        }
                    }
                }
                if (waiting) break
            }
            if (recordedAny) refresh()
        }
    }

    /**
     * An authorization that ran out of ledgers while the phone was offline is signed again for the
     * same payload. The payload keeps its device time, so the platform still judges how late it is.
     */
    private suspend fun renewExpired(batch: List<QueuedCheckIn>): List<QueuedCheckIn> {
        val network = state.value.network ?: return batch
        val ledger = state.value.ledger ?: return batch
        val current = ledger.estimate(now(), network.ledgerSeconds)
        if (batch.none { it.validUntilLedger <= current + RENEW_MARGIN_LEDGERS }) return batch
        val signer = keys.signer()
        val renewed = batch.map { item ->
            if (item.validUntilLedger > current + RENEW_MARGIN_LEDGERS) return@map item
            val validUntil = current + ONLINE_AUTH_LEDGERS
            val invocation = TrailCalls.checkIn(network.contractId, item.tripId, item.waypointIndex, Hashes.fromHex(item.payloadHash))
            item.copy(authEntry = AuthEntries.authorize(signer, invocation, validUntil, network.network), validUntilLedger = validUntil)
        }
        val byId = renewed.associateBy { it.id }
        update { s -> s.copy(queue = s.queue.map { byId[it.id] ?: it }) }
        return renewed
    }

    private fun DriverState.recorded(item: QueuedCheckIn, result: CheckInResult.Recorded): DriverState =
        copy(
            queue = queue.filterNot { it.id == item.id },
            queueNotice = null,
            balance = result.balance,
            balanceAsOf = result.ledgerTime,
        ).updateCheckIn(item) {
            it.copy(
                status = CheckInStatus.RECORDED,
                txHash = result.txHash,
                ledgerTime = result.ledgerTime,
                pay = if (it.pay == LegPayStatus.AWAITING_RECORD) LegPayStatus.PAID else it.pay,
            )
        }

    /** Refused for good. Nothing else in the queue depends on it: each authorization has its own nonce. */
    private fun DriverState.rejected(item: QueuedCheckIn, result: CheckInResult.Rejected): DriverState =
        copy(queue = queue.filterNot { it.id == item.id }).updateCheckIn(item) {
            it.copy(status = CheckInStatus.FAILED, failure = result.code, failureMessage = result.message)
        }

    private fun DriverState.updateCheckIn(item: QueuedCheckIn, change: (CheckIn) -> CheckIn): DriverState {
        val trip = trip?.takeIf { it.plan.id == item.tripId } ?: return this
        val position = trip.checkIns.indexOfLast { it.payloadHash == item.payloadHash }
        if (position < 0) return this
        val updated = trip.checkIns.toMutableList().also { it[position] = change(it[position]) }
        return copy(trip = trip.copy(checkIns = updated))
    }

    // endregion

    /** Wipes the wallet key and everything on the phone. Demo builds use it to start over. */
    suspend fun resetEverything() {
        stateLock.withLock {
            keys.delete()
            store.clear()
            _state.value = DriverState()
            networkChecked = false
        }
    }

    companion object {
        /** About 8 minutes at 5 s a ledger, like the platform's own co-signatures. */
        private const val ONLINE_AUTH_LEDGERS = 100L

        /** Re-sign a queued authorization when it has fewer ledgers left than this. */
        private const val RENEW_MARGIN_LEDGERS = 12L

        /** The batch endpoint takes up to 64 check-ins. */
        private const val MAX_BATCH = 64

        private const val TX_REFUSED = "TX_REFUSED"

        fun formatDistance(metres: Int): String =
            if (metres < 1_000) "$metres m" else "%.1f km".format(metres / 1000.0).replace(".0 km", " km")
    }
}
