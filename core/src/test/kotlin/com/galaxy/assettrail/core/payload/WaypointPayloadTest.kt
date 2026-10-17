package com.galaxy.assettrail.core.payload

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaypointPayloadTest {

    /** The spec's example payload (§4.2) with a fixed salt. */
    private val konongo = WaypointPayload(
        trip = "TR-2041",
        waypoint = 4,
        event = WaypointEvent.CHECKIN,
        asset = "GX400-0041",
        tag = "04:A3:7E:91",
        lat = 6.6167,
        lon = -1.2167,
        accuracyM = 8,
        deviceTime = Instant.parse("2026-10-16T11:51:37Z"),
        photos = listOf("sha256:9f2c"),
        salt = "q7cSVd0Hk9Y7z1VbYw3Xj5rR2sMZcIFv0b0c8yJk9nM=",
    )

    @Test
    fun `canonical form sorts keys and drops whitespace`() {
        val expected = """{"acc_m":8,"asset":"GX400-0041","event":"checkin","lat":6.6167,"lon":-1.2167,""" +
            """"photos":["sha256:9f2c"],"salt":"q7cSVd0Hk9Y7z1VbYw3Xj5rR2sMZcIFv0b0c8yJk9nM=",""" +
            """"t_device":"2026-10-16T11:51:37Z","tag":"04:A3:7E:91","trip":"TR-2041","v":1,"wp":4}"""
        assertEquals(expected, konongo.canonical())
    }

    @Test
    fun `hash is the SHA-256 of the canonical bytes`() {
        val hash = konongo.hash()
        assertEquals(32, hash.size)
        assertContentEquals(Hashes.sha256(konongo.canonical().toByteArray(Charsets.UTF_8)), hash)
        assertContentEquals(hash, konongo.copy().hash())
    }

    @Test
    fun `a different salt gives an unguessable different hash`() {
        val other = konongo.copy(salt = WaypointPayload.newSalt())
        assertFalse(konongo.hash().contentEquals(other.hash()))
        assertEquals(32, Hashes.fromBase64(other.salt).size)
    }

    @Test
    fun `parse reads back what was written`() {
        assertEquals(konongo, WaypointPayload.parse(konongo.canonical()))
    }

    @Test
    fun `events match what the signer service expects at each stop`() {
        assertEquals(WaypointEvent.PICKUP, WaypointEvent.at(0, last = 4))
        assertEquals(WaypointEvent.CHECKIN, WaypointEvent.at(2, last = 4))
        assertEquals("arrival", WaypointEvent.at(4, last = 4).wire)
    }

    @Test
    fun `final-pay claim is canonical, salted and reads back`() {
        val claim = FinalPayClaim("TR-2041", "GX400-0041", Instant.parse("2026-10-18T09:00:00Z"), konongo.salt)
        assertEquals(
            """{"asset":"GX400-0041","event":"release","salt":"q7cSVd0Hk9Y7z1VbYw3Xj5rR2sMZcIFv0b0c8yJk9nM=",""" +
                """"t_device":"2026-10-18T09:00:00Z","trip":"TR-2041","v":1}""",
            claim.canonical(),
        )
        assertEquals(claim, FinalPayClaim.parse(claim.canonical()))
        assertContentEquals(PayloadVerifier.hash(claim.canonical()), claim.hash())
    }

    @Test
    fun `verifier matches any spelling of the same payload`() {
        val pretty = """
            {
              "wp": 4, "v": 1, "trip": "TR-2041", "tag": "04:A3:7E:91",
              "t_device": "2026-10-16T11:51:37Z", "salt": "q7cSVd0Hk9Y7z1VbYw3Xj5rR2sMZcIFv0b0c8yJk9nM=",
              "photos": ["sha256:9f2c"], "lon": -1.21670, "lat": 6.6167e0, "event": "checkin",
              "asset": "GX400-0041", "acc_m": 8.0
            }
        """.trimIndent()
        assertIs<PayloadVerifier.Result.Match>(PayloadVerifier.verify(pretty, konongo.hash()))
    }

    @Test
    fun `verifier flags a tampered payload`() {
        val moved = konongo.copy(lat = 6.6168).canonical()
        val result = PayloadVerifier.verify(moved, konongo.hash())
        assertIs<PayloadVerifier.Result.Mismatch>(result)
        assertEquals(Hashes.hex(konongo.hash()), result.recordedHash)
    }

    @Test
    fun `coordinates and time are trimmed the way the phone records them`() {
        assertEquals(6.616734, WaypointPayload.roundCoordinate(6.6167341234))
        assertEquals(-1.216701, WaypointPayload.roundCoordinate(-1.2167006))
        assertEquals(Instant.parse("2026-10-16T11:51:37Z"), WaypointPayload.truncateTime(Instant.parse("2026-10-16T11:51:37.987Z").toEpochMilli()))
        assertTrue(Hashes.short("2e88aa00bb119c51") == "2e88…9c51")
    }
}
