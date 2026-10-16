package com.galaxy.assettrail.driver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import kotlinx.coroutines.delay

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

private val CardShape = RoundedCornerShape(12.dp)

/** Wall-clock time that ticks once a second, for countdowns and ETAs. */
@Composable
fun rememberNow(intervalMillis: Long = 1_000): Long {
    val clock = LocalContainer.current.clock
    var now by remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            now = clock.millis()
            delay(intervalMillis)
        }
    }
    return now
}

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    padding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Title, color = Palette.Text, modifier = modifier.semantics { heading() })
}

@Composable
fun Eyebrow(text: String, color: Color = Palette.Ember) {
    Text(text.uppercase(), style = Type.Eyebrow, color = color)
}

@Composable
fun SectionHeading(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = Type.Heading, color = Palette.Text, modifier = Modifier.semantics { heading() })
        if (trailing != null) Text(trailing, style = Type.Small, color = Palette.TextSecondary)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    fill: Color = Palette.Surface,
    border: Color = Palette.Border,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(fill, CardShape)
            .border(1.dp, border, CardShape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    style: TextStyle = Type.Button,
    minHeight: Dp = 56.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = CardShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Ember,
            contentColor = Palette.OnEmber,
            disabledContainerColor = Palette.SurfaceRaised,
            disabledContentColor = Palette.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = style, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    minHeight: Dp = 48.dp,
    style: TextStyle = Type.ButtonSecondary,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = CardShape,
        border = BorderStroke(1.dp, if (enabled) Palette.OutlineButton else Palette.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Palette.Text,
            disabledContentColor = Palette.Upcoming,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = style, textAlign = TextAlign.Center)
    }
}

/** Link-coloured text action, at least 44 dp tall. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Ledger, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = Palette.Upcoming),
    ) {
        Text(text, style = Type.ButtonSecondary)
    }
}

@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    TextAction(text = "‹ $label", onClick = onClick, modifier = Modifier.padding(start = 0.dp))
}

enum class BadgeTone { LEDGER, EMBER, NEUTRAL, ALERT }

@Composable
fun Badge(text: String, tone: BadgeTone = BadgeTone.LEDGER) {
    val (fill, color) = when (tone) {
        BadgeTone.LEDGER -> Palette.LedgerBadge to Palette.LedgerSoft
        BadgeTone.EMBER -> Palette.AdvanceFill to Palette.EmberSoft
        BadgeTone.NEUTRAL -> Palette.SurfaceRaised to Palette.TextLabel
        BadgeTone.ALERT -> Palette.AlertFill to Palette.Alert
    }
    Box(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .background(fill, RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = color)
    }
}

enum class BannerTone { INFO, PENDING, ALERT, DEMO }

@Composable
fun Banner(
    text: String,
    tone: BannerTone = BannerTone.INFO,
    title: String? = null,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val (fill, border, accent) = when (tone) {
        BannerTone.INFO -> Triple(Palette.Surface, Palette.Border, Palette.Ledger)
        BannerTone.PENDING -> Triple(Palette.Surface, Palette.Border, Palette.LedgerSoft)
        BannerTone.ALERT -> Triple(Palette.AlertFill, Palette.AlertBorder, Palette.Alert)
        BannerTone.DEMO -> Triple(Palette.DemoFill, Palette.DemoBorder, Palette.Demo)
    }
    Panel(fill = fill, border = border, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon ?: when (tone) {
                    BannerTone.ALERT -> AppIcons.Alert
                    BannerTone.PENDING -> AppIcons.Clock
                    BannerTone.DEMO -> AppIcons.Flask
                    BannerTone.INFO -> AppIcons.Shield
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp).padding(top = 1.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = Type.BodyStrong, color = Palette.Text)
                Text(text, style = Type.Small, color = if (tone == BannerTone.ALERT) Palette.Alert else Palette.TextLabel)
            }
        }
        if (action != null) {
            Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = action)
        }
    }
}

@Composable
fun Amount(amount: Usdc, style: TextStyle = Type.MonoBody, color: Color = Palette.Text, suffix: String? = null) {
    Text(if (suffix == null) amount.display() else "${amount.display()} $suffix", style = style, color = color)
}

/** One line of "what was checked" on the Checked-in screen. */
enum class CheckState { DONE, PENDING, FAILED }

