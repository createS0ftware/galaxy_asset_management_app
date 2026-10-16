package com.galaxy.assettrail.core.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** What happened at the waypoint. Pickup starts custody (T4); delivery is the final arrival. */
enum class WaypointEvent(val wire: String) {
    PICKUP("pickup"),
    CHECKIN("checkin"),
    DELIVERY("delivery");

    companion object {
        fun fromWire(value: String) = entries.first { it.wire == value }
    }
}

/**
 * The off-chain record behind one check-in (spec §4.2). Only SHA-256 of its canonical JSON goes on
 * the ledger; the payload itself, salt included, stays in the platform's store.
 */
data class WaypointPayload(
    val trip: String,
    val waypoint: Int,
    val event: WaypointEvent,
    val asset: String,
    val tag: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val deviceTime: Instant,
    val photos: List<String>,
    /** 32 random bytes, base64. Without it the location could be brute-forced from the public memo. */
    val salt: String,
    /** Reference of an ops override that allowed this check-in outside the geofence, if any. */
    val override: String? = null,
    val version: Int = 1,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", version)
        put("trip", trip)
        put("wp", waypoint)
        put("event", event.wire)
        put("asset", asset)
        put("tag", tag)
        put("lat", lat)
        put("lon", lon)
        put("acc_m", accuracyM)
        put("t_device", DateTimeFormatter.ISO_INSTANT.format(deviceTime))
        put("photos", JsonArray(photos.map(::JsonPrimitive)))
        put("salt", salt)
        override?.let { put("override", it) }
    }

    /** The exact bytes that are hashed. */
    fun canonical(): String = CanonicalJson.encode(toJson())

    /** The 32-byte digest that goes in MEMO_HASH and in the asset account's `wp` entry. */
    fun hash(): ByteArray = Hashes.sha256(canonical())

    companion object {
        fun newSalt(): String = Hashes.base64(Hashes.randomBytes(32))

        /** GPS to 6 decimals (about 0.1 m): enough for a geofence, and keeps payloads tidy. */
        fun roundCoordinate(value: Double): Double = (value * 1_000_000).roundToLong() / 1_000_000.0

        /** Device time to the second, as the payload carries it. */
        fun truncateTime(epochMillis: Long): Instant = Instant.ofEpochSecond(Math.floorDiv(epochMillis, 1000L))

        fun parse(json: String): WaypointPayload {
            val obj = Json.parseToJsonElement(json).jsonObject
            fun text(key: String) = obj.getValue(key).jsonPrimitive.content
            return WaypointPayload(
                version = obj.getValue("v").jsonPrimitive.int,
                trip = text("trip"),
                waypoint = obj.getValue("wp").jsonPrimitive.int,
                event = WaypointEvent.fromWire(text("event")),
                asset = text("asset"),
                tag = text("tag"),
                lat = obj.getValue("lat").jsonPrimitive.double,
                lon = obj.getValue("lon").jsonPrimitive.double,
                accuracyM = obj.getValue("acc_m").jsonPrimitive.int,
                deviceTime = Instant.parse(text("t_device")),
                photos = obj["photos"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                salt = text("salt"),
                override = obj["override"]?.jsonPrimitive?.content,
            )
        }
    }
}

/**
 * The auditor's check (spec §5.4 stage 3): re-hash a payload and compare it with the on-chain memo.
 * Works on any JSON spelling of the payload, because it re-canonicalises before hashing.
 */
object PayloadVerifier {
    sealed interface Result {
        data object Match : Result
        data class Mismatch(val payloadHash: String, val memoHash: String) : Result
    }

    fun verify(payloadJson: String, memoHash: ByteArray): Result {
        val canonical = CanonicalJson.encode(Json.parseToJsonElement(payloadJson))
        val computed = Hashes.sha256(canonical)
        return if (computed.contentEquals(memoHash)) Result.Match
        else Result.Mismatch(Hashes.hex(computed), Hashes.hex(memoHash))
    }
}
