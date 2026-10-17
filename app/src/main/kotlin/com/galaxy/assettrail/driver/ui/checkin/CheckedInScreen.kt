package com.galaxy.assettrail.driver.ui.checkin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.assettrail.core.driver.ActiveTrip
import com.galaxy.assettrail.core.driver.CheckIn
import com.galaxy.assettrail.core.driver.CheckInStatus
import com.galaxy.assettrail.core.driver.LegPayStatus
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.core.payload.WaypointPayload
import com.galaxy.assettrail.driver.ui.Amount
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.CheckLine
import com.galaxy.assettrail.driver.ui.CheckState
import com.galaxy.assettrail.driver.ui.DividerLine
import com.galaxy.assettrail.driver.ui.Eyebrow
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.IconCircle
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

/** Checked in (DriverCheckin artboard), plus its saved-offline and refused states. */
@Composable
fun CheckedInScreen(waypointIndex: Int, onBack: () -> Unit, onOpenWallet: () -> Unit) {
    val container = LocalContainer.current
    val state by container.repository.state.collectAsStateWithLifecycle()
    val online by container.online.collectAsStateWithLifecycle()
    var showRecord by remember { mutableStateOf(false) }

    val trip = state.trip
    val checkIn = trip?.checkIns?.lastOrNull { it.waypointIndex == waypointIndex }
    if (trip == null || checkIn == null) {
        ScreenColumn {
            BackLink("Trip", onBack)
            ScreenTitle("This trip has finished")
        }
        return
    }
    val waypoint = trip.plan.waypoints[waypointIndex]
    val next = trip.plan.waypoints.getOrNull(waypointIndex + 1)

    ScreenColumn(spacing = 18.dp) {
        BackLink("Trip", onBack)
        Header(checkIn, waypoint.name, waypoint.type, online)

        Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spacing = 0.dp) {
            CheckLine(CheckState.DONE, "Asset tag matched", trailing = trip.plan.asset.id, trailingMono = true, divider = false)
            CheckLine(
                CheckState.DONE,
                "${checkIn.distanceFromCentreM} m from the centre",
                trailing = "${Formats.distance(checkIn.distanceFromCentreM)} from centre",
            )
            val photos = WaypointPayload.parse(checkIn.payloadJson).photos
            if (photos.isNotEmpty()) {
                CheckLine(CheckState.DONE, "Load photographed", trailing = Formats.shortHash(photos.first().removePrefix("sha256:")), trailingMono = true)
            }
            when (checkIn.status) {
                CheckInStatus.RECORDED -> CheckLine(
                    CheckState.DONE,
                    "Recorded on the ledger",
                    trailing = checkIn.txHash?.let(Formats::shortHash),
                    trailingMono = true,
                    onTrailingClick = { showRecord = true },
                )
                CheckInStatus.QUEUED -> CheckLine(CheckState.PENDING, "Recorded on the ledger", trailing = if (online) "Recording…" else "Waiting for signal")
                CheckInStatus.FAILED -> CheckLine(CheckState.FAILED, "Recorded on the ledger", trailing = "Refused")
            }
        }

        PayPanel(trip, checkIn, waypoint.type, state.balance.display(), next?.name)

        if (next != null && checkIn.status != CheckInStatus.FAILED) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                Eyebrow("Next")
                val km = Formats.distance(Geo.distanceRoundedM(waypoint.position, next.position))
                Text("${next.name} · $km", style = Type.Subheading, color = Palette.Text)
                if (next.type == WaypointType.DELIVERY) {
                    Text(
                        "Your final ${next.legPay.display()} USDC is locked in the delivery contract and paid the moment the receiver signs.",
                        style = Type.Small.copy(lineHeight = Type.Body.lineHeight),
                        color = Palette.TextSecondary,
                    )
                } else {
                    Text("Window ${Formats.window(next.windowStart, next.windowEnd)} · pays ${next.legPay.display()} USDC", style = Type.Small, color = Palette.TextSecondary)
                }
            }
        }

        PrimaryButton(
            text = if (next != null && checkIn.status != CheckInStatus.FAILED) "Continue to ${next.name}" else "Back to trip",
            onClick = onBack,
        )
        SecondaryButton("View wallet", onClick = onOpenWallet, minHeight = 52.dp)
    }

    if (showRecord) LedgerRecordSheet(checkIn, container.productName, container.isDemo) { showRecord = false }
}

