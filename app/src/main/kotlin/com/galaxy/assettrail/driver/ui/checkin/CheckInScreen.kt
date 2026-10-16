package com.galaxy.assettrail.driver.ui.checkin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxy.assettrail.driver.ui.BackLink
import com.galaxy.assettrail.driver.ui.Banner
import com.galaxy.assettrail.driver.ui.BannerTone
import com.galaxy.assettrail.driver.ui.CheckLine
import com.galaxy.assettrail.driver.ui.CheckState
import com.galaxy.assettrail.driver.ui.DemoChip
import com.galaxy.assettrail.driver.ui.Eyebrow
import com.galaxy.assettrail.driver.ui.Formats
import com.galaxy.assettrail.driver.ui.IconCircle
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.LocalSnackbar
import com.galaxy.assettrail.driver.ui.MessageEffect
import com.galaxy.assettrail.driver.ui.Panel
import com.galaxy.assettrail.driver.ui.PrimaryButton
import com.galaxy.assettrail.driver.ui.ScreenColumn
import com.galaxy.assettrail.driver.ui.ScreenTitle
import com.galaxy.assettrail.driver.ui.SecondaryButton
import com.galaxy.assettrail.driver.ui.TextAction
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import java.io.File

/** Check-in flow (spec §5.2 stages 3–4): tag scan, photo at pickup, then sign on the phone. */
@Composable
fun CheckInScreen(waypointIndex: Int, onBack: () -> Unit, onFinished: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: CheckInViewModel = viewModel(key = "checkin-$waypointIndex") { CheckInViewModel(container, waypointIndex) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    MessageEffect(viewModel)

    LaunchedEffect(ui.step) {
        if (ui.step == CheckInViewModel.Step.DONE) onFinished()
    }

    val plan = viewModel.plan
    val waypoint = viewModel.waypoint
    ScreenColumn(spacing = 18.dp) {
        BackLink("Trip", onBack)
        if (plan == null || waypoint == null) {
            ScreenTitle("Nothing to check in")
            return@ScreenColumn
        }
        Eyebrow("Check in · ${waypoint.name}")
        when (ui.step) {
            CheckInViewModel.Step.SCAN -> ScanStep(viewModel, plan.asset.shortName, plan.asset.id)
            CheckInViewModel.Step.PHOTO -> PhotoStep(viewModel, ui, plan.asset.shortName, plan.asset.id)
            CheckInViewModel.Step.SUBMITTING, CheckInViewModel.Step.DONE -> Submitting(online)
            CheckInViewModel.Step.BLOCKED -> Blocked(ui, onBack, viewModel::scanAgain)
        }
    }
}

@Composable
private fun ScanStep(viewModel: CheckInViewModel, assetName: String, assetId: String) {
    val context = LocalContext.current
    // Re-read the NFC switch whenever we come back, e.g. from the NFC settings screen.
    var nfcEnabled by remember { mutableStateOf(viewModel.nfcEnabled) }
    LifecycleResumeEffect(Unit) {
        nfcEnabled = viewModel.nfcEnabled
        onPauseOrDispose { }
    }
    ScreenTitle("Scan the asset tag")
    Text(
        "Hold the back of your phone against the tag on the ${assetName.lowercase()} ($assetId).",
        style = Type.Body,
        color = Palette.TextSecondary,
    )
    ScanPulse()
    val status = when {
        !viewModel.nfcAvailable -> "This phone can't read tags."
        !nfcEnabled -> "NFC is off. Turn it on to scan the tag."
        else -> "Ready to scan."
    }
    Text(
        status,
        style = Type.BodyStrong,
        color = if (viewModel.nfcAvailable && nfcEnabled) Palette.LedgerSoft else Palette.EmberSoft,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (viewModel.nfcAvailable && !nfcEnabled) {
        SecondaryButton("Open NFC settings", onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        })
    }
    Text(
        "Check-in is blocked unless the tag matches this trip's asset. It works without signal.",
        style = Type.Small,
        color = Palette.TextSecondary,
    )
    if (viewModel.isDemo) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoChip("Scan the right tag", onClick = { viewModel.demoScan(correct = true) })
            DemoChip("Scan a wrong tag", onClick = { viewModel.demoScan(correct = false) })
        }
    }
}

