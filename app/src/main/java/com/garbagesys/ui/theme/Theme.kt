package com.garbagesys.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.garbagesys.R

// ── Brand Colors ──
val GreenPrimary    = Color(0xFF31F2B3)   // #31F2B3 — main brand
val GreenDim        = Color(0xFF1FB884)
val GreenGlow       = Color(0x4031F2B3)   // 25% alpha glow
val BgDeep          = Color(0xFF080D0B)   // near-black bg
val BgCard          = Color(0xFF0F1A15)   // card surface
val BgCardAlt       = Color(0xFF131F19)
val TextPrimary     = Color(0xFFE8FFF6)
val TextSecondary   = Color(0xFF7EB89A)
val TextMuted       = Color(0xFF3D6651)
val BorderColor     = Color(0xFF1C3028)
val ErrorRed        = Color(0xFFFF4D6D)
val WarnAmber       = Color(0xFFFFBD4A)
val WhaleBlue       = Color(0xFF4DAAFF)
val ProfitGreen     = Color(0xFF31F2B3)
val LossRed         = Color(0xFFFF4D6D)

// ── Typography — Inter ──
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_light, FontWeight.Light),
)

// ── Logo Font — Parafina Trial Black M ──
val ParafinaFamily = FontFamily(
    Font(R.font.parafina_black, FontWeight.Black)
)

val GarbageSysTypography = Typography(
    displayLarge  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,   fontSize = 32.sp, letterSpacing = (-0.5).sp, color = TextPrimary),
    displayMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,   fontSize = 24.sp, color = TextPrimary),
    displaySmall  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = TextPrimary),
    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,   fontSize = 18.sp, color = TextPrimary),
    headlineMedium= TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary),
    bodyLarge     = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = TextPrimary),
    bodyMedium    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = TextSecondary),
    bodySmall     = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = TextMuted),
    labelLarge    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = GreenPrimary),
    labelMedium   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = TextSecondary),
    labelSmall    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = TextMuted),
    titleLarge    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,   fontSize = 20.sp, color = TextPrimary),
    titleMedium   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary),
    titleSmall    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextSecondary),
)

private val DarkColorScheme = darkColorScheme(
    primary           = GreenPrimary,
    onPrimary         = BgDeep,
    primaryContainer  = BgCard,
    onPrimaryContainer= GreenPrimary,
    secondary         = GreenDim,
    onSecondary       = BgDeep,
    background        = BgDeep,
    onBackground      = TextPrimary,
    surface           = BgCard,
    onSurface         = TextPrimary,
    surfaceVariant    = BgCardAlt,
    onSurfaceVariant  = TextSecondary,
    outline           = BorderColor,
    error             = ErrorRed,
    onError           = Color.White,
)

@Composable
fun GarbageSysTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme  = DarkColorScheme,
        typography   = GarbageSysTypography,
        content      = content
    )
}