@Composable
fun CheckLine(
    state: CheckState,
    title: String,
    trailing: String? = null,
    trailingMono: Boolean = false,
    onTrailingClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    Column {
        if (divider) DividerLine(Palette.Border)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                CheckState.DONE -> Icon(AppIcons.Check, null, tint = Palette.Ledger, modifier = Modifier.size(20.dp))
                CheckState.PENDING -> Icon(AppIcons.Clock, null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
                CheckState.FAILED -> Icon(AppIcons.Alert, null, tint = Palette.Alert, modifier = Modifier.size(20.dp))
            }
            Text(title, style = Type.Body, color = Palette.Text, modifier = Modifier.weight(1f))
            if (trailing != null) {
                val style = if (trailingMono) Type.MonoSmall else Type.Small
                val color = if (onTrailingClick != null) Palette.Ledger else Palette.TextSecondary
                Text(
                    trailing,
                    style = style,
                    color = color,
                    modifier = if (onTrailingClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onTrailingClick).padding(vertical = 12.dp)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
fun DividerLine(color: Color = Palette.Divider) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun KeyValue(label: String, value: String, strong: Boolean = false, mono: Boolean = true, divider: Boolean = true) {
    Column {
        if (divider) DividerLine(Palette.Border)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = if (strong) 48.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = if (strong) Type.Subheading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else Type.Body, color = if (strong) Palette.Text else Palette.TextLabel)
            Text(
                value,
                style = when {
                    mono && strong -> Type.MonoBody.copy(fontSize = Type.Subheading.fontSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    mono -> Type.MonoBody
                    else -> Type.Body
                },
                color = Palette.Text,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Status of a stop in the trip's leg list. Saved-but-unrecorded is dashed: never drawn as verified. */
enum class StopMark { VERIFIED, SAVED, NEXT, UPCOMING, FAILED }

@Composable
fun StopMarker(mark: StopMark, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val radius = size.minDimension / 2
            when (mark) {
                StopMark.VERIFIED -> drawCircle(Palette.Ledger, radius)
                StopMark.SAVED -> drawCircle(
                    Palette.Ledger,
                    radius - 1.dp.toPx(),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
                StopMark.NEXT -> drawCircle(Palette.Ember, radius - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
                StopMark.UPCOMING -> drawCircle(Palette.Upcoming, radius - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                StopMark.FAILED -> drawCircle(Palette.Alert, radius - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
            }
        }
        if (mark == StopMark.VERIFIED) {
            Icon(AppIcons.Check, null, tint = Palette.Ink, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
fun StopRow(
    mark: StopMark,
    title: String,
    subtitle: String,
    amount: String,
    subtitleColor: Color = Palette.TextSecondary,
    amountColor: Color = Palette.Text,
    emphasise: Boolean = false,
) {
    Column {
        DividerLine()
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StopMarker(mark)
            Column(Modifier.weight(1f)) {
                Text(title, style = if (emphasise) Type.BodyStrong else Type.Body, color = Palette.Text)
                Text(subtitle, style = Type.Caption, color = subtitleColor)
            }
            Text(amount, style = Type.MonoSmall.copy(fontSize = Type.Body.fontSize.times(0.94f)), color = amountColor)
        }
    }
}

/** Online/offline indicator from the trip header. */
@Composable
fun ConnectionStatus(online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (online) {
            Box(Modifier.size(8.dp).background(Palette.Ledger, CircleShape))
            Text("Online", style = Type.Small, color = Palette.TextSecondary)
        } else {
            Icon(AppIcons.NoSignal, null, tint = Palette.EmberSoft, modifier = Modifier.size(16.dp))
            Text("No signal", style = Type.Small, color = Palette.EmberSoft)
        }
    }
}

/** A small chip that runs a demo-only action. Never shown in production builds. */
@Composable
fun DemoChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(Palette.DemoFill, RoundedCornerShape(20.dp))
            .border(1.dp, Palette.DemoBorder, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AppIcons.Flask, null, tint = Palette.Demo, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Small, color = Palette.Demo)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Ember)
    }
}

@Composable
fun IconCircle(icon: ImageVector, tint: Color, fill: Color, size: Dp = 64.dp, iconSize: Dp = 32.dp) {
    Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Draws a short dashed rule, used where a value is still an estimate. */
@Composable
fun DashedRule(color: Color = Palette.Upcoming, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color,
            Offset(0f, 0f),
            Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
    }
}
