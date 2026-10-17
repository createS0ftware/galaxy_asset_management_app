package com.galaxy.assettrail.core.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The record behind claiming the final leg after the delivery deadline (spec §1, §4.6). Hashed and
 * salted like a waypoint payload; the hash goes in `claim_final_pay` and into the asset's history.
 */
data class FinalPayClaim(
    val trip: String,
    val asset: String,
    val deviceTime: Instant,
    /** 32 random bytes, base64. */
    val salt: String,
    val version: Int = 1,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", version)
        put("event", EVENT)
        put("trip", trip)
        put("asset", asset)
        put("t_device", DateTimeFormatter.ISO_INSTANT.format(deviceTime))
        put("salt", salt)
    }

    /** The exact bytes that are hashed. */
    fun canonical(): String = CanonicalJson.encode(toJson())

    fun hash(): ByteArray = Hashes.sha256(canonical())

    companion object {
        const val EVENT = "release"

        fun parse(json: String): FinalPayClaim {
            val obj = Json.parseToJsonElement(json).jsonObject
            fun text(key: String) = obj.getValue(key).jsonPrimitive.content
            require(text("event") == EVENT) { "Not a final-pay claim" }
            return FinalPayClaim(
                version = obj.getValue("v").jsonPrimitive.int,
                trip = text("trip"),
                asset = text("asset"),
                deviceTime = Instant.parse(text("t_device")),
                salt = text("salt"),
            )
        }
    }
}
