package com.galaxy.assettrail.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A USDC amount in Stellar's 7-decimal units (1 USDC = 10,000,000 units).
 * Kept as a whole number so pay arithmetic never drifts.
 */
@Serializable
@JvmInline
value class Usdc(val units: Long) : Comparable<Usdc> {

    operator fun plus(other: Usdc) = Usdc(Math.addExact(units, other.units))

    operator fun minus(other: Usdc) = Usdc(Math.subtractExact(units, other.units))

    override fun compareTo(other: Usdc) = units.compareTo(other.units)

    val isPositive: Boolean get() = units > 0

    /** Two decimals, as the screens show it: "15.00". */
    fun display(): String = toBigDecimal().setScale(2, RoundingMode.DOWN).toPlainString()

    /** Seven decimals, as Stellar operations take it: "15.0000000". */
    fun toStellarAmount(): String = toBigDecimal().toPlainString()

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(units, SCALE)

    fun coerceAtLeastZero(): Usdc = if (units < 0) ZERO else this

    companion object {
        const val SCALE = 7
        val ZERO = Usdc(0)

        fun of(amount: String): Usdc = of(BigDecimal(amount.trim()))

        fun of(amount: BigDecimal): Usdc =
            Usdc(amount.setScale(SCALE, RoundingMode.DOWN).unscaledValue().longValueExact())

        /** Parses user input; null when it isn't a positive amount with at most 7 decimals. */
        fun parseOrNull(input: String): Usdc? {
            val text = input.trim().replace(",", "")
            if (text.isEmpty()) return null
            val value = text.toBigDecimalOrNull() ?: return null
            if (value.signum() <= 0 || value.scale() > SCALE) return null
            return runCatching { of(value) }.getOrNull()
        }
    }
}

fun Iterable<Usdc>.sum(): Usdc = fold(Usdc.ZERO) { total, amount -> total + amount }
