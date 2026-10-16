package com.galaxy.assettrail.driver.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.ActiveTrip
import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.driver.CheckInStatus
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.LegPayStatus
import com.galaxy.assettrail.core.driver.LegState
import com.galaxy.assettrail.core.driver.approvedOverrideFor
import com.galaxy.assettrail.core.driver.canClaimFinalPay
import com.galaxy.assettrail.core.driver.checkInAt
import com.galaxy.assettrail.core.driver.earned
import com.galaxy.assettrail.core.driver.latestFailureAt
import com.galaxy.assettrail.core.driver.legRows
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.driver.pendingCheckIns
import com.galaxy.assettrail.core.driver.pendingOverrideFor
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Waypoint
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.driver.ui.Amount
import com.galaxy.assettrail.driver.ui.Badge
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.ConnectionStatus
import com.galaxy.assettrail.driver.ui.DemoChip
import com.galaxy.assettrail.driver.ui.Eyebrow
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.IconCircle
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.SectionHeading
import com.galaxy.assettrail.driver.ui.StopMark
import com.galaxy.assettrail.driver.ui.StopRow
import com.galaxy.assettrail.driver.ui.TextAction
import com.galaxy.assettrail.driver.ui.demo.DemoControlsSheet
import com.galaxy.assettrail.driver.ui.rememberNow
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

/** Trip tab: offers, the active trip (DriverTrip artboard), delivery and completion. */
@Composable
fun TripScreen(
    onOpenOffer: (String) -> Unit,
    onCheckIn: (Int) -> Unit,
    onOpenCheckIn: (Int) -> Unit,
    onRequestAdvance: () -> Unit,
    onOpenWallet: () -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: TripViewModel = viewModel { TripViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val now = rememberNow()
    var showDemo by remember { mutableStateOf(false) }
    MessageEffect(viewModel)

    val trip = state.trip
    when {
        trip == null -> NoTrip(state, online, viewModel.isDemo, onOpenOffer, onShowDemo = { showDemo = true })
        trip.closed -> TripComplete(trip, onDone = viewModel::finishTrip, onOpenWallet = onOpenWallet)
        else -> ActiveTripContent(
            state = state,
            trip = trip,
            online = online,
            fix = fix,
            now = now,
            busy = busy,
            viewModel = viewModel,
            onCheckIn = onCheckIn,
            onOpenCheckIn = onOpenCheckIn,
            onRequestAdvance = onRequestAdvance,
            onOpenWallet = onOpenWallet,
            onShowDemo = { showDemo = true },
        )
    }
    if (showDemo) DemoControlsSheet(onDismiss = { showDemo = false })
}

@Composable
private fun NoTrip(
    state: DriverState,
    online: Boolean,
    isDemo: Boolean,
    onOpenOffer: (String) -> Unit,
    onShowDemo: () -> Unit,
) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Trips")
            ConnectionStatus(online)
        }
        if (state.offers.isEmpty()) {
            Panel(spacing = 6.dp) {
                Text("No trips right now", style = Type.BodyStrong, color = Palette.Text)
                Text("We'll text you when a trip is offered.", style = Type.Small, color = Palette.TextSecondary)
            }
        } else {
            state.offers.forEach { OfferCard(it) { onOpenOffer(it.id) } }
        }
        if (isDemo) DemoChip("Demo controls", onClick = onShowDemo)
    }
}

