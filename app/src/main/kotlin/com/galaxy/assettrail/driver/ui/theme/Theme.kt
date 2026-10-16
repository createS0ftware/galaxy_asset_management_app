package com.galaxy.assettrail.driver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.galaxy.assettrail.driver.R

/** Colours from the design canvas (driver app artboards). */
object Palette {
    val Ink = Color(0xFF101214)
    val NavBar = Color(0xFF15181B)
    val Surface = Color(0xFF1A1D21)
    val SurfaceRaised = Color(0xFF2A2F35)
    val Border = Color(0xFF2E3338)
    val Divider = Color(0xFF23272C)
    val OutlineButton = Color(0xFF3A4047)
    val Upcoming = Color(0xFF5A6068)

    val Text = Color(0xFFF2F3EF)
    val TextSecondary = Color(0xFFA4AAB1)
    val TextLabel = Color(0xFFC9CCC6)

    /** Actions and "next". */
    val Ember = Color(0xFFF27A3D)
    val EmberSoft = Color(0xFFF5A27A)
    val OnEmber = Ink

    /** Verified on the ledger: always solid. */
    val Ledger = Color(0xFF8DB0F0)
    val LedgerSoft = Color(0xFFB5CCF6)
    val LedgerBadge = Color(0xFF1E2B45)

    val AdvanceBorder = Color(0xFF5A3A28)
    val AdvanceFill = Color(0xFF21170F)
    val AdvanceText = Color(0xFFD4C4B8)

    val Alert = Color(0xFFF4A99E)
    val AlertBorder = Color(0xFF5C2E2A)
    val AlertFill = Color(0xFF241514)

    val Demo = Color(0xFFC9B6F2)
    val DemoFill = Color(0xFF1F1A2B)
    val DemoBorder = Color(0xFF3D3355)
}

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val archivo = GoogleFont("Archivo")
private val plexMono = GoogleFont("IBM Plex Mono")

/** Archivo, downloaded through Google Play services; the system sans until it arrives. */
val Sans = FontFamily(
    Font(archivo, fontProvider, FontWeight.Normal),
    Font(archivo, fontProvider, FontWeight.Medium),
    Font(archivo, fontProvider, FontWeight.SemiBold),
    Font(archivo, fontProvider, FontWeight.Bold),
)

/** IBM Plex Mono for every amount, hash and code. */
val Mono = FontFamily(
    Font(plexMono, fontProvider, FontWeight.Normal),
    Font(plexMono, fontProvider, FontWeight.Medium),
)

object Type {
    val Display = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp)
    val Title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val Heading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val Subheading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)
    val Small = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Caption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Eyebrow = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.06.em,
    )
    val Button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)
    val ButtonSecondary = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)

    val MonoHero = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 48.sp)
    val MonoLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp)
    val MonoMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    val MonoBody = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val MonoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
}

private val colorScheme = darkColorScheme(
    primary = Palette.Ember,
    onPrimary = Palette.OnEmber,
    secondary = Palette.Ledger,
    onSecondary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface,
    surfaceContainerLow = Palette.NavBar,
    outline = Palette.OutlineButton,
    outlineVariant = Palette.Border,
    error = Palette.Alert,
    onError = Palette.Ink,
)

@Composable
fun AssetTrailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            bodyLarge = Type.Body,
            bodyMedium = Type.Body,
            bodySmall = Type.Small,
            titleLarge = Type.Title,
            titleMedium = Type.Heading,
            labelLarge = Type.ButtonSecondary,
        ),
        content = content,
    )
}
