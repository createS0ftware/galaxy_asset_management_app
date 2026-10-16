package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.ledger.DriverTransactions
import com.galaxy.assettrail.core.ledger.LedgerConfig
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.CashOutInfo
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.core.model.ContractInfo
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.OverrideInfo
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.payload.Hashes
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.payload.WaypointPayload
import com.galaxy.assettrail.core.platform.DriverSnapshot
import com.galaxy.assettrail.core.platform.KycSubmission
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.OverrideRequest
import com.galaxy.assettrail.core.platform.PlatformApi
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.platform.Submission
import com.galaxy.assettrail.core.platform.SubmitResult
import com.galaxy.assettrail.core.platform.TxKind
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

enum class BlockReason { NO_TRIP, NOT_NEXT, TAG_MISMATCH, OUTSIDE_GEOFENCE, MOCK_LOCATION }

/**
 * The driver app's single source of truth. Talks to the platform only through [PlatformApi]
 * (built by the backend team), signs driver transactions with [DriverKeyStore], and keeps every
 * driver-sourced transaction in one ordered queue that survives restarts and lost signal (BR-09).
 */
class DriverRepository(
    private val platform: PlatformApi,
    private val store: StateStore<DriverState>,
    private val keys: DriverKeyStore,
    private val ledger: LedgerConfig,
    private val online: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 4_000,
) {
    private val transactions = DriverTransactions(ledger)
    private val stateLock = Mutex()
    private val flushLock = Mutex()

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

    suspend fun finishOnboarding(momoNumber: String, recovery: Boolean) {
        val onboarding = state.value.onboarding
        val driverId = onboarding.driverId ?: throw IllegalStateException("Verify your phone first.")
        val fullName = onboarding.fullName ?: throw IllegalStateException("Check your ID first.")
        // The key is made here, on the phone. Only its public address goes to the platform.
        val accountId = keys.accountId() ?: keys.create()
        val wallet = platform.createWallet(
            driverId,
            WalletRequest(accountId, fullName, onboarding.phone.orEmpty(), momoNumber.trim(), recovery),
        )
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
                sequence = wallet.sequence,
                onboarding = Onboarding(),
            )
        }
        refresh()
    }

    suspend fun updateMomoNumber(momoNumber: String) {
        update { s -> s.copy(profile = s.profile?.copy(momoNumber = momoNumber.filter { it.isDigit() || it == '+' })) }
    }

    // endregion

    // region Sync

    /** Pulls balance, offers, contract and advance status. Returns false when offline. */
    suspend fun refresh(): Boolean {
        val profile = state.value.profile ?: return false
        if (!online.value) return false
        val snapshot = try {
            platform.snapshot(profile.driverId, state.value.trip?.plan?.id)
        } catch (_: OfflineException) {
            return false
        } catch (_: PlatformException) {
            return false
        }
        update { it.merge(snapshot) }
        return true
    }

    private fun DriverState.merge(snapshot: DriverSnapshot): DriverState {
        val mergedTrip = trip?.let { current ->
            val status = snapshot.trip?.takeIf { it.tripId == current.plan.id } ?: return@let current
            val latePaid = status.latePayments.map { it.waypointIndex }.toSet()
            current.copy(
                contract = status.contract,
                advances = mergeAdvances(current.advances, status.advances),
                overrides = status.overrides,
                closed = status.closed,
                checkIns = current.checkIns.map { checkIn ->
                    if (checkIn.pay == LegPayStatus.PENDING_ESCROW && checkIn.waypointIndex in latePaid) {
                        checkIn.copy(pay = LegPayStatus.PAID)
                    } else {
                        checkIn
                    }
                },
            )
        }
        return copy(
            offers = snapshot.offers,
            trip = mergedTrip,
            balance = snapshot.balance,
            balanceAsOf = snapshot.serverTime,
            activity = snapshot.activity,
            cashOuts = mergeCashOuts(cashOuts, snapshot.cashOuts),
            // With nothing queued the ledger is the truth; otherwise keep the numbers already used.
            sequence = if (queue.isEmpty()) snapshot.sequence else maxOf(sequence, snapshot.sequence),
            lastSyncAt = snapshot.serverTime,
        )
    }

    /** A claim waiting in the queue stays "claimed" locally even if the platform hasn't seen it yet. */
    private fun DriverState.mergeAdvances(local: List<AdvanceInfo>, remote: List<AdvanceInfo>): List<AdvanceInfo> {
        val queuedClaims = queue.mapNotNull { (it.spec as? TxSpec.ClaimAdvanceTx)?.advanceId }.toSet()
        return remote.map { advance ->
            if (advance.id in queuedClaims && advance.status == AdvanceStatus.READY) {
                local.firstOrNull { it.id == advance.id } ?: advance
            } else {
                advance
            }
        }
    }

    private fun DriverState.mergeCashOuts(local: List<CashOutInfo>, remote: List<CashOutInfo>): List<CashOutInfo> {
        val remoteIds = remote.map { it.id }.toSet()
        // Keep local cash-outs the platform doesn't list yet (still in the queue).
        return (remote + local.filter { it.id !in remoteIds }).sortedByDescending { it.createdAt }
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

    suspend fun declineOffer(tripId: String) {
        val profile = requireProfile()
        platform.declineOffer(profile.driverId, tripId)
        update { it.copy(offers = it.offers.filterNot { offer -> offer.id == tripId }) }
        refresh()
    }

    /** After the completion screen: forget the closed trip. */
    suspend fun finishTrip() {
        update { if (it.trip?.closed == true) it.copy(trip = null) else it }
    }

    /**
     * Builds the salted payload, hashes it, signs the check-in transaction on the phone and queues
     * it. Online, it is submitted straight away; offline, it records on reconnect, in order.
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
        val distance = Geo.distanceRoundedM(request.fix.point, waypoint.position)
        val override = trip.approvedOverrideFor(waypoint.index)
        if (distance > waypoint.geofenceRadiusM && override == null) {
            return CheckInOutcome.Blocked(
                BlockReason.OUTSIDE_GEOFENCE,
                "You're ${formatDistance(distance)} from ${waypoint.name}. Check-in unlocks inside the ${waypoint.geofenceRadiusM} m geofence.",
            )
        }

        val now = now()
        val payload = WaypointPayload(
            trip = plan.id,
            waypoint = waypoint.index,
            event = when (waypoint.type) {
                WaypointType.PICKUP -> WaypointEvent.PICKUP
                WaypointType.CHECKPOINT -> WaypointEvent.CHECKIN
                WaypointType.DELIVERY -> WaypointEvent.DELIVERY
            },
            asset = plan.asset.id,
            tag = tag,
            lat = WaypointPayload.roundCoordinate(request.fix.point.lat),
            lon = WaypointPayload.roundCoordinate(request.fix.point.lon),
            accuracyM = request.fix.accuracyM,
            deviceTime = WaypointPayload.truncateTime(now),
            photos = request.photoHashes,
            salt = WaypointPayload.newSalt(),
            override = override?.id,
        )
        val canonical = payload.canonical()
        val hash = Hashes.hex(Hashes.sha256(canonical))
        val legPay = waypoint.legPay.takeIf { waypoint.isPaidAtCheckIn }
        val checkIn = CheckIn(
            waypointIndex = waypoint.index,
            event = payload.event,
            payloadJson = canonical,
            payloadHash = hash,
            deviceTime = now,
            tag = tag,
            distanceFromCentreM = distance,
            savedOffline = !online.value,
            status = CheckInStatus.QUEUED,
            pay = when {
                waypoint.type == WaypointType.DELIVERY -> LegPayStatus.IN_CONTRACT
                legPay != null -> LegPayStatus.AWAITING_RECORD
                else -> LegPayStatus.NONE
            },
            legPay = waypoint.legPay,
            overrideId = override?.id,
        )
        val spec = TxSpec.CheckInTx(
            tripId = plan.id,
            waypointIndex = waypoint.index,
            payloadJson = canonical,
            payloadHash = hash,
            assetAccount = plan.asset.account,
            tripAccount = plan.tripAccount,
            legPay = legPay,
            maxTimeEpochSeconds = (now + ledger.offlineMaxAgeMillis) / 1000,
        )
        update { state ->
            state.enqueue(TxKind.CHECK_IN, spec, now).let { queued ->
                queued.copy(trip = queued.trip?.copy(checkIns = queued.trip.checkIns + checkIn))
            }
        }

        flush()

        val result = state.value.trip?.checkIns?.lastOrNull { it.waypointIndex == waypoint.index } ?: checkIn
        return when (result.status) {
            CheckInStatus.RECORDED -> CheckInOutcome.Recorded(result)
            CheckInStatus.FAILED -> CheckInOutcome.Failed(result)
            CheckInStatus.QUEUED -> CheckInOutcome.Saved(result, state.value.queueNotice)
        }
    }

    /** "Request override": the geofence blocks check-in; ops sees the GPS evidence and decides. */
    suspend fun requestOverride(fix: GpsFix, reason: String): OverrideInfo {
        requireOnline()
        val trip = requireTrip()
        val waypoint = trip.nextWaypoint() ?: throw IllegalStateException("Every stop on this trip is checked in.")
        val info = platform.requestOverride(
            requireProfile().driverId,
            OverrideRequest(trip.plan.id, waypoint.index, fix, reason),
        )
        update { s -> s.copy(trip = s.trip?.copy(overrides = s.trip.overrides.filterNot { it.id == info.id } + info)) }
        return info
    }

    /** After the deadline, with no approval or dispute, the contract releases the final leg. */
    suspend fun claimFinalPay() {
        requireOnline()
        val trip = requireTrip()
        val contract = platform.claimFinalPay(requireProfile().driverId, trip.plan.id)
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

    suspend fun claimAdvance(advanceId: String) {
        requireOnline()
        check(state.value.canMoveMoney()) { "Your saved check-ins record first." }
        val trip = requireTrip()
        val advance = trip.advances.firstOrNull { it.id == advanceId && it.status == AdvanceStatus.READY }
            ?: throw IllegalStateException("This advance can't be claimed.")
        val spec = TxSpec.ClaimAdvanceTx(
            tripId = trip.plan.id,
            advanceId = advance.id,
            balanceId = advance.balanceId ?: throw IllegalStateException("This advance isn't ready yet."),
            maxTimeEpochSeconds = now() / 1000 + MONEY_TX_TIMEOUT_SECONDS,
        )
        update { it.enqueue(TxKind.CLAIM_ADVANCE, spec, now()) }
        flush()
    }

    suspend fun quote(amount: Usdc): FirmQuote {
        requireOnline()
        return platform.quote(requireProfile().driverId, amount)
    }

    /** Confirms a firm quote: the anchor returns where to pay, and the phone signs T9. */
    suspend fun cashOut(quote: FirmQuote): CashOutInfo {
        requireOnline()
        check(state.value.canMoveMoney()) { "Your saved check-ins record first." }
        val profile = requireProfile()
        val instructions = platform.startCashOut(profile.driverId, quote.id, profile.momoNumber)
        val now = now()
        val local = CashOutInfo(
            id = instructions.cashOutId,
            sell = instructions.amount,
            fee = quote.fee,
            buyAmount = quote.buyAmount,
            buyCurrency = quote.buyCurrency,
            momoNumber = profile.momoNumber,
            status = CashOutStatus.AWAITING_PAYMENT,
            createdAt = now,
            updatedAt = now,
            anchorName = quote.anchorName,
            eta = quote.eta,
        )
        val spec = TxSpec.CashOutTx(
            cashOutId = instructions.cashOutId,
            anchorAccount = instructions.anchorAccount,
            amount = instructions.amount,
            memoHash = instructions.memoHashHex,
            maxTimeEpochSeconds = now / 1000 + MONEY_TX_TIMEOUT_SECONDS,
        )
        update { it.enqueue(TxKind.CASH_OUT, spec, now).copy(cashOuts = listOf(local) + it.cashOuts) }
        flush()
        return state.value.cashOuts.firstOrNull { it.id == local.id } ?: local
    }

    // endregion

    // region The queue

    private fun DriverState.enqueue(kind: TxKind, spec: TxSpec, now: Long): DriverState {
        val sequence = sequence + 1
        val envelope = build(spec, sequence).toEnvelopeXdrBase64()
        return copy(
            sequence = sequence,
            queue = queue + QueuedTx(UUID.randomUUID().toString(), kind, sequence, envelope, spec, now),
        )
    }

    private fun build(spec: TxSpec, sequence: Long): Transaction {
        val signer = keys.signer()
        return when (spec) {
            is TxSpec.CheckInTx -> transactions.checkIn(
                signer = signer,
                sequence = sequence,
                payloadHash = Hashes.fromHex(spec.payloadHash),
                assetAccount = spec.assetAccount,
                tripAccount = spec.tripAccount,
                legPay = spec.legPay,
                maxTimeEpochSeconds = spec.maxTimeEpochSeconds,
            )
            is TxSpec.ClaimAdvanceTx -> transactions.claimAdvance(signer, sequence, spec.balanceId, spec.maxTimeEpochSeconds)
            is TxSpec.CashOutTx -> transactions.cashOut(
                signer = signer,
                sequence = sequence,
                anchorAccount = spec.anchorAccount,
                amount = spec.amount,
                memoHash = Hashes.fromHex(spec.memoHash),
                maxTimeEpochSeconds = spec.maxTimeEpochSeconds,
            )
        }
    }

    /** Re-signs every queued transaction with consecutive sequence numbers after [base]. */
    private fun DriverState.resequenced(base: Long): DriverState {
        val rebuilt = queue.mapIndexed { i, item ->
            val sequence = base + 1 + i
            if (item.sequence == sequence) item
            else item.copy(sequence = sequence, envelopeXdr = build(item.spec, sequence).toEnvelopeXdrBase64())
        }
        return copy(queue = rebuilt, sequence = base + rebuilt.size)
    }

    /**
     * Submits queued transactions strictly in order (§4.4). Stops at the first one that has to
     * wait, so nothing jumps the queue.
     */
    suspend fun flush() {
        flushLock.withLock {
            val profile = state.value.profile ?: return
            var resyncs = 0
            var recordedAny = false
            while (online.value) {
                val item = state.value.queue.firstOrNull() ?: break
                val result = try {
                    platform.submit(profile.driverId, item.toSubmission())
                } catch (_: OfflineException) {
                    break
                } catch (e: PlatformException) {
                    update { it.copy(queueNotice = e.message) }
                    break
                }
                when (result) {
                    is SubmitResult.Recorded -> {
                        update { it.recorded(item, result) }
                        recordedAny = true
                    }
                    is SubmitResult.BadSequence -> {
                        if (resyncs++ >= MAX_RESYNCS) break
                        update { it.resequenced(result.accountSequence) }
                    }
                    is SubmitResult.Rejected -> {
                        val spec = item.spec
                        if (result.code == RejectCode.ESCROW_UNDERFUNDED && spec is TxSpec.CheckInTx && spec.legPay != null) {
                            update { it.recordOnlyFallback(item, spec) }
                        } else {
                            update { it.rejected(item, result) }
                        }
                    }
                    is SubmitResult.Retry -> {
                        update { it.copy(queueNotice = result.message) }
                        break
                    }
                }
            }
            if (recordedAny) refresh()
        }
    }

    private fun QueuedTx.toSubmission(): Submission = when (val spec = spec) {
        is TxSpec.CheckInTx -> Submission(kind, envelopeXdr, tripId = spec.tripId, payloadJson = spec.payloadJson, refId = id)
        is TxSpec.ClaimAdvanceTx -> Submission(kind, envelopeXdr, tripId = spec.tripId, refId = spec.advanceId)
        is TxSpec.CashOutTx -> Submission(kind, envelopeXdr, refId = spec.cashOutId)
    }

    private fun DriverState.recorded(item: QueuedTx, result: SubmitResult.Recorded): DriverState {
        val base = copy(
            queue = queue.filterNot { it.id == item.id },
            queueNotice = null,
            balance = result.balance,
            balanceAsOf = result.ledgerTime,
        )
        return when (val spec = item.spec) {
            is TxSpec.CheckInTx -> base.updateCheckIn(spec.waypointIndex) {
                it.copy(
                    status = CheckInStatus.RECORDED,
                    txHash = result.txHash,
                    ledgerTime = result.ledgerTime,
                    pay = if (it.pay == LegPayStatus.AWAITING_RECORD) LegPayStatus.PAID else it.pay,
                )
            }
            is TxSpec.ClaimAdvanceTx -> base.copy(
                trip = base.trip?.copy(
                    advances = base.trip.advances.map {
                        if (it.id == spec.advanceId) {
                            it.copy(status = AdvanceStatus.CLAIMED, claimTx = result.txHash, claimedAt = result.ledgerTime)
                        } else {
                            it
                        }
                    },
                ),
            )
            is TxSpec.CashOutTx -> base.copy(
                cashOuts = base.cashOuts.map {
                    if (it.id == spec.cashOutId) {
                        it.copy(status = CashOutStatus.PENDING, txHash = result.txHash, updatedAt = result.ledgerTime)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Refused for good. It never used its sequence number, so everything behind it moves up one. */
    private fun DriverState.rejected(item: QueuedTx, result: SubmitResult.Rejected): DriverState {
        val remaining = copy(queue = queue.filterNot { it.id == item.id }).resequenced(item.sequence - 1)
        return when (val spec = item.spec) {
            is TxSpec.CheckInTx -> remaining.updateCheckIn(spec.waypointIndex) {
                it.copy(status = CheckInStatus.FAILED, failure = result.code, failureMessage = result.message)
            }
            is TxSpec.ClaimAdvanceTx -> remaining.copy(queueNotice = result.message)
            is TxSpec.CashOutTx -> remaining.copy(
                queueNotice = result.message,
                cashOuts = remaining.cashOuts.map { if (it.id == spec.cashOutId) it.copy(message = result.message) else it },
            )
        }
    }

    /**
     * §8: the escrow can't pay, and the whole transaction would fail, location included. Re-sign the
     * same check-in without its payment so custody is still recorded, and mark the pay pending.
     */
    private fun DriverState.recordOnlyFallback(item: QueuedTx, spec: TxSpec.CheckInTx): DriverState {
        val recordOnly = spec.copy(legPay = null)
        val envelope = build(recordOnly, item.sequence).toEnvelopeXdrBase64()
        return copy(queue = queue.map { if (it.id == item.id) it.copy(spec = recordOnly, envelopeXdr = envelope) else it })
            .updateCheckIn(spec.waypointIndex) { it.copy(pay = LegPayStatus.PENDING_ESCROW) }
    }

    private fun DriverState.updateCheckIn(index: Int, change: (CheckIn) -> CheckIn): DriverState {
        val trip = trip ?: return this
        val position = trip.checkIns.indexOfLast { it.waypointIndex == index }
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
        }
    }

    companion object {
        private const val MAX_RESYNCS = 2
        private const val MONEY_TX_TIMEOUT_SECONDS = 300L

        fun formatDistance(metres: Int): String =
            if (metres < 1_000) "$metres m" else "%.1f km".format(metres / 1000.0).replace(".0 km", " km")
    }
}