@Composable
private fun OfferCard(plan: TripPlan, onClick: () -> Unit) {
    Panel(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "View offer", onClick = onClick),
        spacing = 8.dp,
    ) {
        Eyebrow("New trip offer · ${plan.id}")
        Text(plan.routeLabel, style = Type.Subheading, color = Palette.Text)
        Text("${plan.asset.name} · ${plan.asset.id}", style = Type.Small, color = Palette.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Amount(plan.legTotal, Type.MonoMedium, suffix = "USDC")
            Badge("Funded")
        }
        Text(
            "${plan.waypoints.size - 1} paid legs · pickup ${Formats.window(plan.pickup.windowStart, plan.pickup.windowEnd)}",
            style = Type.Small,
            color = Palette.TextSecondary,
        )
        Text("View offer ›", style = Type.ButtonSecondary, color = Palette.Ledger)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveTripContent(
    state: DriverState,
    trip: ActiveTrip,
    online: Boolean,
    fix: GpsFix?,
    now: Long,
    busy: Boolean,
    viewModel: TripViewModel,
    onCheckIn: (Int) -> Unit,
    onOpenCheckIn: (Int) -> Unit,
    onRequestAdvance: () -> Unit,
    onOpenWallet: () -> Unit,
    onShowDemo: () -> Unit,
) {
    val plan = trip.plan
    val next = trip.nextWaypoint()
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${plan.id} · ${plan.asset.shortName} ${plan.asset.id}", style = Type.Small, color = Palette.TextSecondary)
            ConnectionStatus(online)
        }

        QueueBanner(state, online)

        if (next != null) {
            NextStop(trip, next, fix, now, busy, viewModel, onCheckIn)
            if (viewModel.isDemo) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val inside = fix != null && Geo.distanceM(fix.point, next.position) <= next.geofenceRadiusM
                    if (inside) {
                        DemoChip("On the road", onClick = viewModel::demoOnTheRoad)
                    } else {
                        DemoChip("Drive to ${next.name}", onClick = viewModel::demoArrive)
                        DemoChip("Just outside", onClick = viewModel::demoJustOutside)
                    }
                    DemoChip("More", onClick = onShowDemo)
                }
            }
        } else {
            DeliveryStatus(trip, now, busy, viewModel)
            if (viewModel.isDemo) DemoChip("Receiver and ops controls", onClick = onShowDemo)
        }

        AdvanceNotice(trip, onOpenWallet)

        LegList(trip, onOpenCheckIn)

        val available = AdvanceRules.available(plan, trip.advances)
        if (next != null && available.isPositive) {
            SecondaryButton("Need fuel money? Request an advance", onClick = onRequestAdvance)
        }
    }
}

@Composable
private fun QueueBanner(state: DriverState, online: Boolean) {
    val pending = state.pendingCheckIns().size
    when {
        pending > 0 && !online -> Banner(
            title = if (pending == 1) "1 check-in saved" else "$pending check-ins saved",
            text = "Signed on this phone. They'll record on the ledger, in order, as soon as you have signal.",
            tone = BannerTone.PENDING,
        )
        pending > 0 && state.queueNotice != null -> Banner(
            title = if (pending == 1) "1 check-in waiting" else "$pending check-ins waiting",
            text = state.queueNotice.orEmpty(),
            tone = BannerTone.PENDING,
        )
        pending > 0 -> Banner(text = "Recording saved check-ins…", tone = BannerTone.PENDING)
    }
}