@Composable
private fun ScanPulse() {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(180.dp)) {
            val maxRadius = size.minDimension / 2
            repeat(3) { ring ->
                val phase = (progress + ring / 3f) % 1f
                drawCircle(
                    color = Palette.Ledger.copy(alpha = (1f - phase) * 0.6f),
                    radius = maxRadius * (0.35f + 0.65f * phase),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        IconCircle(AppIcons.Tag, tint = Palette.Ledger, fill = Palette.LedgerBadge, size = 72.dp, iconSize = 34.dp)
    }
}

@Composable
private fun PhotoStep(viewModel: CheckInViewModel, ui: CheckInViewModel.Ui, assetName: String, assetId: String) {
    val container = LocalContainer.current
    val snackbar = LocalSnackbar.current
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraError by rememberSaveable { mutableStateOf(false) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = photoPath
        if (saved && path != null) viewModel.onPhotoTaken(File(path))
    }
    LaunchedEffect(cameraError) {
        if (cameraError) {
            snackbar.showSnackbar("No camera app found on this phone.")
            cameraError = false
        }
    }

    ScreenTitle("Photograph the load")
    Text(
        "One clear photo of the ${assetName.lowercase()} on the truck. Only its fingerprint goes on the ledger; the photo stays private.",
        style = Type.Body,
        color = Palette.TextSecondary,
    )
    Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), spacing = 0.dp) {
        CheckLine(CheckState.DONE, "Asset tag matched", trailing = assetId, trailingMono = true, divider = false)
        CheckLine(
            if (ui.photoHash != null) CheckState.DONE else CheckState.PENDING,
            if (ui.photoHash != null) "Load photographed" else "Photo of the load",
            trailing = ui.photoHash?.removePrefix("sha256:")?.let(Formats::shortHash),
            trailingMono = true,
        )
    }
    if (ui.photoHash == null) {
        PrimaryButton("Take photo", icon = AppIcons.Camera, onClick = {
            val (file, uri) = container.photos.newPhoto()
            photoPath = file.absolutePath
            try {
                takePicture.launch(uri)
            } catch (_: ActivityNotFoundException) {
                cameraError = true
            }
        })
        if (viewModel.isDemo) DemoChip("Use a sample photo", onClick = viewModel::useSamplePhoto)
    } else {
        PrimaryButton("Start the trip", icon = AppIcons.Pin, onClick = viewModel::confirmPhoto)
        SecondaryButton("Retake photo", onClick = viewModel::clearPhoto, minHeight = 52.dp)
    }
}

@Composable
private fun Submitting(online: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = Palette.Ember)
        Text(
            if (online) "Signing on your phone and recording on the ledger…" else "Signing and saving on your phone…",
            style = Type.Body,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Blocked(ui: CheckInViewModel.Ui, onBack: () -> Unit, onScanAgain: () -> Unit) {
    IconCircle(AppIcons.Alert, tint = Palette.Alert, fill = Palette.AlertFill)
    ScreenTitle(ui.blockedTitle)
    Text(ui.blockedMessage, style = Type.Body, color = Palette.TextLabel)
    ui.tag?.let {
        Banner("Scanned tag $it", tone = BannerTone.INFO, icon = AppIcons.Tag)
    }
    if (ui.canScanAgain) PrimaryButton("Scan again", onClick = onScanAgain)
    SecondaryButton("Back to trip", onClick = onBack, minHeight = 52.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Shield, null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
        Text("Nothing was signed or sent to the ledger.", style = Type.Small, color = Palette.TextSecondary)
    }
}