@Composable
private fun Header(checkIn: CheckIn, name: String, type: WaypointType, online: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (checkIn.status) {
            CheckInStatus.RECORDED -> {
                IconCircle(AppIcons.Check, tint = Palette.Ledger, fill = Palette.LedgerBadge)
                ScreenTitle(
                    when (type) {
                        WaypointType.PICKUP -> "Picked up at $name"
                        WaypointType.CHECKPOINT -> "Checked in at $name"
                        WaypointType.DELIVERY -> "Arrived at $name"
                    },
                )
                val recorded = "Recorded on the ledger · ${Formats.time(checkIn.ledgerTime ?: checkIn.deviceTime)}"
                Text(
                    if (checkIn.savedOffline) "$recorded · saved offline at ${Formats.time(checkIn.deviceTime)}" else recorded,
                    style = Type.Body,
                    color = Palette.TextSecondary,
                )
            }
            CheckInStatus.QUEUED -> {
                IconCircle(AppIcons.Clock, tint = Palette.LedgerSoft, fill = Palette.SurfaceRaised)
                ScreenTitle("Saved at $name")
                Text(
                    if (online) "Saved — recording on the ledger now" else "Saved — will record when online",
                    style = Type.Body,
                    color = Palette.TextSecondary,
                )
            }
            CheckInStatus.FAILED -> {
                IconCircle(AppIcons.Alert, tint = Palette.Alert, fill = Palette.AlertFill)
                ScreenTitle("Not recorded at $name")
                Text(checkIn.failureMessage ?: "The platform refused this check-in.", style = Type.Body, color = Palette.Alert)
            }
        }
    }
}

@Composable
private fun PayPanel(trip: ActiveTrip, checkIn: CheckIn, type: WaypointType, balance: String, nextName: String?) {
    if (checkIn.status == CheckInStatus.FAILED) return
    Panel(spacing = 4.dp) {
        when {
            type == WaypointType.PICKUP -> {
                Text("Custody started", style = Type.Small, color = Palette.TextSecondary)
                Text("No pay at pickup", style = Type.Subheading, color = Palette.Text)
                Text("Your first leg pays when you check in at ${nextName ?: "the next stop"}.", style = Type.Small, color = Palette.TextSecondary)
            }
            type == WaypointType.DELIVERY -> {
                Text("Your final leg is in the delivery contract", style = Type.Small, color = Palette.TextSecondary)
                Amount(checkIn.legPay, Type.MonoLarge, Palette.Text, suffix = "USDC")
                Text("Paid the moment ${trip.plan.receiverName} signs.", style = Type.Small, color = Palette.TextSecondary)
            }
            checkIn.pay == LegPayStatus.PAID -> {
                Text("Leg ${checkIn.waypointIndex} pay is in your wallet", style = Type.Small, color = Palette.TextSecondary)
                Text("+${checkIn.legPay.display()} USDC", style = Type.MonoLarge, color = Palette.LedgerSoft)
                Text("Wallet balance $balance USDC", style = Type.Body.copy(fontSize = Type.Small.fontSize.times(1.08f)), color = Palette.TextSecondary)
            }
            else -> {
                Text("Leg ${checkIn.waypointIndex} pay arrives when this records", style = Type.Small, color = Palette.TextSecondary)
                Amount(checkIn.legPay, Type.MonoLarge, Palette.TextSecondary, suffix = "USDC")
                Text("The contract pays it in the same call that records your location.", style = Type.Small, color = Palette.TextSecondary)
            }
        }
    }
}

/** The technical record behind a check-in, for anyone who asks to see it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerRecordSheet(checkIn: CheckIn, productName: String, isDemo: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ledger record", style = Type.Subheading, color = Palette.Text)
            RecordField("Transaction", checkIn.txHash.orEmpty(), context)
            RecordField("Recorded hash (of the check-in record)", checkIn.payloadHash, context)
            DividerLine(Palette.Border)
            RecordLine("Phone time", Formats.timeSeconds(checkIn.deviceTime))
            checkIn.ledgerTime?.let { RecordLine("Ledger time", Formats.timeSeconds(it)) }
            RecordLine("Authorized by", "Your wallet, then $productName")
            RecordLine("Network fee", "Paid by $productName")
            if (isDemo) {
                Text(
                    "Demo build: the authorization is real and signed on this phone, but the simulated platform doesn't submit it to a Stellar network.",
                    style = Type.Small,
                    color = Palette.Demo,
                )
            }
        }
    }
}

@Composable
private fun RecordField(label: String, value: String, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Type.Small, color = Palette.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = Type.MonoSmall, color = Palette.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { copy(context, label, value) }) {
                Icon(AppIcons.Copy, contentDescription = "Copy $label", tint = Palette.Ledger, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun RecordLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.Small, color = Palette.TextSecondary)
        Text(value, style = Type.Small, color = Palette.Text)
    }
}

fun copy(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
}