@Composable
private fun NextStop(
    trip: ActiveTrip,
    next: Waypoint,
    fix: GpsFix?,
    now: Long,
    busy: Boolean,
    viewModel: TripViewModel,
    onCheckIn: (Int) -> Unit,
) {
    val distance = fix?.let { Geo.distanceRoundedM(it.point, next.position) }
    val inside = distance != null && distance <= next.geofenceRadiusM
    val approvedOverride = trip.approvedOverrideFor(next.index)
    val pendingOverride = trip.pendingOverrideFor(next.index)
    val mock = fix?.isMock == true
    val canCheckIn = fix != null && !mock && (inside || approvedOverride != null)
    var confirmOverride by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(
            when (next.type) {
                WaypointType.PICKUP -> "Pick up"
                WaypointType.CHECKPOINT -> "Next stop"
                WaypointType.DELIVERY -> "Deliver to"
            },
        )
        Text(
            if (next.type == WaypointType.CHECKPOINT) "${next.name} checkpoint" else next.name,
            style = Type.Display,
            color = Palette.Text,
        )
        val parts = buildList {
            when {
                distance == null -> add("Finding your location…")
                inside -> add("You're here")
                else -> add(Formats.distance(distance))
            }
            add("window ${Formats.window(next.windowStart, next.windowEnd)}")
            if (distance != null && !inside) add("ETA ${Formats.eta(distance, now)}")
        }
        Text(parts.joinToString(" · "), style = Type.Body, color = Palette.TextSecondary)
        if (now > next.windowEnd) {
            Text(
                "The window closed at ${Formats.time(next.windowEnd)}. Check in anyway; ops will see it was late.",
                style = Type.Small,
                color = Palette.EmberSoft,
            )
        }
    }

    PayCard(trip.plan, next)

    trip.latestFailureAt(next.index)?.let { failed ->
        Banner(
            title = "Check-in at ${next.name} wasn't recorded",
            text = failed.failureMessage ?: "The platform refused it.",
            tone = BannerTone.ALERT,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = when (next.type) {
                WaypointType.PICKUP -> "Pick up at ${next.name}"
                WaypointType.CHECKPOINT -> "Check in at ${next.name}"
                WaypointType.DELIVERY -> "Check in at ${next.name}"
            },
            onClick = { onCheckIn(next.index) },
            enabled = canCheckIn,
            icon = AppIcons.Pin,
            style = Type.Button.copy(fontSize = Type.Button.fontSize.times(1.06f)),
        )
        val helper = when {
            mock -> "Mock locations are turned on. Turn them off to check in."
            fix == null -> "Waiting for your location."
            !inside && approvedOverride != null ->
                "Ops allowed check-in here, ${Formats.distance(approvedOverride.distanceM)} from the centre."
            inside -> "You're inside the ${next.geofenceRadiusM} m geofence. Works without signal."
            else -> "Unlocks inside the ${next.geofenceRadiusM} m geofence. Works without signal."
        }
        Text(
            helper,
            style = Type.Small,
            color = if (mock) Palette.Alert else Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (fix != null && !inside && approvedOverride == null) {
        if (pendingOverride != null) {
            Banner(
                title = "Asked ops to allow check-in here",
                text = "They can see your GPS position (${Formats.distance(pendingOverride.distanceM)} from the centre). Waiting for their answer.",
                tone = BannerTone.PENDING,
            )
        } else if (distance != null && distance < 5_000) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextAction("At the stop but can't check in? Ask ops", onClick = { confirmOverride = true }, enabled = !busy)
            }
        }
    }

    if (confirmOverride && distance != null) {
        AlertDialog(
            onDismissRequest = { confirmOverride = false },
            containerColor = Palette.Surface,
            title = { Text("Ask ops to allow check-in?", style = Type.Subheading, color = Palette.Text) },
            text = {
                Text(
                    "Your GPS puts you ${Formats.distance(distance)} from ${next.name}, outside its ${next.geofenceRadiusM} m geofence. " +
                        "Ops gets your position as evidence. If they allow it, the override is recorded with the check-in.",
                    style = Type.Body,
                    color = Palette.TextLabel,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverride = false
                    viewModel.requestOverride()
                }) { Text("Send to ops", color = Palette.Ember, style = Type.ButtonSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverride = false }) { Text("Cancel", color = Palette.TextLabel, style = Type.ButtonSecondary) }
            },
        )
    }
}

