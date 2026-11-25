package com.nx.android.agent.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The assistant's palette — light repair-ticket direction (检修单): light warm greys, ink text,
 * one brand accent, serif display for headings, mono for anything the machine said. The app around
 * it stays dark; the drawer is a bright workbench on top.
 */
object AgentPalette {
    val Ground = Color(0xFFF8F8F7)
    val Panel = Color(0xFFFFFFFF)
    val Hairline = Color(0xFFE5E7EA)
    val Ink = Color(0xFF14161A)
    val Ink2 = Color(0xFF6B7078)
    val Cream = Color(0xFF141416)
    val CreamInk = Color(0xFFFFF0D6)
    val Orange = Color(0xFFFF8A00)
    /** 品牌橙的深一档：文字用（白底上读得清），亮橙只做大面积的填充。 */
    val Accent = Color(0xFFB45309)
    val Ready = Color(0xFF2FBF63)
    val Working = Color(0xFFE6A23C)
    val Idle = Color(0xFF9CA3AF)
    val Danger = Color(0xFFEF4444)
    val CodeBg = Color(0xFF1E2126)
    val CodeInk = Color(0xFFE4E7EB)
    val Cmd = Color(0xFFA0AEC0)
    val Quick = Color(0xFF374151)
    val DrawerBg = Color(0xFFF8F8F7)

    /** The player's bubble: a faint navy wash, readable on the white ground. */
    val Bubble = Color(0x0D101828)
    val BubbleBorder = Color(0x18101828)
}

/** The light scheme: every Material component inside the agent UI resolves ink-on-white. */
val AgentLightScheme = lightColorScheme(
    background = AgentPalette.Ground,
    surface = AgentPalette.Panel,
    surfaceVariant = Color(0xFFF1F2F4),
    onSurface = AgentPalette.Ink,
    onBackground = AgentPalette.Ink,
    onSurfaceVariant = AgentPalette.Ink2,
    outline = AgentPalette.Hairline,
    outlineVariant = AgentPalette.Hairline,
    primary = AgentPalette.Orange,
    onPrimary = Color.White,
    secondary = AgentPalette.Cream,
    onSecondary = AgentPalette.Ink,
    error = AgentPalette.Danger,
)

/**
 * The type scale — one base rules the whole sheet: the player's bubble and the answer's body are
 * both 13sp/21 (the user's rule), headings serif, machine output mono. Sizes live here so every
 * `MaterialTheme.typography` call inside the agent UI lands on the scale without per-call copies.
 */
val AgentTypography = Typography(
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
)

/** The serif headings the answers use (the sheet's display voice). */
val AgentHeading = FontFamily.Serif
