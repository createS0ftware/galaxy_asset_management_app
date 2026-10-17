package com.galaxy.assettrail.http

import com.galaxy.assettrail.core.model.ActivityKind
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.platform.CheckInResult
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.core.platform.RejectCode
import com.galaxy.assettrail.core.platform.SignedCheckIn
import com.galaxy.assettrail.core.platform.SignedClaim
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client against the backend's JSON, as the driver routes return it (backend/src/http/routes). */
class HttpPlatformApiTest {
    private val transport = FakeTransport()
    private val tokens = MemoryTokenStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    private val api = HttpPlatformApi("http://backend.test/", transport, tokens, clock)

    private fun signedIn() = tokens.write("jwt-123")

    @Test
    fun `sign-in keeps the token and sends it on driver calls`() = runBlocking<Unit> {
        transport.on("POST", "/v1/auth/driver/otp", body = """{"sent":true,"devCode":"123456"}""")
        transport.on("POST", "/v1/auth/driver/verify", body = """{"token":"jwt-123","driver":{"id":7,"name":"Kwame"}}""")
        transport.on("POST", "/v1/driver/wallet/submit", body = """{"address":"GABC","txHash":"aa11"}""")

        val sent = api.sendPhoneCode("+233 24 123 4471")
        assertEquals("+233241234471", sent.phone)
        // A demo backend without SMS returns the code so the sign-in screen can show it.
        assertEquals("123456", sent.demoCode)
        assertEquals("7", api.verifyPhone("+233 24 123 4471", "123456"))
        assertEquals("jwt-123", tokens.read())
        assertEquals("""{"phone":"+233241234471","code":"123456"}""", transport.requests[1].body)
        assertNull(transport.requests[1].headers["Authorization"])

        assertEquals("aa11", api.submitWallet("7", "signed").txHash)
        assertEquals("Bearer jwt-123", transport.requests.last().headers["Authorization"])
    }

    @Test
    fun `a wrong code shows the backend's message and keeps no token`() = runBlocking<Unit> {
        transport.on("POST", "/v1/auth/driver/verify", status = 401, body = """{"error":"UNAUTHORIZED","message":"Invalid or expired code"}""")
        val error = assertFailsWith<PlatformException> { api.verifyPhone("+233241234471", "000000") }
        assertEquals("Invalid or expired code", error.message)
        assertNull(tokens.read())
    }

    @Test
    fun `an expired session forgets the token`() = runBlocking<Unit> {
        signedIn()
        transport.on("POST", "/v1/driver/wallet/submit", status = 401, body = """{"error":"UNAUTHORIZED","message":"Invalid or expired token"}""")
        assertEquals("UNAUTHORIZED", assertFailsWith<PlatformException> { api.submitWallet("7", "signed") }.code)
        assertNull(tokens.read())
    }

    @Test
    fun `no connection is offline, and errors carry the backend's code`() = runBlocking<Unit> {
        signedIn()
        transport.failWith = IOException("unreachable")
        assertFailsWith<OfflineException> { api.network() }
        transport.failWith = null
        transport.on("POST", "/v1/driver/cashouts/9/prepare", status = 422, body = """{"error":"QUOTE_EXPIRED","message":"Quote expired"}""")
        val error = assertFailsWith<PlatformException> { api.prepareCashOut("7", "9") }
        assertEquals("QUOTE_EXPIRED", error.code)
        assertEquals("{}", transport.requests.last().body)
    }

    @Test
    fun `network info comes from GET network`() = runBlocking<Unit> {
        transport.on("GET", "/v1/network", body = NETWORK)
        val info = api.network()
        assertEquals("CCONTRACT", info.contractId)
        assertEquals(12, info.maxOfflineHours)
        assertEquals("USDC", info.stellarAsset.toString().substringBefore(':'))
    }

