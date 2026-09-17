package com.example.tgmusicai.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppTheme(val displayName: String) {
    YT_DARK("YT Dark"),
    PASTEL_MIDNIGHT("Pastel Midnight"),
    WARM_AMBER("Warm Amber"),
    NORDIC_SLATE("Nordic Slate");

    companion object {
        fun fromName(name: String?): AppTheme {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: YT_DARK
        }
    }
}

fun getSoftColorScheme(themeName: String): ColorScheme {
    val theme = AppTheme.fromName(themeName)
    return when (theme) {
        AppTheme.YT_DARK -> darkColorScheme(
            primary = YTDarkAccent,
            onPrimary = Color.White,
            primaryContainer = YTDarkAccent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = YTDarkAccent,
            onSecondary = Color.White,
            secondaryContainer = YTDarkCard,
            onSecondaryContainer = Color.White,
            tertiary = YTGrey,
            onTertiary = YTBlack,
            tertiaryContainer = YTDarkCard,
            onTertiaryContainer = Color.White,
            background = YTDarkBackground,
            onBackground = Color.White,
            surface = YTDarkBackground,
            onSurface = Color.White,
            surfaceVariant = YTDarkCard,
            onSurfaceVariant = Color(0xFFAAAAAA),
            surfaceContainer = YTDarkBackground,
            surfaceContainerHigh = YTDarkCard,
            surfaceContainerHighest = YTDarkCard,
            surfaceContainerLow = YTDarkBackground,
            outline = Color(0xFF666666)
        )
        AppTheme.PASTEL_MIDNIGHT -> darkColorScheme(
            primary = PastelMidnightAccent,
            onPrimary = Color(0xFF121824),
            primaryContainer = PastelMidnightAccent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = PastelMidnightAccent,
            onSecondary = Color(0xFF121824),
            secondaryContainer = PastelMidnightCard,
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFF94A3B8),
            onTertiary = Color(0xFF121824),
            tertiaryContainer = PastelMidnightCard,
            onTertiaryContainer = Color.White,
            background = PastelMidnightBackground,
            onBackground = Color(0xFFF0F4F8),
            surface = PastelMidnightBackground,
            onSurface = Color(0xFFF0F4F8),
            surfaceVariant = PastelMidnightCard,
            onSurfaceVariant = Color(0xFF94A3B8),
            surfaceContainer = PastelMidnightBackground,
            surfaceContainerHigh = PastelMidnightCard,
            surfaceContainerHighest = PastelMidnightCard,
            surfaceContainerLow = PastelMidnightBackground,
            outline = Color(0xFF64748B)
        )
        AppTheme.WARM_AMBER -> darkColorScheme(
            primary = WarmAmberAccent,
            onPrimary = Color(0xFF1C1917),
            primaryContainer = WarmAmberAccent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = WarmAmberAccent,
            onSecondary = Color(0xFF1C1917),
            secondaryContainer = WarmAmberCard,
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFA8A29E),
            onTertiary = Color(0xFF1C1917),
            tertiaryContainer = WarmAmberCard,
            onTertiaryContainer = Color.White,
            background = WarmAmberBackground,
            onBackground = Color(0xFFF5F5F4),
            surface = WarmAmberBackground,
            onSurface = Color(0xFFF5F5F4),
            surfaceVariant = WarmAmberCard,
            onSurfaceVariant = Color(0xFFA8A29E),
            surfaceContainer = WarmAmberBackground,
            surfaceContainerHigh = WarmAmberCard,
            surfaceContainerHighest = WarmAmberCard,
            surfaceContainerLow = WarmAmberBackground,
            outline = Color(0xFF78716C)
        )
        AppTheme.NORDIC_SLATE -> darkColorScheme(
            primary = NordicSlateAccent,
            onPrimary = Color(0xFF1E222A),
            primaryContainer = NordicSlateAccent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = NordicSlateAccent,
            onSecondary = Color(0xFF1E222A),
            secondaryContainer = NordicSlateCard,
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFF828997),
            onTertiary = Color(0xFF1E222A),
            tertiaryContainer = NordicSlateCard,
            onTertiaryContainer = Color.White,
            background = NordicSlateBackground,
            onBackground = Color(0xFFABB2BF),
            surface = NordicSlateBackground,
            onSurface = Color(0xFFABB2BF),
            surfaceVariant = NordicSlateCard,
            onSurfaceVariant = Color(0xFF828997),
            surfaceContainer = NordicSlateBackground,
            surfaceContainerHigh = NordicSlateCard,
            surfaceContainerHighest = NordicSlateCard,
            surfaceContainerLow = NordicSlateBackground,
            outline = Color(0xFF5C6370)
        )
    }
}

private const val CHART_HUE_STEP = 0.618033988749895f * 360f

/**
 * Generates [count] perceptually distinct, vibrant colors for pie/donut charts and multi-item
 * bars. Steps hue by the golden-ratio conjugate instead of cycling fixed ColorScheme roles, so
 * two slices never collide even when a theme maps two roles (e.g. primary/secondary) to the
 * same hue, as every [AppTheme] here does.
 */
fun generateDistinctChartPalette(count: Int, isDarkTheme: Boolean = true): List<Color> {
    if (count <= 0) return emptyList()
    val saturation = if (isDarkTheme) 0.80f else 0.70f
    val lightness = if (isDarkTheme) 0.62f else 0.45f
    var hue = 20f
    return List(count) {
        val color = Color.hsl(hue = hue, saturation = saturation, lightness = lightness)
        hue = (hue + CHART_HUE_STEP) % 360f
        color
    }
}

@Composable
fun TGMusicAITheme(
    themeName: String = "YT_DARK",
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        else -> getSoftColorScheme(themeName)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
