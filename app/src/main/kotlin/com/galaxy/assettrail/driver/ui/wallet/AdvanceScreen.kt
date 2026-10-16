package com.galaxy.assettrail.driver.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.AdvanceRules
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.KeyValue
import com.galaxy.assettrail.driver.ui.LabeledField
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

/** Request a fuel advance (spec §5.2 stage 5): within the ops limit, deduction shown up front. */
@Composable
fun AdvanceScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: WalletViewModel = viewModel(key = "advance") { WalletViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    MessageEffect(viewModel)

    val trip = state.trip
    ScreenColumn(spacing = 18.dp) {
        BackLink("Back", onBack)
        ScreenTitle("Fuel advance")
        if (trip == null || trip.closed) {
            Text("Advances are available during a trip.", style = Type.Body, color = Palette.TextSecondary)
            return@ScreenColumn
        }
        val available = AdvanceRules.available(trip.plan, trip.advances)
        var text by rememberSaveable { mutableStateOf(available.display()) }
        val amount = Usdc.parseOrNull(text)
        val tooMuch = amount != null && amount > available

        Text(
            "Get cash before the next leg pays. Ops approves it, then you claim it in your wallet.",
            style = Type.Body,
            color = Palette.TextSecondary,
        )
        LabeledField(
            label = "Amount (USDC)",
            value = text,
            onValueChange = { text = it },
            keyboardType = KeyboardType.Decimal,
            textStyle = Type.MonoMedium.copy(color = Palette.Text),
            isError = tooMuch,
            hint = if (tooMuch) "You can ask for up to ${available.display()} USDC on ${trip.plan.id}." else "Up to ${available.display()} USDC on ${trip.plan.id}.",
        ) {
            SecondaryButton("Max", onClick = { text = available.display() }, modifier = Modifier.fillMaxWidth(0.28f), minHeight = 56.dp)
        }

        val finalLeg = trip.plan.delivery.legPay
        val afterDeduction = (finalLeg - AdvanceRules.deduction(trip.advances) - (amount ?: Usdc.ZERO)).coerceAtLeastZero()
        Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
            KeyValue("You get now", "${(amount ?: Usdc.ZERO).display()} USDC", divider = false)
            KeyValue("Final leg pay", "${finalLeg.display()} USDC")
            KeyValue("Final leg after this advance", "${afterDeduction.display()} USDC", strong = true)
        }
        Text(
            "It comes off your final leg pay. Once approved, claim it within 48 hours or it goes back to the trip.",
            style = Type.Small,
            color = Palette.TextSecondary,
        )
        if (!online) Banner("Requesting an advance needs signal.", tone = BannerTone.PENDING)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "Send request",
                onClick = { amount?.let { viewModel.requestAdvance(it, onBack) } },
                enabled = amount != null && !tooMuch && online,
                busy = busy,
            )
        }
    }
}
