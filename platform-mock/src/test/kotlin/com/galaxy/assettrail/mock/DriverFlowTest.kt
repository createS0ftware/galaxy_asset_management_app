package com.galaxy.assettrail.mock

import com.galaxy.assettrail.core.driver.ActiveTrip
import com.galaxy.assettrail.core.driver.BlockReason
import com.galaxy.assettrail.core.driver.CheckInOutcome
import com.galaxy.assettrail.core.driver.CheckInRequest
import com.galaxy.assettrail.core.driver.CheckInStatus
import com.galaxy.assettrail.core.driver.DriverRepository
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.InMemoryDriverKeyStore
import com.galaxy.assettrail.core.driver.LegPayStatus
import com.galaxy.assettrail.core.driver.canClaimFinalPay
import com.galaxy.assettrail.core.driver.canMoveMoney
import com.galaxy.assettrail.core.driver.earned
import com.galaxy.assettrail.core.driver.isAwaitingSignature
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.ledger.LedgerConfig
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.OverrideStatus
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.payload.WaypointPayload
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.store.MemoryStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}

/** End-to-end: the real repository and transaction signing against the mock backend. */
class DriverFlowTest {
    private val clock = MutableClock(Instant.parse("2026-10-16T07:05:00Z"))
    private val online = MutableStateFlow(true)
    private val ledger = LedgerConfig.Testnet
    private val platformStore = MemoryStateStore<SimState>()
    private val platform = MockPlatform(platformStore, ledger, clock, isOnline = { online.value }, latency = {})
    private val driverStore = MemoryStateStore<DriverState>()
    private val keys = InMemoryDriverKeyStore()
    private val repo = newRepository()

    private fun newRepository() = DriverRepository(
        platform = platform,
        store = driverStore,
        keys = keys,
        ledger = ledger,
        online = online,
        clock = clock,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private val trip: ActiveTrip get() = assertNotNull(repo.state.value.trip)

    private suspend fun onboardAndAccept() {
        repo.sendCode("+233 24 123 4471")
        repo.verifyCode(MockPlatform.DEMO_CODE)
        repo.submitIdentity("Kwame Asante", "GHA-723451890-3")
        repo.finishOnboarding("024 123 4471", recovery = true)
        val offer = repo.state.value.offers.single()
        assertEquals("TR-2041", offer.id)
        repo.acceptOffer(offer.id)
    }

    /** Stand [metres] from the next stop's centre and scan [tag] (the right one by default). */
    private suspend fun checkInNext(metres: Double = 82.0, tag: String? = null): CheckInOutcome {
        val waypoint = assertNotNull(trip.nextWaypoint())
        val position = Geo.offset(waypoint.position, metres, 45.0)
        return repo.checkIn(
            CheckInRequest(
                waypointIndex = waypoint.index,
                scannedTag = tag ?: trip.plan.asset.tagId,
                fix = GpsFix(position, accuracyM = 8, timeMillis = clock.millis()),
                photoHashes = listOf("sha256:00ff"),
            ),
        )
    }

    private fun usdc(amount: String) = Usdc.of(amount)

    @Test
    fun `whole trip pays every leg and the final leg on the receiver's signature`() = runBlocking<Unit> {
        onboardAndAccept()

        val pickup = assertIs<CheckInOutcome.Recorded>(checkInNext(120.0)).checkIn
        assertEquals(LegPayStatus.NONE, pickup.pay)
        assertNotNull(pickup.txHash)

        listOf("18.00", "40.00", "55.00").forEach { expected ->
            val leg = assertIs<CheckInOutcome.Recorded>(checkInNext()).checkIn
            assertEquals(LegPayStatus.PAID, leg.pay)
            assertEquals(usdc(expected), repo.state.value.balance)
        }
        assertEquals(usdc("55.00"), trip.earned())

        val delivery = assertIs<CheckInOutcome.Recorded>(checkInNext(40.0)).checkIn
        assertEquals(LegPayStatus.IN_CONTRACT, delivery.pay)
        assertTrue(trip.isAwaitingSignature())

        platform.receiverSigns(trip.plan.id)
        repo.refresh()
        assertEquals(ContractStatus.DELIVERED, trip.contract.status)
        assertEquals(usdc("25.00"), trip.contract.payout)
        assertEquals(usdc("80.00"), repo.state.value.balance)
        assertTrue(trip.closed)

        repo.finishTrip()
        assertNull(repo.state.value.trip)
        assertEquals("TR-2042", repo.state.value.offers.single().id)
    }

    @Test
    fun `offline check-ins are signed on the phone and record in order on reconnect`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        online.value = false

        val nsawam = assertIs<CheckInOutcome.Saved>(checkInNext()).checkIn
        assertTrue(nsawam.savedOffline)
        clock.advance(Duration.ofMinutes(70))
        assertIs<CheckInOutcome.Saved>(checkInNext())
        val queue = repo.state.value.queue
        assertEquals(2, queue.size)
        assertEquals(queue[0].sequence + 1, queue[1].sequence)
        assertFalse(repo.state.value.canMoveMoney())

        online.value = true
        repo.flush()

        assertTrue(repo.state.value.queue.isEmpty())
        assertTrue(trip.checkIns.all { it.status == CheckInStatus.RECORDED })
        assertEquals(usdc("40.00"), repo.state.value.balance)
        val recorded = trip.checkIns[1]
        assertTrue(assertNotNull(recorded.ledgerTime) > recorded.deviceTime, "ledger time comes after device time")
    }