@Composable
private fun PayCard(plan: TripPlan, next: Waypoint) {
    Panel(spacing = 2.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (next.type) {
                    WaypointType.PICKUP -> {
                        Text("Custody starts here", style = Type.Small, color = Palette.TextSecondary)
                        Text("Scan the tag, photograph the load", style = Type.BodyStrong, color = Palette.Text)
                    }
                    WaypointType.CHECKPOINT -> {
                        Text("Pays when you check in", style = Type.Small, color = Palette.TextSecondary)
                        Amount(next.legPay, Type.MonoMedium, suffix = "USDC")
                    }
                    WaypointType.DELIVERY -> {
                        Text("Paid when the receiver signs", style = Type.Small, color = Palette.TextSecondary)
                        Amount(next.legPay, Type.MonoMedium, suffix = "USDC")
                    }
                }
            }
            Badge(if (next.type == WaypointType.DELIVERY) "In contract" else "Funded")
        }
        if (next.type == WaypointType.PICKUP) {
            Text(
                "All ${plan.legTotal.display()} USDC for this trip is funded. The first leg pays at ${plan.waypoints.getOrNull(1)?.name}.",
                style = Type.Small,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DeliveryStatus(trip: ActiveTrip, now: Long, busy: Boolean, viewModel: TripViewModel) {
    val plan = trip.plan
    val arrival = trip.checkInAt(plan.delivery.index)
    val contract = trip.contract
    val deduction = AdvanceRules.deduction(trip.advances)
    val net = (contract.locked - deduction).coerceAtLeastZero()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(if (contract.status == ContractStatus.DISPUTED) "Problem reported" else "Delivered")
        Text(plan.delivery.name, style = Type.Display, color = Palette.Text)
        arrival?.let {
            val time = it.ledgerTime ?: it.deviceTime
            Text(
                if (it.status == CheckInStatus.RECORDED) "Arrival recorded on the ledger · ${Formats.time(time)}" else "Arrival saved on this phone · ${Formats.time(time)}",
                style = Type.Body,
                color = Palette.TextSecondary,
            )
        }
    }

    when {
        arrival?.status == CheckInStatus.QUEUED -> Banner(
            title = "Arrival saved, not recorded yet",
            text = "The receiver can sign once your arrival records. It will, as soon as you have signal.",
            tone = BannerTone.PENDING,
        )
        contract.status == ContractStatus.DISPUTED -> Banner(
            title = "The receiver reported a problem",
            text = "Your final ${contract.locked.display()} USDC stays in the delivery contract while ops reviews their photos. " +
                "Ops will let you know the outcome.",
            tone = BannerTone.ALERT,
        )
        else -> {
            Panel(spacing = 8.dp) {
                Text("Waiting for ${plan.receiverName} to sign", style = Type.BodyStrong, color = Palette.Text)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Amount(net, Type.MonoMedium, suffix = "USDC")
                    Badge("In contract")
                }
                if (deduction.isPositive) {
                    Text(
                        "${contract.locked.display()} final leg − ${deduction.display()} fuel advance",
                        style = Type.MonoSmall,
                        color = Palette.TextSecondary,
                    )
                }
                Text(
                    "Locked in the delivery contract and paid the moment they sign with their passkey. " +
                        "If nobody signs or reports a problem by ${Formats.dayTime(contract.deadline)}, you can claim it yourself.",
                    style = Type.Small,
                    color = Palette.TextSecondary,
                )
            }
            if (trip.canClaimFinalPay(now)) {
                PrimaryButton("Claim final pay · ${net.display()} USDC", onClick = viewModel::claimFinalPay, busy = busy)
            }
        }
    }
}

@Composable
private fun AdvanceNotice(trip: ActiveTrip, onOpenWallet: () -> Unit) {
    val advance = trip.advances.lastOrNull() ?: return
    when (advance.status) {
        AdvanceStatus.READY -> Banner(
            title = "Fuel advance ready · ${advance.amount.display()} USDC",
            text = "Claim it in your wallet by ${advance.claimDeadline?.let(Formats::dayTime) ?: "the deadline"}.",
            tone = BannerTone.INFO,
            icon = AppIcons.Wallet,
        ) { TextAction("Open wallet", onClick = onOpenWallet) }
        AdvanceStatus.REQUESTED -> Banner(
            title = "Advance requested · ${advance.amount.display()} USDC",
            text = "Waiting for ops to approve it.",
            tone = BannerTone.PENDING,
        )
        else -> Unit
    }
}

