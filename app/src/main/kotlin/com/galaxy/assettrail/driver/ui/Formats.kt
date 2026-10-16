package com.galaxy.assettrail.driver.ui

import com.galaxy.assettrail.core.model.Usdc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Formats {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val timeSeconds = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)
    private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** Average road speed used for ETAs between waypoints. */
    private const val AVERAGE_SPEED_KMH = 45.0

    fun time(millis: Long): String = time.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun timeSeconds(millis: Long): String = timeSeconds.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun window(start: Long, end: Long) = "${time(start)}–${time(end)}"

    fun dayTime(millis: Long): String = dayTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "Today 11:52", "Yesterday 18:03", or "Mon 13 Oct". */
    fun relative(millis: Long, now: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today ${time(millis)}"
            today.minusDays(1) -> "Yesterday ${time(millis)}"
            else -> day.format(date)
        }
    }

    fun isToday(millis: Long, now: Long): Boolean =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)

    fun usdc(amount: Usdc) = amount.display()

    /** "+15.00" or "−60.00" (a real minus sign, as the designs use). */
    fun signed(amount: Usdc, incoming: Boolean) = (if (incoming) "+" else "−") + amount.display()

    fun distance(metres: Int): String = when {
        metres < 1_000 -> "$metres m"
        metres < 10_000 -> "%.1f km".format(Locale.UK, metres / 1000.0)
        else -> "${(metres / 1000.0).roundToInt()} km"
    }

    fun eta(metres: Int, now: Long): String {
        val minutes = (metres / 1000.0) / AVERAGE_SPEED_KMH * 60
        return time(now + (minutes * 60_000).toLong())
    }

    /** "024 ••• 4471". */
    fun maskedPhone(phone: String): String {
        val digits = phone.filter(Char::isDigit).let { if (it.startsWith("233")) "0" + it.drop(3) else it }
        return if (digits.length < 7) digits else "${digits.take(3)} ••• ${digits.takeLast(4)}"
    }

    fun shortHash(hex: String): String = if (hex.length <= 8) hex else hex.take(4) + "…" + hex.takeLast(4)

    fun secondsLeft(until: Long, now: Long): Long = ((until - now) / 1000).coerceAtLeast(0)
}
