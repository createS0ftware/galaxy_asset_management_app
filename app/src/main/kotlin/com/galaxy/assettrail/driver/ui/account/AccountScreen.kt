package com.galaxy.assettrail.driver.ui.account

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.BuildConfig
import com.galaxy.assettrail.driver.device.AndroidKeyVault
import com.galaxy.assettrail.driver.device.LocationController
import com.galaxy.assettrail.driver.ui.ActionViewModel
import com.galaxy.assettrail.driver.ui.DividerLine
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.KeyValue
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.SectionHeading
import com.galaxy.assettrail.driver.ui.TextAction
import com.galaxy.assettrail.driver.ui.checkin.copy
import com.galaxy.assettrail.driver.ui.demo.DemoControlsSheet
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AccountViewModel(private val container: AppContainer) : ActionViewModel() {
    val state: StateFlow<DriverState> = container.repository.state
    val locationMode: StateFlow<LocationController.Mode> = container.location.mode
    val noSignal: MutableStateFlow<Boolean> = container.simulatedNoSignal
    val isDemo = container.isDemo
    val productName = container.productName
    val network = if (container.ledger.network.networkPassphrase.contains("Test")) "Stellar testnet" else "Stellar"

    fun protection(): AndroidKeyVault.Protection = container.keyVault.protection()

    fun useDeviceLocation(on: Boolean) {
        container.location.setMode(if (on) LocationController.Mode.DEVICE else LocationController.Mode.SIMULATED)
    }

    fun onLocationPermission() = container.location.onPermissionResult()

    fun hasLocationPermission() = container.location.hasPermission

    fun reset() = action { container.resetDemo() }
}

@Composable
fun AccountScreen() {
    val container = LocalContainer.current
    val viewModel: AccountViewModel = viewModel { AccountViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.locationMode.collectAsStateWithLifecycle()
    val noSignal by viewModel.noSignal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    MessageEffect(viewModel)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermission()
    }

    val profile = state.profile ?: return
    ScreenColumn(spacing = 18.dp) {
        ScreenTitle("Account")

        Panel(spacing = 2.dp) {
            Text(profile.fullName, style = Type.Subheading, color = Palette.Text)
            Text(Formats.maskedPhone(profile.phone), style = Type.Body, color = Palette.TextSecondary)
            Text("ID checked by ${profile.kycProvider}", style = Type.Small, color = Palette.TextSecondary)
        }

        Column {
            SectionHeading("Payouts")
            InfoRow(AppIcons.Phone, "MTN MoMo", Formats.maskedPhone(profile.momoNumber))
        }

        Column {
            SectionHeading("Wallet")
            val protection = remember { viewModel.protection() }
            InfoRow(
                AppIcons.Shield,
                "Your key stays on this phone",
                when (protection) {
                    AndroidKeyVault.Protection.STRONGBOX -> "Locked by the phone's security chip (StrongBox)."
                    AndroidKeyVault.Protection.TRUSTED_ENVIRONMENT -> "Locked by the phone's secure hardware."
                    else -> "Locked by the Android keystore."
                },
            )
            InfoRow(
                AppIcons.Person,
                if (profile.recoveryEnabled) "Wallet backup is on" else "Wallet backup is off",
                if (profile.recoveryEnabled) {
                    "If you lose this phone, we can restore your wallet after checking your phone number and ID."
                } else {
                    "If you lose this phone, ops can move your wallet to a new one after checking your ID."
                },
            )
            InfoRow(AppIcons.Wallet, "Network fees", "Paid by ${viewModel.productName}. You never need XLM.")
            DividerLine()
            TextAction(if (showDetails) "Hide technical details" else "Technical details", onClick = { showDetails = !showDetails })
            if (showDetails) TechnicalDetails(state, viewModel.network) { label, value -> copy(context, label, value) }
        }

        if (viewModel.isDemo) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading("Demo")
                Text(
                    "This build runs a simulated platform in place of the backend and escrow-contract API. Use these to play the other parties.",
                    style = Type.Small,
                    color = Palette.TextSecondary,
                )
                SwitchRow("Use this phone's GPS", mode == LocationController.Mode.DEVICE) { on ->
                    if (on && !viewModel.hasLocationPermission()) {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    viewModel.useDeviceLocation(on)
                }
                SwitchRow("Simulate no signal", noSignal) { viewModel.noSignal.value = it }
                SecondaryButton("Demo controls", onClick = { showDemo = true }, minHeight = 52.dp)
                SecondaryButton("Reset demo", onClick = { confirmReset = true }, minHeight = 52.dp)
            }
        }

        Text("${viewModel.productName} ${BuildConfig.VERSION_NAME}", style = Type.Caption, color = Palette.Upcoming)
    }

    if (showDemo) DemoControlsSheet(onDismiss = { showDemo = false })
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.Surface,
            title = { Text("Reset the demo?", style = Type.Subheading, color = Palette.Text) },
            text = {
                Text(
                    "This deletes the wallet key on this phone, every trip and check-in, and the simulated platform's data. You'll start again from sign-up.",
                    style = Type.Body,
                    color = Palette.TextLabel,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.reset()
                }) { Text("Delete and reset", color = Palette.Alert, style = Type.ButtonSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Palette.TextLabel, style = Type.ButtonSecondary) }
            },
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, detail: String) {
    Column {
        DividerLine()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = Palette.Ledger, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).heightIn(min = 44.dp), verticalArrangement = Arrangement.Center) {
                Text(title, style = Type.Body, color = Palette.Text)
                Text(detail, style = Type.Small, color = Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun TechnicalDetails(state: DriverState, network: String, onCopy: (String, String) -> Unit) {
    val profile = state.profile ?: return
    Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Copy wallet address") {
                onCopy("Wallet address", profile.accountId)
            },
        ) {
            Text("Wallet address (tap to copy)", style = Type.Small, color = Palette.TextSecondary)
            Text(profile.accountId, style = Type.MonoSmall, color = Palette.Text)
        }
        KeyValue("Network", network, mono = false)
        KeyValue("Sequence number", state.sequence.toString())
        KeyValue("Waiting to record", state.queue.size.toString())
        KeyValue("Wallet created", Formats.shortHash(profile.walletTx))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Type.Body, color = Palette.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Ember,
                uncheckedThumbColor = Palette.TextSecondary,
                uncheckedTrackColor = Palette.SurfaceRaised,
                uncheckedBorderColor = Palette.OutlineButton,
            ),
        )
    }
}
