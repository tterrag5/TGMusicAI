package com.example.tgmusicai.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the app follows the system light/dark setting or pins one of them.
 *
 * Every palette in [AppTheme] now ships both a light and a dark variant, so this is a real choice
 * rather than a label -- previously every theme was dark-only and [TGMusicAITheme] accepted a
 * `darkTheme` flag it never actually applied.
 */
enum class ThemeMode(val displayName: String) {
    // Kept to one word each so the segmented buttons stay the same height -- "Follow system"
    // wrapped onto a second line and made the row look broken.
    SYSTEM("System"),
    LIGHT("Light"),
    /** Softened dark: dark text-on-dark, but lifted off near-black. See [ThemeSpec.toDim]. */
    DIM("Dim"),
    DARK("Dark");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/** Mixes this color toward [other] by [ratio] (0 = unchanged, 1 = fully [other]), staying opaque. */
private fun Color.blendWith(other: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * r,
        green = green + (other.green - green) * r,
        blue = blue + (other.blue - blue) * r,
        alpha = 1f
    )
}

/**
 * One palette's concrete colors for a single light/dark variant.
 *
 * Holding these as data rather than as a hand-written [ColorScheme] per theme is what makes the
 * theme list cheap to extend: [toColorScheme] derives the ~20 Material 3 roles the app actually
 * uses from these few colors, so adding a palette means adding colors, not another 25-line block.
 */
data class ThemeSpec(
    val background: Color,
    val surface: Color,
    val card: Color,
    val accent: Color,
    val onAccent: Color,
    val onBackground: Color,
    val muted: Color,
    val outline: Color
) {
    /**
     * A softened version of a dark palette: the near-black surfaces are lifted toward grey and the
     * text is pulled back from pure white, so the screen is still dark but much lower contrast --
     * the middle setting between Light and Dark, comparable to YouTube's "Dim" theme.
     *
     * Derived from the dark spec rather than authored per palette so every theme (and any theme
     * added later) gets a Dim variant automatically, with no third set of hex values to maintain.
     */
    fun toDim(): ThemeSpec {
        val lift = Color(0xFF9AA0A6)
        return copy(
            background = background.blendWith(lift, 0.16f),
            surface = surface.blendWith(lift, 0.16f),
            card = card.blendWith(lift, 0.18f),
            onBackground = onBackground.blendWith(background, 0.10f),
            muted = muted.blendWith(background, 0.06f),
            outline = outline.blendWith(lift, 0.18f)
        )
    }

    fun toColorScheme(isDark: Boolean): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        // Opaque, not accent.copy(alpha = ...): container roles are painted behind real content
        // (the playlist FAB, selected rows), and a translucent container let whatever sat underneath
        // bleed through -- the FAB read as a washed-out smudge over the cards behind it.
        val accentContainer = accent.blendWith(surface, if (isDark) 0.74f else 0.80f)
        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onBackground,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = card,
            onSecondaryContainer = onBackground,
            tertiary = muted,
            onTertiary = background,
            tertiaryContainer = card,
            onTertiaryContainer = onBackground,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = card,
            onSurfaceVariant = muted,
            surfaceContainerLowest = background,
            surfaceContainerLow = background,
            surfaceContainer = surface,
            surfaceContainerHigh = card,
            surfaceContainerHighest = card,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.5f)
        )
    }
}

/**
 * The selectable color palettes. [accentPreview] is what the Settings picker paints in each
 * swatch, so a theme is recognisable before it's applied.
 */
