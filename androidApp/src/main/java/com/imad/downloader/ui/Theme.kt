package com.imad.downloader.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.imad.downloader.ThemeMode
import com.imad.downloader.core.Category

// ImadLabs brand colours (Electric Cobalt, Sky Diamond, Cyber Mint) and its dark surfaces.
val Brand = Color(0xFF0066FF)
val BrandBright = Color(0xFF38BDF8)
val Success = Color(0xFF10B981)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0B1930),
    secondary = Color(0xFF0058DB),
    secondaryContainer = Color(0xFFDCEBFF),
    tertiary = Color(0xFF00B377),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFF6F8FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F7),
    surfaceVariant = Color(0xFFE8EDF3),
    outlineVariant = Color(0xFFDDE3EA),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF041322),
    primaryContainer = Color(0xFF0B2A52),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFF38BDF8),
    secondaryContainer = Color(0xFF162032),
    tertiary = Color(0xFF00F5A0),
    background = Color(0xFF070B14),
    surface = Color(0xFF070B14),
    surfaceContainer = Color(0xFF0B0F19),
    surfaceContainerLow = Color(0xFF0B0F19),
    surfaceContainerHigh = Color(0xFF111827),
    surfaceVariant = Color(0xFF162032),
    outlineVariant = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444),
)

@Composable
fun ImadTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
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
    Category.COMPRESSED -> Color(0xFFE6A100)
    Category.DOCUMENTS -> Color(0xFF1E88E5)
    Category.MUSIC -> Color(0xFF9C27B0)
    Category.PROGRAMS -> Color(0xFF00A88F)
    Category.VIDEO -> Color(0xFFE53935)
    Category.IMAGES -> Color(0xFF43A047)
    Category.OTHER -> Color(0xFF607D8B)
}