@Composable
private fun LegList(trip: ActiveTrip, onOpenCheckIn: (Int) -> Unit) {
    val plan = trip.plan
    Column {
        SectionHeading("This trip", "${trip.earned().display()} of ${plan.legTotal.display()} USDC earned")
        trip.legRows().forEach { row ->
            val waypoint = row.waypoint
            val checkIn = row.checkIn
            val failed = row.state == LegState.NEXT && trip.latestFailureAt(waypoint.index) != null
            val mark = when {
                failed -> StopMark.FAILED
                row.state == LegState.VERIFIED -> StopMark.VERIFIED
                row.state == LegState.SAVED -> StopMark.SAVED
                row.state == LegState.NEXT -> StopMark.NEXT
                else -> StopMark.UPCOMING
            }
            val subtitle = when (row.state) {
                LegState.VERIFIED -> {
                    val recorded = Formats.time(checkIn?.ledgerTime ?: 0)
                    val base = when (waypoint.type) {
                        WaypointType.PICKUP -> "Pickup · $recorded"
                        WaypointType.DELIVERY -> when (trip.contract.status) {
                            ContractStatus.DISPUTED -> "Delivered $recorded · problem reported"
                            ContractStatus.LOCKED -> "Delivered $recorded · waiting for signature"
                            else -> "Delivered $recorded · paid"
                        }
                        WaypointType.CHECKPOINT ->
                            if (checkIn?.savedOffline == true) "${Formats.time(checkIn.deviceTime)} · saved offline, recorded $recorded" else recorded
                    }
                    if (checkIn?.pay == LegPayStatus.PENDING_ESCROW) "$base · pay pending" else base
                }
                LegState.SAVED -> "Saved ${Formats.time(checkIn?.deviceTime ?: 0)} · records when online"
                LegState.NEXT -> if (failed) "Not recorded · try again" else "Next"
                LegState.UPCOMING -> when (waypoint.type) {
                    WaypointType.PICKUP -> "Pickup · ${Formats.window(waypoint.windowStart, waypoint.windowEnd)}"
                    WaypointType.CHECKPOINT -> "Window ${Formats.window(waypoint.windowStart, waypoint.windowEnd)}"
                    WaypointType.DELIVERY -> "Delivery · in contract until the receiver signs"
                }
            }
            val paidFinal = waypoint.type == WaypointType.DELIVERY && trip.contract.isPaidToDriver
            val amount = when {
                waypoint.type == WaypointType.PICKUP -> "—"
                paidFinal -> "+" + waypoint.legPay.display()
                row.state == LegState.VERIFIED && checkIn?.pay == LegPayStatus.PAID -> "+" + waypoint.legPay.display()
                else -> waypoint.legPay.display()
            }
            val amountColor = when {
                amount.startsWith("+") -> Palette.LedgerSoft
                row.state == LegState.VERIFIED || row.state == LegState.SAVED || waypoint.type == WaypointType.PICKUP -> Palette.TextSecondary
                else -> Palette.Text
            }
            val subtitleColor = when {
                failed -> Palette.Alert
                row.state == LegState.NEXT -> Palette.EmberSoft
                else -> Palette.TextSecondary
            }
            Column(
                if (checkIn != null) Modifier.clickable(role = Role.Button, onClickLabel = "View check-in") { onOpenCheckIn(waypoint.index) } else Modifier,
            ) {
                StopRow(
                    mark = mark,
                    title = waypoint.name,
                    subtitle = subtitle,
                    amount = amount,
                    subtitleColor = subtitleColor,
                    amountColor = amountColor,
                    emphasise = row.state == LegState.NEXT,
                )
            }
        }
    }
}

@Composable
private fun TripComplete(trip: ActiveTrip, onDone: () -> Unit, onOpenWallet: () -> Unit) {
    val contract = trip.contract
    ScreenColumn(spacing = 18.dp) {
        IconCircle(AppIcons.Check, tint = Palette.Ledger, fill = Palette.LedgerBadge)
        ScreenTitle("Trip complete")
        Text("${trip.plan.id} · ${trip.plan.routeLabel}", style = Type.Body, color = Palette.TextSecondary)
        Panel(spacing = 4.dp) {
            Text("Earned on this trip", style = Type.Small, color = Palette.TextSecondary)
            Amount(trip.earned(), Type.MonoLarge, Palette.LedgerSoft, suffix = "USDC")
            val how = when (contract.status) {
                ContractStatus.RELEASED_AFTER_DEADLINE -> "Final leg released after the deadline."
                ContractStatus.RESOLVED -> "Final leg paid after ops resolved the problem report."
                else -> "Final leg paid when ${trip.plan.receiverName} signed."
            }
            Text(how, style = Type.Small, color = Palette.TextSecondary)
            if (contract.deduction.isPositive) {
                Text(
                    "Final leg ${contract.locked.display()} − ${contract.deduction.display()} fuel advance = ${contract.payout?.display()} USDC",
                    style = Type.MonoSmall,
                    color = Palette.TextSecondary,
                )
            }
        }
        PrimaryButton("Done", onClick = onDone)
        SecondaryButton("View wallet", onClick = onOpenWallet, minHeight = 52.dp)
    }
}