    @Test
    fun `the queue survives an app restart`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        online.value = false
        checkInNext()

        val restarted = newRepository().also { it.load() }
        assertEquals(1, restarted.state.value.queue.size)
        online.value = true
        restarted.flush()
        assertEquals(usdc("18.00"), restarted.state.value.balance)
    }

    @Test
    fun `wrong tag is blocked on the phone and nothing is signed`() = runBlocking<Unit> {
        onboardAndAccept()
        val blocked = assertIs<CheckInOutcome.Blocked>(checkInNext(120.0, tag = "04:A3:7E:00"))
        assertEquals(BlockReason.TAG_MISMATCH, blocked.reason)
        assertTrue(repo.state.value.queue.isEmpty())
        assertTrue(trip.checkIns.isEmpty())
    }

    @Test
    fun `outside the geofence needs an ops override`() = runBlocking<Unit> {
        onboardAndAccept()
        val blocked = assertIs<CheckInOutcome.Blocked>(checkInNext(450.0))
        assertEquals(BlockReason.OUTSIDE_GEOFENCE, blocked.reason)

        val waypoint = assertNotNull(trip.nextWaypoint())
        val fix = GpsFix(Geo.offset(waypoint.position, 450.0, 45.0), 12, clock.millis())
        val requested = repo.requestOverride(fix, "Port gate moved")
        assertEquals(OverrideStatus.PENDING, requested.status)

        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        assertEquals(OverrideStatus.APPROVED, trip.overrides.single().status)

        val recorded = assertIs<CheckInOutcome.Recorded>(checkInNext(450.0)).checkIn
        assertEquals(requested.id, WaypointPayload.parse(recorded.payloadJson).override)
    }

    @Test
    fun `underfunded escrow still records the location and leaves pay pending`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        platform.updateFlags { it.copy(underfundNextLeg = true) }

        val nsawam = assertIs<CheckInOutcome.Recorded>(checkInNext()).checkIn
        assertEquals(LegPayStatus.PENDING_ESCROW, nsawam.pay)
        assertEquals(Usdc.ZERO, repo.state.value.balance)

        platform.opsPaysPendingLegs(trip.plan.id)
        repo.refresh()
        assertEquals(LegPayStatus.PAID, trip.checkIns[1].pay)
        assertEquals(usdc("18.00"), repo.state.value.balance)
    }

    @Test
    fun `a stale check-in fails and the one behind it is re-signed with the freed sequence`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        online.value = false
        checkInNext()
        clock.advance(Duration.ofHours(7))
        checkInNext()

        online.value = true
        repo.flush()

        val (nsawam, nkawkaw) = trip.checkIns.drop(1)
        assertEquals(RejectCode.TOO_LATE, nsawam.failure)
        // Re-sequenced, so the signer service judged it on BR-03, not on a sequence gap.
        assertEquals(RejectCode.NOT_NEXT_WAYPOINT, nkawkaw.failure)
        assertTrue(repo.state.value.queue.isEmpty())
        assertEquals(nsawam.waypointIndex, trip.nextWaypoint()?.index)
    }

    @Test
    fun `fuel advance is approved, claimed, and comes off the final leg`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        assertFailsWith<IllegalArgumentException> { repo.requestAdvance(usdc("30.00")) }

        repo.requestAdvance(usdc("20.00"))
        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        val ready = trip.advances.single()
        assertEquals(AdvanceStatus.READY, ready.status)
        assertNotNull(ready.balanceId)

        repo.claimAdvance(ready.id)
        assertEquals(AdvanceStatus.CLAIMED, trip.advances.single().status)
        assertEquals(usdc("20.00"), repo.state.value.balance)

        repeat(3) { checkInNext() }
        checkInNext(40.0)
        platform.receiverSigns(trip.plan.id)
        repo.refresh()
        assertEquals(usdc("5.00"), trip.contract.payout)
        assertEquals(usdc("20.00"), trip.contract.deduction)
        assertEquals(usdc("80.00"), repo.state.value.balance)
    }

    @Test
    fun `money can't move while check-ins wait in the queue`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        repo.requestAdvance(usdc("10.00"))
        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        online.value = false
        checkInNext()
        online.value = true // back online, queue not flushed yet

        assertFailsWith<IllegalStateException> { repo.claimAdvance(trip.advances.single().id) }
    }

    @Test
    fun `declined advance shows the reason`() = runBlocking<Unit> {
        onboardAndAccept()
        platform.updateFlags { it.copy(declineNextAdvance = true) }
        repo.requestAdvance(usdc("10.00"))
        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        val declined = trip.advances.single()
        assertEquals(AdvanceStatus.DECLINED, declined.status)
        assertNotNull(declined.declineReason)
    }

    @Test
    fun `cash out pays the anchor at a firm quote and settles`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        repeat(3) { checkInNext() }

        val quote = repo.quote(usdc("55.00"))
        assertEquals(usdc("0.55"), quote.fee)
        val cashOut = repo.cashOut(quote)
        assertEquals(CashOutStatus.PENDING, cashOut.status)
        assertEquals(Usdc.ZERO, repo.state.value.balance)

        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        assertEquals(CashOutStatus.COMPLETED, repo.state.value.cashOuts.first().status)
    }

    @Test
    fun `failed payout is refunded`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        checkInNext()
        platform.updateFlags { it.copy(failNextCashOut = true) }
        repo.cashOut(repo.quote(usdc("18.00")))
        clock.advance(Duration.ofSeconds(10))
        repo.refresh()
        assertEquals(CashOutStatus.REFUNDED, repo.state.value.cashOuts.first().status)
        assertEquals(usdc("18.00"), repo.state.value.balance)
    }

    @Test
    fun `an expired quote can't be confirmed`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        checkInNext()
        val quote = repo.quote(usdc("10.00"))
        clock.advance(Duration.ofSeconds(61))
        val error = assertFailsWith<PlatformException> { repo.cashOut(quote) }
        assertEquals("quote_expired", error.code)
    }

    @Test
    fun `after the deadline the driver claims the final leg without a signature`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        repeat(3) { checkInNext() }
        checkInNext(40.0)
        assertFalse(trip.canClaimFinalPay(clock.millis()))

        platform.expireDeliveryDeadline(trip.plan.id)
        repo.refresh()
        assertTrue(trip.canClaimFinalPay(clock.millis()))
        repo.claimFinalPay()
        assertEquals(ContractStatus.RELEASED_AFTER_DEADLINE, trip.contract.status)
        assertEquals(usdc("80.00"), repo.state.value.balance)
    }

    @Test
    fun `a dispute holds the final leg until ops resolves it`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        repeat(3) { checkInNext() }
        checkInNext(40.0)
        platform.receiverReportsProblem(trip.plan.id)
        platform.expireDeliveryDeadline(trip.plan.id)
        repo.refresh()
        assertEquals(ContractStatus.DISPUTED, trip.contract.status)
        assertFalse(trip.canClaimFinalPay(clock.millis()))

        platform.opsResolvesDisputeForDriver(trip.plan.id)
        repo.refresh()
        assertEquals(ContractStatus.RESOLVED, trip.contract.status)
        assertEquals(usdc("80.00"), repo.state.value.balance)
    }

    @Test
    fun `fee account top-up keeps check-ins queued, then they record`() = runBlocking<Unit> {
        onboardAndAccept()
        checkInNext(120.0)
        platform.updateFlags { it.copy(feeAccountLow = true) }
        val saved = assertIs<CheckInOutcome.Saved>(checkInNext())
        assertNotNull(saved.notice)
        platform.updateFlags { it.copy(feeAccountLow = false) }
        repo.flush()
        assertEquals(CheckInStatus.RECORDED, trip.checkIns.last().status)
    }
}
