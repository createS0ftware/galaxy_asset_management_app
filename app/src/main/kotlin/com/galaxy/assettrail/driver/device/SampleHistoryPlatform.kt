package com.galaxy.assettrail.driver.device

import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.ActivityKind
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.platform.DriverSnapshot
import com.galaxy.assettrail.core.platform.PlatformApi

/**
 * Debuggable live builds: earlier trips and payouts in the wallet's activity, so a walkthrough
 * doesn't start from an empty history. Display only: these items have no transactions behind them.
 */
class SampleHistoryPlatform(private val delegate: PlatformApi) : PlatformApi by delegate {

    override suspend fun snapshot(driverId: String, activeTripId: String?): DriverSnapshot {
        val live = delegate.snapshot(driverId, activeTripId)
        return live.copy(activity = live.activity + sample(live.serverTime))
    }

    private fun sample(now: Long): List<ActivityItem> {
        fun item(daysAgo: Int, hour: Int, kind: ActivityKind, title: String, amount: String, trip: String?, incoming: Boolean = true) =
            ActivityItem(
                id = "sample-$daysAgo-$hour",
                kind = kind,
                title = title,
                amount = Usdc.of(amount),
                incoming = incoming,
                time = now - daysAgo * DAY - hour * HOUR,
                tripId = trip,
                note = "Earlier trip",
            )
        return listOf(
            item(2, 1, ActivityKind.FINAL_PAY, "Final leg · Tema Port · Gate 3", "26.00", "TR-2040"),
            item(2, 3, ActivityKind.LEG_PAY, "Leg 3 · Nsawam", "22.00", "TR-2040"),
            item(2, 5, ActivityKind.LEG_PAY, "Leg 2 · Nkawkaw", "20.00", "TR-2040"),
            item(2, 7, ActivityKind.LEG_PAY, "Leg 1 · Konongo", "16.00", "TR-2040"),
            item(3, 2, ActivityKind.CASH_OUT, "Cash out · MTN MoMo ••0077", "60.00", null, incoming = false),
            item(4, 1, ActivityKind.FINAL_PAY, "Final leg · Kumasi · Warehouse B", "25.00", "TR-2039"),
            item(4, 4, ActivityKind.LEG_PAY, "Leg 3 · Konongo", "15.00", "TR-2039"),
            item(4, 6, ActivityKind.LEG_PAY, "Leg 2 · Nkawkaw", "22.00", "TR-2039"),
            item(4, 8, ActivityKind.LEG_PAY, "Leg 1 · Nsawam", "18.00", "TR-2039"),
        )
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
