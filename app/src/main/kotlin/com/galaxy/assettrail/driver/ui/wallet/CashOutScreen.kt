package com.galaxy.assettrail.driver.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.canMoveMoney
import com.galaxy.assettrail.core.model.FirmQuote
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.KeyValue
import com.galaxy.assettrail.driver.ui.LabeledField
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.TextAction
import com.galaxy.assettrail.driver.ui.rememberNow
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** SEP-6 withdrawal at a SEP-38 firm quote: rate, fee, amount and arrival before Confirm (§6.2). */
class CashOutViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository
    val state: StateFlow<DriverState> = repository.state
    val online: StateFlow<Boolean> = container.online
    val productName = container.productName

    private val _amount = MutableStateFlow(repository.state.value.balance.display())
    val amount: StateFlow<String> = _amount.asStateFlow()

    private val _quote = MutableStateFlow<FirmQuote?>(null)
    val quote: StateFlow<FirmQuote?> = _quote.asStateFlow()

    private val _quoteProblem = MutableStateFlow<String?>(null)
    val quoteProblem: StateFlow<String?> = _quoteProblem.asStateFlow()

    private val _quoting = MutableStateFlow(false)
    val quoting: StateFlow<Boolean> = _quoting.asStateFlow()

    private var quoteJob: Job? = null

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _amount.debounce(500).distinctUntilChanged().collect { requote() }
        }
        // A firm quote expires; fetch a fresh one when it does.
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val current = _quote.value
                if (current != null && container.clock.millis() >= current.expiresAt) requote()
            }
        }
    }

    fun setAmount(text: String) {
        _amount.value = text
    }

    fun max() {
        _amount.value = repository.state.value.balance.display()
    }

    private fun requote() {
        quoteJob?.cancel()
        val sell = Usdc.parseOrNull(_amount.value)
        _quote.value = null
        _quoteProblem.value = when {
            sell == null -> "Enter an amount."
            sell > repository.state.value.balance -> "You have ${repository.state.value.balance.display()} USDC."
            !online.value -> "Quotes need signal."
            else -> null
        }
        if (_quoteProblem.value != null || sell == null) return
        quoteJob = viewModelScope.launch {
            _quoting.value = true
            try {
                _quote.value = repository.quote(sell)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _quoteProblem.value = friendly(e)
            } finally {
                _quoting.value = false
            }
        }
    }

    fun confirm(onSubmitted: (String) -> Unit) {
        val current = _quote.value ?: return
        if (container.clock.millis() >= current.expiresAt) {
            requote()
            say("The quote expired. Check the new one.")
            return
        }
        var id: String? = null
        action(onSuccess = { id?.let(onSubmitted) }) {
            id = repository.cashOut(current).id
        }
    }

    fun changeMomo(number: String) = action {
        repository.updateMomoNumber(number)
        say("Cash outs will go to ${Formats.maskedPhone(number)}.")
    }
}

@Composable
fun CashOutScreen(onBack: () -> Unit, onSubmitted: (String) -> Unit) {
    val container = LocalContainer.current
    val viewModel: CashOutViewModel = viewModel { CashOutViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val amount by viewModel.amount.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val problem by viewModel.quoteProblem.collectAsStateWithLifecycle()
    val quoting by viewModel.quoting.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val now = rememberNow()
    var editingMomo by rememberSaveable { mutableStateOf(false) }
    MessageEffect(viewModel)

    val profile = state.profile ?: return
    ScreenColumn(spacing = 18.dp) {
        BackLink("Wallet", onBack)
        ScreenTitle("Cash out")

        LabeledField(
            label = "Amount (USDC)",
            value = amount,
            onValueChange = viewModel::setAmount,
            keyboardType = KeyboardType.Decimal,
            textStyle = Type.MonoMedium.copy(color = Palette.Text),
            hint = "Available ${state.balance.display()} USDC",
        ) {
            SecondaryButton("Max", onClick = viewModel::max, modifier = Modifier.fillMaxWidth(0.28f), minHeight = 56.dp)
        }

        Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(Palette.SurfaceRaised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Phone, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("To", style = Type.Small, color = Palette.TextSecondary)
                    Text("MTN MoMo · ${Formats.maskedPhone(profile.momoNumber)}", style = Type.BodyStrong.copy(fontSize = Type.Heading.fontSize), color = Palette.Text)
                }
                TextAction("Change", onClick = { editingMomo = true })
            }
        }

        QuotePanel(quote, problem, quoting, now)

        if (!state.canMoveMoney()) {
            Banner("Cash out unlocks once your saved check-ins are recorded.", tone = BannerTone.PENDING)
        }

        PrimaryButton(
            "Confirm cash out",
            onClick = { viewModel.confirm(onSubmitted) },
            enabled = quote != null && online && state.canMoveMoney(),
            busy = busy,
        )
        Text(
            "Your ID was checked by ${quote?.anchorName ?: profile.kycProvider} when you signed up. ${viewModel.productName} never sees your MoMo PIN.",
            style = Type.Small,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (editingMomo) {
        var number by rememberSaveable { mutableStateOf(profile.momoNumber) }
        AlertDialog(
            onDismissRequest = { editingMomo = false },
            containerColor = Palette.Surface,
            title = { Text("MTN MoMo number", style = Type.Subheading, color = Palette.Text) },
            text = { LabeledField(label = "Send cash outs to", value = number, onValueChange = { number = it }, keyboardType = KeyboardType.Phone) },
            confirmButton = {
                TextButton(onClick = {
                    editingMomo = false
                    viewModel.changeMomo(number)
                }) { Text("Save", color = Palette.Ember, style = Type.ButtonSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { editingMomo = false }) { Text("Cancel", color = Palette.TextLabel, style = Type.ButtonSecondary) }
            },
        )
    }
}

@Composable
private fun QuotePanel(quote: FirmQuote?, problem: String?, quoting: Boolean, now: Long) {
    Panel(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp), spacing = 0.dp) {
        when {
            quote != null -> {
                KeyValue("You send", "${quote.sell.display()} USDC", divider = false)
                KeyValue("Anchor fee", "${quote.fee.display()} USDC")
                KeyValue("Rate", "1 USDC = ${quote.rate} ${quote.buyCurrency}")
                KeyValue("You receive", "${quote.buyCurrency} ${quote.buyAmount}", strong = true)
                KeyValue("Arrives", quote.eta, mono = false)
                val seconds = Formats.secondsLeft(quote.expiresAt, now)
                Text(
                    "Firm quote from ${quote.anchorName} (SEP-38) · valid until ${Formats.timeSeconds(quote.expiresAt)} (${seconds}s)",
                    style = Type.Caption,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            quoting -> Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
                Text("Getting a quote from the payout partner…", style = Type.Small, color = Palette.TextSecondary)
            }
            else -> Text(
                problem ?: "Enter an amount to see the quote.",
                style = Type.Small,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
    }
}