enum class AppTheme(
    val displayName: String,
    val dark: ThemeSpec,
    val light: ThemeSpec
) {
    YT_DARK(
        "YT Red",
        dark = ThemeSpec(
            background = Color(0xFF0F0F0F),
            surface = Color(0xFF0F0F0F),
            card = Color(0xFF1F1F1F),
            accent = Color(0xFFE53935),
            onAccent = Color.White,
            onBackground = Color.White,
            muted = Color(0xFFAAAAAA),
            outline = Color(0xFF666666)
        ),
        light = ThemeSpec(
            background = Color(0xFFFDFCFC),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFF2EDED),
            accent = Color(0xFFC62828),
            onAccent = Color.White,
            onBackground = Color(0xFF1A1616),
            muted = Color(0xFF6B5F5F),
            outline = Color(0xFFCBBFBF)
        )
    ),
    PASTEL_MIDNIGHT(
        "Pastel Midnight",
        dark = ThemeSpec(
            background = Color(0xFF121824),
            surface = Color(0xFF121824),
            card = Color(0xFF1B2436),
            accent = Color(0xFF4FD1C5),
            onAccent = Color(0xFF07201E),
            onBackground = Color(0xFFF0F4F8),
            muted = Color(0xFF94A3B8),
            outline = Color(0xFF64748B)
        ),
        light = ThemeSpec(
            background = Color(0xFFF7FAFC),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFE6EEF4),
            accent = Color(0xFF0D9488),
            onAccent = Color.White,
            onBackground = Color(0xFF14202E),
            muted = Color(0xFF5A6B7D),
            outline = Color(0xFFB6C4D2)
        )
    ),
    WARM_AMBER(
        "Warm Amber",
        dark = ThemeSpec(
            background = Color(0xFF1C1917),
            surface = Color(0xFF1C1917),
            card = Color(0xFF292524),
            accent = Color(0xFFF59E0B),
            onAccent = Color(0xFF231703),
            onBackground = Color(0xFFF5F5F4),
            muted = Color(0xFFA8A29E),
            outline = Color(0xFF78716C)
        ),
        light = ThemeSpec(
            background = Color(0xFFFFFBF4),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFF6EDDF),
            accent = Color(0xFFB45309),
            onAccent = Color.White,
            onBackground = Color(0xFF231A0E),
            muted = Color(0xFF6F6353),
            outline = Color(0xFFD6C6AC)
        )
    ),
    NORDIC_SLATE(
        "Nordic Slate",
        dark = ThemeSpec(
            background = Color(0xFF1E222A),
            surface = Color(0xFF1E222A),
            card = Color(0xFF282C34),
            accent = Color(0xFF61AFEF),
            onAccent = Color(0xFF071726),
            onBackground = Color(0xFFD7DDE6),
            muted = Color(0xFF828997),
            outline = Color(0xFF5C6370)
        ),
        light = ThemeSpec(
            background = Color(0xFFF6F8FB),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFE7EDF5),
            accent = Color(0xFF2563EB),
            onAccent = Color.White,
            onBackground = Color(0xFF16202C),
            muted = Color(0xFF5B6878),
            outline = Color(0xFFBDC9D8)
        )
    ),
    VIOLET_DUSK(
        "Violet Dusk",
        dark = ThemeSpec(
            background = Color(0xFF15111F),
            surface = Color(0xFF15111F),
            card = Color(0xFF221B31),
            accent = Color(0xFFA78BFA),
            onAccent = Color(0xFF1B0F33),
            onBackground = Color(0xFFEDE9F6),
            muted = Color(0xFF9E93B8),
            outline = Color(0xFF6C6186)
        ),
        light = ThemeSpec(
            background = Color(0xFFFBF9FF),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFEFEAFA),
            accent = Color(0xFF6D28D9),
            onAccent = Color.White,
            onBackground = Color(0xFF1D1730),
            muted = Color(0xFF655C7D),
            outline = Color(0xFFC8BDE2)
        )
    ),
    FOREST(
        "Forest",
        dark = ThemeSpec(
            background = Color(0xFF0F1A14),
            surface = Color(0xFF0F1A14),
            card = Color(0xFF1A281F),
            accent = Color(0xFF4ADE80),
            onAccent = Color(0xFF052B14),
            onBackground = Color(0xFFE6F2EA),
            muted = Color(0xFF8FA899),
            outline = Color(0xFF5B7566)
        ),
        light = ThemeSpec(
            background = Color(0xFFF5FBF7),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFE4F1E9),
            accent = Color(0xFF15803D),
            onAccent = Color.White,
            onBackground = Color(0xFF122018),
            muted = Color(0xFF546B5D),
            outline = Color(0xFFB4CCBE)
        )
    ),
    ROSE_QUARTZ(
        "Rose Quartz",
        dark = ThemeSpec(
            background = Color(0xFF1B1317),
            surface = Color(0xFF1B1317),
            card = Color(0xFF2A1D24),
            accent = Color(0xFFF472B6),
            onAccent = Color(0xFF3A0C22),
            onBackground = Color(0xFFF7E9F0),
            muted = Color(0xFFB396A4),
            outline = Color(0xFF7E6371)
        ),
        light = ThemeSpec(
            background = Color(0xFFFFF8FB),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFF9E8F0),
            accent = Color(0xFFBE185D),
            onAccent = Color.White,
            onBackground = Color(0xFF2A121D),
            muted = Color(0xFF7A5C68),
            outline = Color(0xFFE0BFCF)
        )
    ),
    MONO(
        "Mono",
        dark = ThemeSpec(
            background = Color(0xFF101010),
            surface = Color(0xFF101010),
            card = Color(0xFF1E1E1E),
            accent = Color(0xFFE4E4E4),
            onAccent = Color(0xFF101010),
            onBackground = Color(0xFFF2F2F2),
            muted = Color(0xFF9B9B9B),
            outline = Color(0xFF5E5E5E)
        ),
        light = ThemeSpec(
            background = Color(0xFFFAFAFA),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFEDEDED),
            accent = Color(0xFF1F1F1F),
            onAccent = Color.White,
            onBackground = Color(0xFF141414),
            muted = Color(0xFF636363),
            outline = Color(0xFFC4C4C4)
        )
    );

    /** The color the Settings picker shows in this theme's swatch. */
    val accentPreview: Color get() = dark.accent

    companion object {
        fun fromName(name: String?): AppTheme =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: YT_DARK
    }
}

/** Resolves [themeName] and the effective light/dim/dark [mode] into a Material 3 scheme. */
fun getSoftColorScheme(themeName: String, mode: ThemeMode = ThemeMode.DARK): ColorScheme {
    val theme = AppTheme.fromName(themeName)
    return when (mode) {
        ThemeMode.LIGHT -> theme.light.toColorScheme(isDark = false)
        ThemeMode.DIM -> theme.dark.toDim().toColorScheme(isDark = true)
        // SYSTEM is resolved to a concrete mode before reaching here.
        else -> theme.dark.toColorScheme(isDark = true)
    }
}

/**
 * Applies the user's chosen palette, light/dark mode and (optionally) Material You dynamic color.
 *
 * [dynamicColor] only takes effect on Android 12+, where it derives the scheme from the device
 * wallpaper and therefore ignores [themeName] -- the Settings toggle makes that trade-off explicit
 * rather than silently overriding the palette the user picked.
 */
@Composable
fun TGMusicAITheme(
    themeName: String = AppTheme.YT_DARK.name,
    themeMode: ThemeMode = ThemeMode.DARK,
    systemInDarkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // SYSTEM resolves to a concrete mode here so everything downstream deals with a real choice.
    val effectiveMode = if (themeMode == ThemeMode.SYSTEM) {
        if (systemInDarkTheme) ThemeMode.DARK else ThemeMode.LIGHT
    } else {
        themeMode
    }
    val isDark = effectiveMode != ThemeMode.LIGHT

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> getSoftColorScheme(themeName, effectiveMode)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
