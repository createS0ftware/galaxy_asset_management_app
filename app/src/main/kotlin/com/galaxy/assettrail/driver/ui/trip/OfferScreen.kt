package com.galaxy.assettrail.driver.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.driver.ui.Amount
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.Badge
import com.galaxy.assettrail.driver.ui.Eyebrow
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.KeyValue
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SectionHeading
import com.galaxy.assettrail.driver.ui.StopMark
import com.galaxy.assettrail.driver.ui.StopRow
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

/** Trips › Offer (spec §5.2 stage 2): total and per-leg pay with a "Funded" badge before Accept. */
@Composable
fun OfferScreen(tripId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: TripViewModel = viewModel(key = "offer-$tripId") { TripViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    MessageEffect(viewModel)

    val plan = state.offers.firstOrNull { it.id == tripId }
    ScreenColumn {
        BackLink("Trips", onBack)
        if (plan == null) {
            ScreenTitle("Offer not available")
            Text("This trip was taken or withdrawn.", style = Type.Body, color = Palette.TextSecondary)
            return@ScreenColumn
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("Trip offer · ${plan.id}")
            ScreenTitle(plan.routeLabel)
            Text("${plan.asset.name} · ${plan.asset.id}", style = Type.Body, color = Palette.TextSecondary)
        }

        Panel(spacing = 6.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Total pay", style = Type.Small, color = Palette.TextSecondary)
                    Amount(plan.legTotal, Type.MonoMedium, suffix = "USDC")
                }
                Badge("Funded")
            }
            Text(
                "Leg pay is already held for this trip, and each leg pays the moment you check in. " +
                    "The final ${plan.delivery.legPay.display()} USDC is locked in the delivery contract and paid when the receiver signs.",
                style = Type.Small,
                color = Palette.TextSecondary,
            )
        }

        Column {
            SectionHeading("Route", "${plan.waypoints.size} stops")
            plan.waypoints.forEach { waypoint ->
                StopRow(
                    mark = StopMark.UPCOMING,
                    title = waypoint.name,
                    subtitle = when (waypoint.type) {
                        WaypointType.PICKUP -> "Pickup"
                        WaypointType.CHECKPOINT -> "Checkpoint"
                        WaypointType.DELIVERY -> "Delivery · paid on signature"
                    } + " · " + Formats.window(waypoint.windowStart, waypoint.windowEnd) + " · ${waypoint.geofenceRadiusM} m",
                    amount = if (waypoint.type == WaypointType.PICKUP) "—" else waypoint.legPay.display(),
                )
            }
        }

        Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
            KeyValue("Fuel advance", "up to ${AdvanceRules.cap(plan).display()} USDC", divider = false)
            KeyValue("Deliver by", Formats.dayTime(plan.delivery.windowEnd), mono = false)
            KeyValue("Escrow funded", Formats.shortHash(plan.fundingTx))
        }

        PrimaryButton("Accept trip", onClick = { viewModel.accept(plan.id, onBack) }, busy = busy)
    }
}
