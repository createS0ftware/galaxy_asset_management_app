package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.payload.FinalPayClaim
import com.galaxy.assettrail.core.payload.Hashes
import com.galaxy.assettrail.core.payload.WaypointEvent
import com.galaxy.assettrail.core.payload.WaypointPayload
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.StrKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden values produced by the backend's own code (galaxy_asset_management_backend,
 * `backend/src/client/driver.ts` signCheckIn, `lib/payload.ts`, `stellar/trail.ts`) for the same
 * inputs. If one of these breaks, the phone and the signer service no longer agree.
 */
class BackendCompatibilityTest {
    private val contract = StrKey.encodeContract(ByteArray(32) { 7 })
    private val driver = KeyPair.fromSecretSeed(ByteArray(32) { 9 })

    private val payload = WaypointPayload(
        trip = "TR-2041",
        waypoint = 1,
        event = WaypointEvent.CHECKIN,
        asset = "GEN-400KVA-0192",
        tag = "04:A3:7E:91",
        lat = 5.603716,
        lon = -0.186964,
        accuracyM = 12,
        deviceTime = Instant.parse("2026-10-16T11:51:37Z"),
        photos = listOf("sha256:" + "ab".repeat(32)),
        salt = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    )

    @Test
    fun `payload canonical form and hash match the backend`() {
        assertEquals(
            """{"acc_m":12,"asset":"GEN-400KVA-0192","event":"checkin","lat":5.603716,"lon":-0.186964,""" +
                """"photos":["sha256:abababababababababababababababababababababababababababababababab"],""" +
                """"salt":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","t_device":"2026-10-16T11:51:37Z",""" +
                """"tag":"04:A3:7E:91","trip":"TR-2041","v":1,"wp":1}""",
            payload.canonical(),
        )
        assertEquals(BACKEND_HASH, Hashes.hex(payload.hash()))
    }

    @Test
    fun `check_in invocation matches the backend's argument types`() {
        assertEquals("CADQOBYHA4DQOBYHA4DQOBYHA4DQOBYHA4DQOBYHA4DQOBYHA4DQP5KR", contract)
        assertEquals(BACKEND_CHECK_IN_INVOCATION, TrailCalls.checkIn(contract, "TR-2041", 1, payload.hash()).toXdrBase64())
    }

    @Test
    fun `claim invocations and the final-pay claim hash match the backend`() {
        assertEquals(BACKEND_CLAIM_ADVANCE_INVOCATION, TrailCalls.claimAdvance(contract, "TR-2041").toXdrBase64())
        val claim = FinalPayClaim("TR-2041", "GEN-400KVA-0192", Instant.parse("2026-10-18T09:00:00Z"), payload.salt)
        assertEquals(
            """{"asset":"GEN-400KVA-0192","event":"release","salt":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",""" +
                """"t_device":"2026-10-18T09:00:00Z","trip":"TR-2041","v":1}""",
            claim.canonical(),
        )
        assertEquals("de6bf3f90f5bea731f42dda7426f86090a7f75d9b6b336c2eec15d0502d49b39", Hashes.hex(claim.hash()))
        assertEquals(BACKEND_CLAIM_FINAL_PAY_INVOCATION, TrailCalls.claimFinalPay(contract, "TR-2041", claim.hash()).toXdrBase64())
    }

    @Test
    fun `an entry signed by the backend's phone client passes the phone's verifier`() {
        assertEquals("GD6ROJBYLKQMOW3E7N4M2YBPUHMZD7PL65VRHRMO24BOVSBV5H3BQRSL", driver.accountId)
        val invocation = TrailCalls.checkIn(contract, "TR-2041", 1, payload.hash())
        assertNull(AuthEntries.problem(BACKEND_SIGNED_ENTRY, driver.accountId, invocation, latestLedger = 1_999, network = Network.TESTNET))
        assertEquals(2_000L, AuthEntries.inspect(BACKEND_SIGNED_ENTRY).validUntilLedger)
    }

    @Test
    fun `an entry signed on the phone has the same shape as the backend's`() {
        val invocation = TrailCalls.checkIn(contract, "TR-2041", 1, payload.hash())
        val entry = AuthEntries.authorize(KeyPairSigner(driver), invocation, 2_000L, Network.TESTNET)
        // Same credential type (ADDRESS_V2) and layout; only the random nonce and signature differ.
        assertEquals(BACKEND_SIGNED_ENTRY.take(8), entry.take(8))
        assertEquals(BACKEND_SIGNED_ENTRY.length, entry.length)
        assertNull(AuthEntries.problem(entry, driver.accountId, invocation, latestLedger = 1_999, network = Network.TESTNET))
    }

    private companion object {
        const val BACKEND_HASH = "b7fb28679e2435b8f786069a4b7b336acefbfe68c9149ee2046a0869f70467f5"

        const val BACKEND_CLAIM_ADVANCE_INVOCATION =
            "AAAAAAAAAAEHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwAAAA1jbGFpbV9hZHZhbmNlAAAAAAAAAQAAAA4AAAAHVFItMjA0MQAAAAAA"

        const val BACKEND_CLAIM_FINAL_PAY_INVOCATION =
            "AAAAAAAAAAEHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwAAAA9jbGFpbV9maW5hbF9wYXkAAAAAAgAAAA4AAAAHVFItMjA0MQAAAAANAAAAIN5r8/kPW+pzH0Ldp0JvhgkKf3XZtrM2wu7BXQUC1Js5AAAAAA=="

        const val BACKEND_CHECK_IN_INVOCATION =
            "AAAAAAAAAAEHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwAAAAhjaGVja19pbgAAAAMAAAAOAAAAB1RSLTIwNDEAAAAAAwAAAAEAAAANAAAAILf7KGeeJDW494YGmkt7M2rO+/5oyRSe4gRqCGn3BGf1AAAAAA=="

        /** `signCheckIn(Keypair.fromRawEd25519Seed(9 × 32), 'TR-2041', …, validUntil = 2000)` on testnet. */
        const val BACKEND_SIGNED_ENTRY =
            "AAAAAgAAAAAAAAAA/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hhMrxUmv3IIwwAAB9AAAAAQAAAAAQAAAAEAAAARAAAAAQAAAAIAAAAPAAAACnB1YmxpY19rZXkAAAAAAA0AAAAg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hgAAAAPAAAACXNpZ25hdHVyZQAAAAAAAA0AAABAM3aR/cpYo1OPIiFA9hVuxlBkQKtYrVIpaEU+Qd2BQdVyMufBbdWwrMVzmCwQVTibL881QhjP1hc/BIOqXfU7DwAAAAAAAAABBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcAAAAIY2hlY2tfaW4AAAADAAAADgAAAAdUUi0yMDQxAAAAAAMAAAABAAAADQAAACC3+yhnniQ1uPeGBppLezNqzvv+aMkUnuIEaghp9wRn9QAAAAA="
    }
}
