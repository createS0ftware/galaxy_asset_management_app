package com.galaxy.assettrail.driver.ui.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.canMoveMoney
import com.galaxy.assettrail.core.model.ActivityItem
import com.galaxy.assettrail.core.model.ActivityKind
import com.galaxy.assettrail.core.model.ActivityStatus
import com.galaxy.assettrail.core.model.AdvanceInfo
import com.galaxy.assettrail.core.model.AdvanceStatus
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.DividerLine
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.SectionHeading
import com.galaxy.assettrail.driver.ui.rememberNow
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import com.galaxy.assettrail.core.model.Usdc
import kotlinx.coroutines.flow.StateFlow

class WalletViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository
    val state: StateFlow<DriverState> = repository.state
    val online: StateFlow<Boolean> = container.online
    val productName = container.productName

    fun claim(advanceId: String) = action {
        repository.claimAdvance(advanceId)
        say("Advance claimed. It's in your balance.")
    }

    fun requestAdvance(amount: Usdc, onDone: () -> Unit) = action(onDone) {
        repository.requestAdvance(amount)
    }
}

/** Wallet (DriverWallet artboard): balance, cash out, advances, activity. */
@Composable
fun WalletScreen(onCashOut: () -> Unit, onRequestAdvance: () -> Unit, onOpenCashOut: (String) -> Unit) {
    val container = LocalContainer.current
    val viewModel: WalletViewModel = viewModel { WalletViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    MessageEffect(viewModel)

    val trip = state.trip
    val canMoveMoney = state.canMoveMoney()
    val advanceAvailable = trip?.takeIf { !it.closed }?.let { AdvanceRules.available(it.plan, it.advances) } ?: Usdc.ZERO

    ScreenColumn {
        ScreenTitle("Wallet")

        Panel(spacing = 4.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
            Text("Balance", style = Type.Small, color = Palette.TextSecondary)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.balance.display(), style = Type.MonoHero, color = Palette.Text)
                Text("USDC", style = Type.MonoBody.copy(fontSize = Type.Subheading.fontSize), color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("Shown in cedis at the payout partner's rate when you cash out.", style = Type.Small, color = Palette.TextSecondary)
            if (!online) {
                Text(
                    "No signal · balance as of ${state.balanceAsOf?.let { Formats.relative(it, now) } ?: "your last sync"}",
                    style = Type.Small,
                    color = Palette.EmberSoft,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                "Cash out to MoMo",
                onClick = onCashOut,
                enabled = state.balance.isPositive && canMoveMoney,
                modifier = Modifier.weight(1f),
                style = Type.Button.copy(fontSize = Type.Body.fontSize.times(1.07f)),
                minHeight = 52.dp,
            )
            SecondaryButton(
                "Request advance",
                onClick = onRequestAdvance,
                enabled = advanceAvailable.isPositive,
                modifier = Modifier.weight(1f),
                minHeight = 52.dp,
                style = Type.ButtonSecondary.copy(fontSize = Type.Body.fontSize.times(1.07f)),
            )
        }

        if (!canMoveMoney) {
            Banner(
                "Cash out unlocks once your saved check-ins are recorded, so it pays from a settled balance.",
                tone = BannerTone.PENDING,
            )
        } else if (state.queueNotice != null && state.queue.isNotEmpty()) {
            Banner(state.queueNotice!!, tone = BannerTone.PENDING)
        }

        trip?.advances?.lastOrNull()?.let { AdvanceCard(it, trip.plan.id, online, busy, viewModel) }

        Activity(state.activity, now, onOpenCashOut)

        Text(
            "Network fees are paid by ${viewModel.productName}. You never need XLM.",
            style = Type.Small,
            color = Palette.TextSecondary,
        )
    }
}

@Composable
private fun AdvanceCard(
    advance: AdvanceInfo,
    tripId: String,
    online: Boolean,
    busy: Boolean,
    viewModel: WalletViewModel,
) {
    when (advance.status) {
        AdvanceStatus.READY -> Panel(fill = Palette.AdvanceFill, border = Palette.AdvanceBorder, spacing = 8.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Fuel advance ready", style = Type.BodyStrong.copy(fontWeight = FontWeight.Bold), color = Palette.Text)
                Text("${advance.amount.display()} USDC", style = Type.MonoBody.copy(fontSize = Type.Heading.fontSize), color = Palette.Text)
            }
            Text(
                "Claim by ${advance.claimDeadline?.let(Formats::dayTime) ?: "the deadline"}. It comes off your final leg pay on $tripId.",
                style = Type.Small.copy(lineHeight = Type.Body.lineHeight),
                color = Palette.AdvanceText,
            )
            PrimaryButton(
                "Claim advance",
                onClick = { viewModel.claim(advance.id) },
                enabled = online,
                busy = busy,
                minHeight = 44.dp,
                style = Type.ButtonSecondary.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth(0.55f),
            )
            if (!online) Text("Claiming needs signal.", style = Type.Caption, color = Palette.AdvanceText)
        }
        AdvanceStatus.REQUESTED -> Banner(
            title = "Advance requested · ${advance.amount.display()} USDC",
            text = "Waiting for ops. Once approved you'll have 48 hours to claim it here.",
            tone = BannerTone.PENDING,
        )
        AdvanceStatus.DECLINED -> Banner(
            title = "Advance declined",
            text = advance.declineReason ?: "Ops declined this advance.",
            tone = BannerTone.ALERT,
        )
        AdvanceStatus.EXPIRED -> Banner(
            title = "Advance expired",
            text = "It wasn't claimed in time, so it went back to the trip.",
            tone = BannerTone.INFO,
        )
        AdvanceStatus.CLAIMED -> Unit
    }
}

@Composable
private fun Activity(items: List<ActivityItem>, now: Long, onOpenCashOut: (String) -> Unit) {
    Column {
        SectionHeading("Activity")
        if (items.isEmpty()) {
            DividerLine()
            Text(
                "Leg pay, advances and cash-outs show up here.",
                style = Type.Small,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        items.forEach { item ->
            val cashOutId = item.id.removePrefix("act-").takeIf { item.kind == ActivityKind.CASH_OUT }
            DividerLine()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .then(if (cashOutId != null) Modifier.clickable(role = Role.Button) { onOpenCashOut(cashOutId) } else Modifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Type.Body, color = Palette.Text)
                    val detail = buildList {
                        add(Formats.relative(item.time, now))
                        item.tripId?.let(::add)
                        when (item.status) {
                            ActivityStatus.PENDING -> add("pending")
                            ActivityStatus.FAILED -> add("failed")
                            ActivityStatus.COMPLETED -> if (item.kind == ActivityKind.CASH_OUT) add("completed")
                        }
                        item.note?.let(::add)
                    }.joinToString(" · ")
                    Text(detail, style = Type.Caption, color = if (item.status == ActivityStatus.FAILED) Palette.Alert else Palette.TextSecondary)
                }
                Text(
                    Formats.signed(item.amount, item.incoming),
                    style = Type.MonoBody,
                    color = when {
                        item.status == ActivityStatus.FAILED -> Palette.TextSecondary
                        item.incoming -> Palette.LedgerSoft
                        else -> Palette.Text
                    },
                )
            }
        }
    }
}
