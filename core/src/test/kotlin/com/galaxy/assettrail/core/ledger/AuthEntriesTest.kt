package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.payload.Hashes
import org.stellar.sdk.Auth
import org.stellar.sdk.KeyPair
import org.stellar.sdk.StrKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthEntriesTest {
    private val network = LedgerConfig.Testnet.network
    private val contract = StrKey.encodeContract(ByteArray(32) { 7 })
    private val driver = KeyPair.random()
    private val signer = KeyPairSigner(driver)
    private val hash = Hashes.sha256("payload")
    private val call = TrailCalls.checkIn(contract, "TR-2041", 1, hash)

    @Test
    fun `a check-in authorization names the driver, the call and its last ledger`() {
        val entry = AuthEntries.authorize(signer, call, 1_000L, network)
        val inspected = AuthEntries.inspect(entry)
        assertEquals(driver.accountId, inspected.address)
        assertEquals(1_000L, inspected.validUntilLedger)
        assertEquals(call.toXdrBase64(), inspected.invocation.toXdrBase64())
        assertNull(AuthEntries.problem(entry, driver.accountId, call, latestLedger = 999, network = network))
    }

    @Test
    fun `entries carry their own nonce, so offline check-ins never collide`() {
        assertNotEquals(AuthEntries.authorize(signer, call, 1_000L, network), AuthEntries.authorize(signer, call, 1_000L, network))
    }

    @Test
    fun `the signer service's checks refuse the wrong signer, call, ledger or signature`() {
        val entry = AuthEntries.authorize(signer, call, 1_000L, network)
        assertNotNull(AuthEntries.problem(entry, KeyPair.random().accountId, call, 999, network))
        assertNotNull(AuthEntries.problem(entry, driver.accountId, TrailCalls.checkIn(contract, "TR-2041", 2, hash), 999, network))
        assertNotNull(AuthEntries.problem(entry, driver.accountId, TrailCalls.checkIn(contract, "TR-2042", 1, hash), 999, network))
        assertNotNull(AuthEntries.problem(entry, driver.accountId, call, latestLedger = 1_000, network = network))
        val forged = AuthEntries.authorize(KeyPairSignerFor(driver.accountId, KeyPair.random()), call, 1_000L, network)
        assertEquals("entry is not signed by ${driver.accountId}", AuthEntries.problem(forged, driver.accountId, call, 999, network))
    }

    @Test
    fun `entries signed by the SDK's own helper pass the same checks`() {
        val entry = Auth.authorizeInvocation(driver, 1_000L, call, network).toXdrBase64()
        assertNull(AuthEntries.problem(entry, driver.accountId, call, 999, network))
    }

    @Test
    fun `offline validity follows the backend's offlineValidUntil`() {
        val info = LedgerConfig.Testnet.networkInfo(contract)
        val clock = LedgerClock(latestLedger = 50_000, seenAt = 0)
        // 10 minutes after the last sync: 120 ledgers on, plus 12 hours of 5-second ledgers.
        assertEquals(50_000L + 120 + 8_640, clock.offlineValidUntil(now = 600_000, network = info))
        assertEquals(50_000L, clock.estimate(now = -5_000, ledgerSeconds = 5))
    }

    /** Claims [accountId] but signs with another key. */
    private class KeyPairSignerFor(override val accountId: String, private val key: KeyPair) : DriverSigner {
        override fun sign(data: ByteArray): ByteArray = key.sign(data)
    }
}
