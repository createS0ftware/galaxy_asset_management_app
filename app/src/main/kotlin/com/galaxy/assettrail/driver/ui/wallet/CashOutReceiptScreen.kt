package com.galaxy.assettrail.driver.ui.wallet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.assettrail.core.model.CashOutStatus
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.IconCircle
import com.galaxy.assettrail.driver.ui.KeyValue
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type

/** Cash-out pending / completed / failed, kept as the receipt. */
@Composable
fun CashOutReceiptScreen(cashOutId: String, onDone: () -> Unit) {
    val container = LocalContainer.current
    val state by container.repository.state.collectAsStateWithLifecycle()
    val cashOut = state.cashOuts.firstOrNull { it.id == cashOutId }

    ScreenColumn(spacing = 18.dp) {
        BackLink("Wallet", onDone)
        if (cashOut == null) {
            ScreenTitle("Cash out not found")
            return@ScreenColumn
        }
        val received = "${cashOut.buyCurrency} ${cashOut.buyAmount}"
        val momo = "MTN MoMo ${Formats.maskedPhone(cashOut.momoNumber)}"
        when (cashOut.status) {
            CashOutStatus.AWAITING_PAYMENT, CashOutStatus.PENDING -> {
                val problem = cashOut.message?.takeIf { cashOut.status == CashOutStatus.AWAITING_PAYMENT }
                if (problem != null) {
                    IconCircle(AppIcons.Alert, tint = Palette.Alert, fill = Palette.AlertFill)
                    ScreenTitle("Not sent")
                    Banner(problem, tone = BannerTone.ALERT)
                } else {
                    CircularProgressIndicator(color = Palette.Ember)
                    ScreenTitle("Sending $received")
                    Text("To $momo. ${cashOut.eta}. You can leave this screen.", style = Type.Body, color = Palette.TextSecondary)
                }
            }
            CashOutStatus.COMPLETED -> {
                IconCircle(AppIcons.Check, tint = Palette.Ledger, fill = Palette.LedgerBadge)
                ScreenTitle("$received sent")
                Text(cashOut.message ?: "Sent to $momo.", style = Type.Body, color = Palette.TextSecondary)
            }
            CashOutStatus.REFUNDED -> {
                IconCircle(AppIcons.Alert, tint = Palette.Alert, fill = Palette.AlertFill)
                ScreenTitle("Cash out failed")
                Text(cashOut.message ?: "Your USDC is back in your wallet.", style = Type.Body, color = Palette.TextLabel)
            }
        }
        Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
            KeyValue("You sent", "${cashOut.sell.display()} USDC", divider = false)
            KeyValue("Anchor fee", "${cashOut.fee.display()} USDC")
            KeyValue("Received", received, strong = true)
            KeyValue("To", momo, mono = false)
            KeyValue("Started", Formats.dayTime(cashOut.createdAt), mono = false)
            cashOut.txHash?.let { KeyValue("Transaction", Formats.shortHash(it)) }
        }
        Text("Paid out by ${cashOut.anchorName}. Network fees paid by ${container.productName}.", style = Type.Small, color = Palette.TextSecondary)
        PrimaryButton("Done", onClick = onDone)
    }
}
