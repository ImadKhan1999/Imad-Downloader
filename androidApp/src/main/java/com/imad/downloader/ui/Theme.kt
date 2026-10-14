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

val Brand = Color(0xFF1565C0)
val BrandBright = Color(0xFF1E88E5)
val Success = Color(0xFF2E9D4F)
val Warning = Color(0xFFE09B12)
val Danger = Color(0xFFD32F2F)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FB),
    onPrimaryContainer = Color(0xFF0A2D5C),
    secondary = Color(0xFF00897B),
    secondaryContainer = Color(0xFFCDEDE8),
    tertiary = Color(0xFF00C853),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F7),
    surfaceVariant = Color(0xFFE8EDF3),
    outlineVariant = Color(0xFFDDE3EA),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DB5F7),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF16406E),
    onPrimaryContainer = Color(0xFFD6E6FB),
    secondary = Color(0xFF4DB6AC),
    tertiary = Color(0xFF3DDC84),
    background = Color(0xFF111316),
    surface = Color(0xFF111316),
    surfaceContainer = Color(0xFF1B1E22),
    surfaceContainerLow = Color(0xFF181B1F),
    surfaceContainerHigh = Color(0xFF23272C),
    surfaceVariant = Color(0xFF2A2E34),
    outlineVariant = Color(0xFF33383F),
    error = Color(0xFFF26B6B),
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
