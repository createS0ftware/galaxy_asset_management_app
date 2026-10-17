package com.galaxy.assettrail.driver.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.hasArrivedAtDelivery
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.model.ContractStatus
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.account.SwitchRow
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import com.galaxy.assettrail.mock.DemoFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Plays everyone who isn't the driver: receiver, ops, payout partner, the platform itself. */
class DemoViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository
    private val demo = container.demo
    /** The in-app demo platform can also play ops and the payout partner; a live backend can't. */
    val inAppPlatform = container.isDemo
    val state: StateFlow<DriverState> = repository.state
    val noSignal: MutableStateFlow<Boolean> = container.simulatedNoSignal

    private val _flags = MutableStateFlow(DemoFlags())
    val flags: StateFlow<DemoFlags> = _flags.asStateFlow()

    init {
        viewModelScope.launch { demo?.flags()?.let { _flags.value = it } }
    }

    private val tripId: String? get() = state.value.trip?.plan?.id

    private fun other(done: String, block: suspend (String) -> Unit) = action {
        val id = tripId ?: throw IllegalStateException("Accept a trip first.")
        block(id)
        repository.refresh()
        say(done)
    }

    fun receiverSigns() = other("Receiver signed with their passkey.") { demo?.receiverSigns(it) }

    fun receiverReportsProblem() = other("Receiver reported a problem with photos.") { demo?.receiverReportsProblem(it) }

    fun resolveForDriver() = other("Ops resolved the dispute in the driver's favour.") { demo?.opsResolvesDisputeForDriver(it) }

    fun expireDeadline() = other("Delivery deadline has passed.") { demo?.expireDeliveryDeadline(it) }

    fun setFlags(change: (DemoFlags) -> DemoFlags) = action {
        demo?.updateFlags(change)
        demo?.flags()?.let { _flags.value = it }
    }

    fun arrive() {
        state.value.trip?.nextWaypoint()?.let { container.location.simulateNear(it, 82.0) }
    }

    fun justOutside() {
        state.value.trip?.nextWaypoint()?.let { container.location.simulateNear(it, it.geofenceRadiusM + 160.0) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoControlsSheet(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: DemoViewModel = viewModel(key = "demo") { DemoViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flags by viewModel.flags.collectAsStateWithLifecycle()
    val noSignal by viewModel.noSignal.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    MessageEffect(viewModel)

    val trip = state.trip?.takeIf { !it.closed }
    val next = trip?.nextWaypoint()
    val arrived = trip?.hasArrivedAtDelivery() == true
    val contract = trip?.contract?.status

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Demo controls", style = Type.Subheading, color = Palette.Demo)
            Text(
                "Stand-ins for the backend: they act as the receiver, ops, the payout partner and the platform.",
                style = Type.Small,
                color = Palette.TextSecondary,
            )

            Section("Your position")
            if (next != null) {
                SecondaryButton("Arrive at ${next.name}", onClick = { viewModel.arrive(); onDismiss() })
                SecondaryButton("Stand just outside ${next.name}", onClick = { viewModel.justOutside(); onDismiss() })
            } else {
                Text("No next stop.", style = Type.Small, color = Palette.TextSecondary)
            }

            Section("Signal")
            SwitchRow("Simulate no signal", noSignal) { viewModel.noSignal.value = it }

            Section("Receiver")
            SecondaryButton(
                "Receiver signs with passkey",
                onClick = viewModel::receiverSigns,
                enabled = arrived && contract == ContractStatus.LOCKED,
                busy = busy,
            )
            if (viewModel.inAppPlatform) {
                SecondaryButton(
                    "Receiver reports a problem",
                    onClick = viewModel::receiverReportsProblem,
                    enabled = arrived && contract == ContractStatus.LOCKED,
                )
            }
            if (!arrived) Text("Available once the driver checks in at the delivery stop.", style = Type.Caption, color = Palette.TextSecondary)

            // Live builds talk to the real backend: only the receiver's side can be played from here.
            if (viewModel.inAppPlatform) {
                Section("Ops and the contract")
                SecondaryButton("Resolve dispute for the driver", onClick = viewModel::resolveForDriver, enabled = contract == ContractStatus.DISPUTED)
                SecondaryButton("Skip past the delivery deadline", onClick = viewModel::expireDeadline, enabled = arrived && contract == ContractStatus.LOCKED)
                SwitchRow("Decline the next advance", flags.declineNextAdvance) { on -> viewModel.setFlags { it.copy(declineNextAdvance = on) } }

                Section("Platform")
                SwitchRow("Fee account low on XLM", flags.feeAccountLow) { on -> viewModel.setFlags { it.copy(feeAccountLow = on) } }

                Section("Payout partner")
                SwitchRow("Fail the next cash out", flags.failNextCashOut) { on -> viewModel.setFlags { it.copy(failNextCashOut = on) } }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = Type.Eyebrow, color = Palette.TextSecondary, modifier = Modifier.padding(top = 10.dp))
}
