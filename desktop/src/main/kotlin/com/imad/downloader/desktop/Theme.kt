package com.imad.downloader.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.imad.downloader.core.Category

/** Colours that Material does not cover: the IDM-like table and toolbar. */
@Immutable
data class Palette(
    val window: Color,
    val toolbar: Color,
    val toolbarBorder: Color,
    val sidebar: Color,
    val tableHeader: Color,
    val rowAlt: Color,
    val rowSelected: Color,
    val gridLine: Color,
    val progressTrack: Color,
    val progress: Color,
    val complete: Color,
    val error: Color,
    val paused: Color,
    val muted: Color,
    val segmentDone: Color,
    val segmentActive: Color,
)

val Brand = Color(0xFF4F46E5)
val BrandLight = Color(0xFF6366F1)
val Accent = Color(0xFF06B6D4)

private val LightPalette = Palette(
    window = Color(0xFFF5F6FB),
    toolbar = Color(0xFFFFFFFF),
    toolbarBorder = Color(0xFFD9E1EC),
    sidebar = Color(0xFFFAFBFD),
    tableHeader = Color(0xFFEDF2F8),
    rowAlt = Color(0xFFF8FAFD),
    rowSelected = Color(0xFFE0E3FF),
    gridLine = Color(0xFFE3E8EF),
    progressTrack = Color(0xFFE1E7EF),
    progress = Color(0xFF4F46E5),
    complete = Color(0xFF2E9D4F),
    error = Color(0xFFD32F2F),
    paused = Color(0xFFE09B12),
    muted = Color(0xFF6B7685),
    segmentDone = Color(0xFF6366F1),
    segmentActive = Color(0xFF06B6D4),
)

private val DarkPalette = Palette(
    window = Color(0xFF1B1D21),
    toolbar = Color(0xFF24272C),
    toolbarBorder = Color(0xFF33373E),
    sidebar = Color(0xFF202327),
    tableHeader = Color(0xFF2A2E34),
    rowAlt = Color(0xFF1F2226),
    rowSelected = Color(0xFF2D2B63),
    gridLine = Color(0xFF30343A),
    progressTrack = Color(0xFF353A42),
    progress = Color(0xFF818CF8),
    complete = Color(0xFF4CC26D),
    error = Color(0xFFF26B6B),
    paused = Color(0xFFF0B43C),
    muted = Color(0xFF9AA4B2),
    segmentDone = Color(0xFF818CF8),
    segmentActive = Color(0xFF22D3EE),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

private val lightScheme: ColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFF4338CA),
    tertiary = Accent,
    background = LightPalette.window,
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F8),
    outline = Color(0xFFC3CCD8),
    error = LightPalette.error,
)

private val darkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    secondary = Color(0xFFA5B4FC),
    tertiary = Accent,
    background = DarkPalette.window,
    surface = Color(0xFF25282D),
    surfaceVariant = Color(0xFF2C3036),
    outline = Color(0xFF4A505A),
    error = DarkPalette.error,
)

private val typography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 14.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    )
}

val CellText = TextStyle(fontSize = 13.sp)

@Composable
fun ImadTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = typography, content = content)
    }
}

fun Category.icon(): ImageVector = when (this) {
    Category.COMPRESSED -> Icons.Filled.Archive
    Category.DOCUMENTS -> Icons.Filled.Description
    Category.MUSIC -> Icons.Filled.MusicNote
    Category.PROGRAMS -> Icons.Filled.Apps
    Category.VIDEO -> Icons.Filled.Movie
    Category.IMAGES -> Icons.Filled.Image
    Category.OTHER -> Icons.Filled.InsertDriveFile
}

fun Category.tint(): Color = when (this) {
    Category.COMPRESSED -> Color(0xFFB8860B)
    Category.DOCUMENTS -> Color(0xFF1976D2)
    Category.MUSIC -> Color(0xFF8E24AA)
    Category.PROGRAMS -> Color(0xFF00897B)
    Category.VIDEO -> Color(0xFFE53935)
    Category.IMAGES -> Color(0xFF43A047)
    Category.OTHER -> Color(0xFF607D8B)
}
