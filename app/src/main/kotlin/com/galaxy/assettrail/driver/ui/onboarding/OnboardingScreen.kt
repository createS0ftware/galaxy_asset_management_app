package com.galaxy.assettrail.driver.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.Onboarding
import com.galaxy.assettrail.core.driver.OnboardingStep
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.LabeledField
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.TextAction
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

class OnboardingViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository

    val onboarding: StateFlow<Onboarding> = repository.state
        .map { it.onboarding }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.state.value.onboarding)

    val isDemo = container.isDemo
    val productName = container.productName

    fun sendCode(phone: String) = action { repository.sendCode(phone) }

    fun verify(code: String) = action { repository.verifyCode(code) }

    fun changePhone() = action { repository.changePhone() }

    fun checkIdentity(fullName: String, idNumber: String) = action { repository.submitIdentity(fullName, idNumber) }

    fun finish(momoNumber: String, recovery: Boolean) = action { repository.finishOnboarding(momoNumber, recovery) }
}

/** Onboarding, 3 steps (spec §5.2 stage 1): phone, ID check with the anchor, MoMo + wallet. */
@Composable
fun OnboardingScreen() {
    val container = LocalContainer.current
    val viewModel: OnboardingViewModel = viewModel { OnboardingViewModel(container) }
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    MessageEffect(viewModel)

    ScreenColumn(spacing = 20.dp) {
        val step = when (onboarding.step) {
            OnboardingStep.PHONE -> 1
            OnboardingStep.IDENTITY -> 2
            OnboardingStep.PAYOUT -> 3
        }
        StepIndicator(step)
        when (onboarding.step) {
            OnboardingStep.PHONE -> PhoneStep(viewModel, onboarding, busy)
            OnboardingStep.IDENTITY -> IdentityStep(viewModel, busy)
            OnboardingStep.PAYOUT -> PayoutStep(viewModel, onboarding, busy)
        }
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Step $step of 3" }) {
        Text("Step $step of 3", style = Type.Small, color = Palette.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..3).forEach { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (index <= step) Palette.Ember else Palette.SurfaceRaised, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun PhoneStep(viewModel: OnboardingViewModel, onboarding: Onboarding, busy: Boolean) {
    var phone by rememberSaveable { mutableStateOf(if (viewModel.isDemo) "+233 24 123 4471" else "+233 ") }
    var code by rememberSaveable { mutableStateOf("") }
    val codeSent = onboarding.phone != null

    ScreenTitle("Get paid for every leg")
    Text(
        "Your pay arrives as you check in at each stop, and you can cash out to MTN MoMo any time. " +
            "${viewModel.productName} pays every network fee.",
        style = Type.Body,
        color = Palette.TextSecondary,
    )
    if (!codeSent) {
        LabeledField(
            label = "Your phone number",
            value = phone,
            onValueChange = { phone = it },
            keyboardType = KeyboardType.Phone,
            hint = "We'll text you a code to check it's yours.",
            onImeAction = { viewModel.sendCode(phone) },
        )
        PrimaryButton("Send code", onClick = { viewModel.sendCode(phone) }, busy = busy)
    } else {
        LabeledField(
            label = "Code from the SMS",
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            keyboardType = KeyboardType.NumberPassword,
            textStyle = Type.MonoMedium.copy(color = Palette.Text),
            hint = "Sent to ${Formats.maskedPhone(onboarding.phone.orEmpty())}.",
            onImeAction = { viewModel.verify(code) },
        )
        onboarding.demoCode?.let { demoCode ->
            Banner("Demo build: no SMS is sent. Use code $demoCode.", tone = BannerTone.DEMO)
        }
        PrimaryButton("Continue", onClick = { viewModel.verify(code) }, enabled = code.length == 6, busy = busy)
        TextAction("Use a different number", onClick = viewModel::changePhone)
    }
}

@Composable
private fun IdentityStep(viewModel: OnboardingViewModel, busy: Boolean) {
    var name by rememberSaveable { mutableStateOf(if (viewModel.isDemo) "Kwame Asante" else "") }
    var idNumber by rememberSaveable { mutableStateOf(if (viewModel.isDemo) "GHA-723451890-3" else "") }

    ScreenTitle("Check your ID")
    Text(
        "Our payout partner checks your ID once, so you can cash out to mobile money. " +
            "${viewModel.productName} never stores your ID.",
        style = Type.Body,
        color = Palette.TextSecondary,
    )
    LabeledField(
        label = "Full name, as on your ID",
        value = name,
        onValueChange = { name = it },
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Next,
    )
    LabeledField(
        label = "Ghana Card number",
        value = idNumber,
        onValueChange = { idNumber = it.uppercase() },
        capitalization = KeyboardCapitalization.Characters,
        placeholder = "GHA-000000000-0",
        onImeAction = { viewModel.checkIdentity(name, idNumber) },
    )
    PrimaryButton("Check my ID", onClick = { viewModel.checkIdentity(name, idNumber) }, busy = busy)
}

@Composable
private fun PayoutStep(viewModel: OnboardingViewModel, onboarding: Onboarding, busy: Boolean) {
    var momo by rememberSaveable { mutableStateOf(onboarding.phone.orEmpty()) }
    var recovery by rememberSaveable { mutableStateOf(true) }

    ScreenTitle("Where should your money go?")
    Text(
        "Cash out sends money to this MTN MoMo number. You can change it before each cash out.",
        style = Type.Body,
        color = Palette.TextSecondary,
    )
    LabeledField(
        label = "MTN MoMo number",
        value = momo,
        onValueChange = { momo = it },
        keyboardType = KeyboardType.Phone,
        onImeAction = { viewModel.finish(momo, recovery) },
    )
    Panel(spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.Shield, null, tint = Palette.Ledger, modifier = Modifier.size(22.dp))
            Text("Back up my wallet", style = Type.BodyStrong, color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(
                checked = recovery,
                onCheckedChange = { recovery = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.Ink,
                    checkedTrackColor = Palette.Ember,
                    uncheckedThumbColor = Palette.TextSecondary,
                    uncheckedTrackColor = Palette.SurfaceRaised,
                    uncheckedBorderColor = Palette.OutlineButton,
                ),
            )
        }
        Text(
            "Your wallet key stays on this phone. If you lose the phone, we can restore your wallet " +
                "after checking your phone number and ID again.",
            style = Type.Small,
            color = Palette.TextSecondary,
        )
    }
    Spacer(Modifier.height(4.dp))
    PrimaryButton(if (busy) "Setting up your wallet…" else "Finish", onClick = { viewModel.finish(momo, recovery) }, busy = busy)
    Text(
        "ID checked by ${onboarding.kycProvider ?: "our payout partner"}.",
        style = Type.Small,
        color = Palette.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
