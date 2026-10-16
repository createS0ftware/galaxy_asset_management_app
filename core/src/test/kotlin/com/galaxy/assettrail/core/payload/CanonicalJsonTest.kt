package com.galaxy.assettrail.core.payload

import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalJsonTest {

    private fun canonical(json: String) = CanonicalJson.encode(Json.parseToJsonElement(json))

    /** Builds JSON unicode escapes at runtime, so the test source holds no escape sequences. */
    private fun esc(hex: String) = "\\" + "u" + hex

    @Test
    fun `RFC 8785 sample canonicalises exactly`() {
        val input = "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001]," +
            "\"string\":\"" + esc("20ac") + "$" + esc("000F") + esc("000a") + "A'" + esc("0042") + esc("0022") +
            esc("005c") + "\\\\\\\"\\/\"," +
            "\"literals\":[null,true,false]}"
        val expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]," +
            "\"string\":\"" + Char(0x20AC) + "$" + esc("000f") + "\\nA'B\\\"\\\\\\\\\\\"/\"}"
        assertEquals(expected, canonical(input))
    }

    @Test
    fun `keys sort by UTF-16 code units at every level`() {
        val emoji = String(Character.toChars(0x1F600)) // surrogate pair D83D DE00
        val dalet = Char(0xFB33).toString()
        val eAcute = Char(0xE9).toString()
        val input = "{\"b\":1,\"a\":{\"z\":true,\"$eAcute\":2,\"A\":3},\"10\":0,\"1\":0,\"$emoji\":1,\"$dalet\":2}"
        // In UTF-16 the emoji's high surrogate (D83D) sorts below U+FB33.
        val expected = "{\"1\":0,\"10\":0,\"a\":{\"A\":3,\"z\":true,\"$eAcute\":2},\"b\":1,\"$emoji\":1,\"$dalet\":2}"
        assertEquals(expected, canonical(input))
    }

    @Test
    fun `arrays keep their order and whitespace disappears`() {
        assertEquals("""[3,1,{"a":[]},"x"]""", canonical(""" [ 3 , 1 , { "a" : [ ] } , "x" ] """))
    }

    @Test
    fun `control characters are escaped, other text is literal`() {
        val lineSeparator = Char(0x2028).toString()
        val cedi = Char(0x20B5).toString()
        val input = "\"tab\\tbell" + esc("0007") + " quote\\\" slash\\/ line" + esc("2028") + " gh" + esc("20b5") + "\""
        val expected = "\"tab\\tbell" + esc("0007") + " quote\\\" slash/ line" + lineSeparator + " gh" + cedi + "\""
        assertEquals(expected, canonical(input))
    }

    @Test
    fun `numbers follow ECMAScript Number toString`() {
        val cases = mapOf(
            0.0 to "0",
            -0.0 to "0",
            8.0 to "8",
            6.6167 to "6.6167",
            -1.2167 to "-1.2167",
            4.5 to "4.5",
            0.1 to "0.1",
            0.000001 to "0.000001",
            1e-7 to "1e-7",
            1e20 to "100000000000000000000",
            1e21 to "1e+21",
            9007199254740992.0 to "9007199254740992",
            1.7976931348623157e308 to "1.7976931348623157e+308",
            java.lang.Double.MIN_VALUE to "5e-324",
            -java.lang.Double.MIN_VALUE to "-5e-324",
            295147905179352830000.0 to "295147905179352830000",
            1e23 to "1e+23",
            333333333.3333333 to "333333333.3333333",
            1424953923781206.2 to "1424953923781206.2",
            -0.0000033333333333333333 to "-0.0000033333333333333333",
        )
        cases.forEach { (value, expected) -> assertEquals(expected, CanonicalJson.formatNumber(value), "for $value") }
    }

    @Test
    fun `shortest digits agree with the JDK on random doubles`() {
        // Since JDK 19, Double.toString returns the shortest round-tripping digits (but never fewer
        // than two, so 5e-324 prints as 4.9E-324 there). JCS needs exactly the shortest.
        val random = Random(8785)
        repeat(20_000) {
            val value = Double.fromBits(random.nextLong())
            if (!value.isFinite() || value == 0.0) return@repeat
            val ours = BigDecimal(CanonicalJson.formatNumber(value).replace("e+", "e"))
            val jdk = BigDecimal(value.toString())
            assertEquals(value, ours.toDouble(), "round-trip for $value")
            if (ours.stripTrailingZeros().precision() == 1) {
                assertTrue(jdk.stripTrailingZeros().precision() <= 2, "length for $value")
            } else {
                assertEquals(0, ours.compareTo(jdk), "digits for $value")
            }
        }
    }
}