    @Test
    fun `snapshot is composed from trips, wallet and the network`() = runBlocking<Unit> {
        signedIn()
        transport.on("GET", "/v1/driver/trips", body = "[${trip("TR-2041", "delivered", RELEASE_EVENTS)},${trip("TR-2042", "funded", DISPATCH_ONLY)}]")
        transport.on("GET", "/v1/driver/wallet", body = WALLET)
        transport.on("GET", "/v1/network", body = NETWORK)
        transport.on("GET", "/v1/driver/me", body = """{"id":7,"name":"Kwame","walletStatus":"ready","momoNumber":"+233241234471"}""")

        val snapshot = api.snapshot("7", "TR-2041")

        assertEquals(61_000_123L, snapshot.latestLedger)
        assertEquals(Usdc.of("80.00"), snapshot.balance)
        val offer = snapshot.offers.single()
        assertEquals("TR-2042", offer.id)
        assertEquals("Generator", offer.asset.shortName)
        assertEquals("", offer.asset.tagId)
        assertEquals("Warehouse B", offer.receiverName)
        assertEquals("dispatch-tx", offer.fundingTx)
        assertEquals(listOf(WaypointType.PICKUP, WaypointType.CHECKPOINT, WaypointType.DELIVERY), offer.waypoints.map { it.type })
        assertEquals(Usdc.of("18.00"), offer.waypoints[1].legPay)

        val status = assertIs<com.galaxy.assettrail.core.platform.TripStatus>(snapshot.trip)
        assertTrue(status.closed)
        assertEquals(ContractStatus.RELEASED_AFTER_DEADLINE, status.contract.status)
        assertEquals(Usdc.of("5.00"), status.contract.payout)
        assertEquals(Usdc.of("20.00"), status.contract.deduction)
        assertEquals(Instant.parse("2026-10-18T18:00:00Z").toEpochMilli(), status.contract.deadline)
        assertEquals(AdvanceStatus.CLAIMED, status.advances.single().status)

        // Newest first; the quoted cash-out moved no money, so it isn't listed.
        assertEquals(listOf(ActivityKind.CASH_OUT, ActivityKind.FINAL_PAY, ActivityKind.ADVANCE, ActivityKind.LEG_PAY), snapshot.activity.map { it.kind })
        assertEquals("Leg 1 · Nsawam", snapshot.activity.last().title)
        val cashOut = snapshot.cashOuts.single()
        assertEquals(CashOutStatus.COMPLETED, cashOut.status)
        assertEquals(Usdc.of("0.55"), cashOut.fee)
        assertEquals("+233241234471", cashOut.momoNumber)
    }

    @Test
    fun `check-in results map to recorded, refused and try-again`() = runBlocking<Unit> {
        signedIn()
        transport.on(
            "POST",
            "/v1/driver/trips/TR-2041/checkins/batch",
            body = """{"results":[
                {"index":0,"ok":true,"duplicate":false,"txHash":"t1","balance":"18.00","event":{"kind":5,"status":"confirmed","ledgerTime":"2026-10-17T11:59:58.000Z"}},
                {"index":1,"ok":true,"duplicate":true,"txHash":"t0","event":{"kind":4,"status":"confirmed"}},
                {"index":2,"ok":false,"code":"OUTSIDE_WINDOW","message":"Check-in is outside the waypoint time window"}
            ],"stoppedAt":2}""",
        )
        val items = List(4) { SignedCheckIn("""{"v":1,"wp":$it}""", "entry-$it") }
        val results = api.submitCheckIns("7", "TR-2041", items)

        val first = assertIs<CheckInResult.Recorded>(results[0])
        assertEquals(Usdc.of("18.00"), first.balance)
        assertEquals(Instant.parse("2026-10-17T11:59:58Z").toEpochMilli(), first.ledgerTime)
        val duplicate = assertIs<CheckInResult.Recorded>(results[1])
        assertTrue(duplicate.duplicate)
        assertNull(duplicate.balance)
        assertEquals(RejectCode.OUTSIDE_WINDOW, assertIs<CheckInResult.Rejected>(results[2]).code)
        assertTrue(transport.requests.single().body!!.contains(""""payload":{"v":1,"wp":0},"authEntry":"entry-0""""))
    }

    @Test
    fun `ledger trouble keeps check-ins queued, a malformed batch retries the first alone`() = runBlocking<Unit> {
        signedIn()
        transport.on("POST", "/v1/driver/trips/TR-1/checkins/batch", status = 502, body = """{"error":"LEDGER_UNAVAILABLE","message":"Stellar RPC is unavailable, retry shortly"}""")
        assertIs<CheckInResult.Retry>(api.submitCheckIns("7", "TR-1", listOf(SignedCheckIn("{}", "e"))).single())

        transport.on("POST", "/v1/driver/trips/TR-2/checkins/batch", status = 400, body = """{"error":"VALIDATION","message":"bad"}""")
        val results = api.submitCheckIns("7", "TR-2", listOf(SignedCheckIn("{}", "a"), SignedCheckIn("{}", "b")))
        assertEquals(RejectCode.UNKNOWN, assertIs<CheckInResult.Rejected>(results.single()).code)
        assertEquals(1, Regex("authEntry").findAll(transport.requests.last().body!!).count())
    }

    @Test
    fun `quote, advance and final pay map the backend's answers`() = runBlocking<Unit> {
        signedIn()
        transport.on(
            "POST",
            "/v1/driver/cashouts/quote",
            body = """{"id":9,"youSend":"55.00","anchorFee":"0.55","rate":"15.2000","youReceive":"827.64 GHS","to":"+233241234471",
                "arrival":"within 5 minutes","validUntil":"2026-10-17T12:05:00.000Z","networkFee":"paid by the platform","payTo":"GANCHOR","memo":"WD-1"}""",
        )
        val quote = api.quote("7", Usdc.of("55.00"))
        assertEquals("9", quote.id)
        assertEquals("827.64", quote.buyAmount)
        assertEquals("GHS", quote.buyCurrency)
        assertEquals("GANCHOR", quote.payTo)
        assertEquals("""{"amount":"55.0000000"}""", transport.requests.last().body)

        transport.on(
            "POST",
            "/v1/driver/trips/TR-1/advances/claim",
            body = """{"id":3,"tripId":"TR-1","amount":"20.00","status":"claimed","claimTx":"c1","createdAt":"2026-10-17T10:00:00.000Z"}""",
        )
        assertEquals(AdvanceStatus.CLAIMED, api.claimAdvance("7", "TR-1", "3", "entry").status)

        transport.on("POST", "/v1/driver/trips/TR-1/final-pay", body = """{"txHash":"f1","payout":"5.00","deduction":"20.00","balance":"80.00"}""")
        val contract = api.claimFinalPay("7", "TR-1", SignedClaim("""{"v":1,"event":"release"}""", "entry"))
        assertEquals(ContractStatus.RELEASED_AFTER_DEADLINE, contract.status)
        assertEquals(Usdc.of("25.00"), contract.locked)
        assertTrue(transport.requests.last().body!!.startsWith("""{"payload":{"v":1,"event":"release"}"""))
    }

    private fun trip(id: String, status: String, events: String) = """
        {"id":"$id","status":"$status",
         "asset":{"id":"GEN-400KVA-0192","name":"Diesel generator 400 kVA","type":"generator","serial":"SN-1"},
         "driver":{"id":7,"name":"Kwame","address":"GABC"},"receiver":{"name":"Warehouse B"},"vehicle":null,
         "escrow":{"funded":"120.00","released":"80.00","advances":"20.00","left":"40.00","advanceLimit":"40.00","funded_badge":true},
         "nextWp":3,"deliveryDeadline":"2026-10-18T18:00:00.000Z",
         "waypoints":[
          {"idx":0,"name":"Tema Port","type":"pickup","lat":5.6358,"lon":0.0138,"radiusM":300,"windowStart":"2026-10-17T07:00:00.000Z","windowEnd":"2026-10-17T08:00:00.000Z","legPay":"0.00","state":"verified","verified":null},
          {"idx":1,"name":"Nsawam","type":"checkpoint","lat":5.8089,"lon":-0.3503,"radiusM":200,"windowStart":"2026-10-17T09:00:00.000Z","windowEnd":"2026-10-17T10:00:00.000Z","legPay":"18.00","state":"verified","verified":null},
          {"idx":2,"name":"Kumasi","type":"delivery","lat":6.6885,"lon":-1.6244,"radiusM":150,"windowStart":"2026-10-17T13:00:00.000Z","windowEnd":"2026-10-17T14:00:00.000Z","legPay":"25.00","payNote":"paid when the receiver signs","state":"next","verified":null}],
         "live":null,
         "advances":[{"id":3,"tripId":"$id","amount":"20.00","reason":"fuel","status":"claimed","expiresAt":"2026-10-18T10:00:00.000Z","grantTx":"g1","claimTx":"c1","decidedBy":1,"createdAt":"2026-10-17T10:00:00.000Z"}],
         "events":$events,
         "trackingUrl":"http://localhost:3000/t/x","contractId":"CCONTRACT","createdAt":"2026-10-17T06:00:00.000Z"}
    """

    private companion object {
        const val NETWORK = """{"networkPassphrase":"Test SDF Network ; September 2015","rpcUrl":"https://soroban-testnet.stellar.org",
            "contractId":"CCONTRACT","payAsset":"USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5","latestLedger":61000123,
            "ledgerSeconds":5,"maxOfflineHours":12}"""

        const val DISPATCH_ONLY = """[{"id":1,"kind":3,"wp":0,"hash":"plan-hash","amount":1200000000,"status":"confirmed","txHash":"dispatch-tx","ledgerTime":"2026-10-17T06:00:05.000Z"}]"""

        const val RELEASE_EVENTS = """[
            {"id":1,"kind":3,"wp":0,"hash":"plan-hash","amount":1200000000,"status":"confirmed","txHash":"dispatch-tx","ledgerTime":"2026-10-17T06:00:05.000Z"},
            {"id":2,"kind":6,"wp":2,"hash":"h6","amount":0,"status":"confirmed","txHash":"arrive-tx","ledgerTime":"2026-10-17T13:30:00.000Z"},
            {"id":3,"kind":15,"wp":2,"hash":"h15","amount":50000000,"status":"confirmed","txHash":"release-tx","ledgerTime":"2026-10-18T18:05:00.000Z"}]"""

        const val WALLET = """{"balance":"80.00","asset":"USDC","networkFees":"paid by the platform","advanceReady":[],
            "activity":[
             {"type":"leg","kind":5,"trip":"TR-2041","wp":1,"amount":"18.00","txHash":"t1","at":"2026-10-17T09:30:00.000Z"},
             {"type":"leg","kind":15,"trip":"TR-2041","wp":2,"amount":"5.00","txHash":"release-tx","at":"2026-10-18T18:05:00.000Z"},
             {"type":"advance","trip":"TR-2041","amount":"20.00","txHash":"c1","at":"2026-10-17T10:00:00.000Z"},
             {"type":"cashout","id":9,"status":"completed","amount":"-55.00","txHash":"co1","at":"2026-10-18T19:00:00.000Z"},
             {"type":"cashout","id":10,"status":"quoted","amount":"-10.00","txHash":null,"at":"2026-10-18T19:10:00.000Z"}],
            "cashouts":[
             {"id":9,"amount":"55.00","fee":"0.55","buyAmount":"827.64","buyCurrency":"GHS","rate":"15.2000","arrival":"within 5 minutes","status":"completed","txHash":"co1","error":null,"createdAt":"2026-10-18T19:00:00.000Z"},
             {"id":10,"amount":"10.00","fee":"0.25","buyAmount":"148.20","buyCurrency":"GHS","rate":"15.2000","status":"quoted","txHash":null,"error":null,"createdAt":"2026-10-18T19:10:00.000Z"}]}"""
    }
}

class FakeTransport : HttpTransport {
    class Sent(val method: String, val path: String, val body: String?, val headers: Map<String, String>)

    val requests = mutableListOf<Sent>()
    var failWith: IOException? = null
    private val routes = mutableMapOf<String, HttpResult>()

    fun on(method: String, path: String, status: Int = 200, body: String) {
        routes["$method $path"] = HttpResult(status, body)
    }

    override suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        failWith?.let { throw it }
        val path = url.removePrefix("http://backend.test")
        requests += Sent(method, path, body, headers)
        return routes["$method $path"] ?: error("No route for $method $path")
    }
}
