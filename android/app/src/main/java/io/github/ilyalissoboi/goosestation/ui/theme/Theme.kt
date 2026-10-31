// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ilyalissoboi.goosestation.R

/**
 * The design system's colours, dark theme: night on the pond, the goose's white for text, its beak for the one
 * accent. The app is always dark: it's played on a TV or a handheld, often in a dim room.
 */
@Immutable
data class GsColors(
    val ground: Color = Color(0xFF0E1116),
    val surface: Color = Color(0xFF161A21),
    val raised: Color = Color(0xFF1F242D),
    val sunken: Color = Color(0xFF0A0C10),
    val line: Color = Color(0xFF2C323D),
    val lineStrong: Color = Color(0xFF6A7383),
    val ink: Color = Color(0xFFF3F1EC),
    val inkMuted: Color = Color(0xFFA7AEBB),
    val inkFaint: Color = Color(0xFF6B7381),
    val beak: Color = Color(0xFFFF7A1A),
    val beakHover: Color = Color(0xFFFF9447),
    // Orange as text or a thin mark; in the dark theme it's the beak itself.
    val beakInk: Color = Color(0xFFFF7A1A),
    val onBeak: Color = Color(0xFF1A0B00),
    val beakSoft: Color = Color(0xFF3A2312),
    val focus: Color = Color(0xFFFF7A1A),
    val info: Color = Color(0xFF7AA7FF),
    val infoSoft: Color = Color(0xFF1A2742),
    val success: Color = Color(0xFF3CC9B4),
    val successSoft: Color = Color(0xFF12302D),
    val warning: Color = Color(0xFFF5C542),
    val warningSoft: Color = Color(0xFF3A3014),
    val danger: Color = Color(0xFFFF6B81),
    val dangerSoft: Color = Color(0xFF3D1820),
    val scrim: Color = Color(0xB805070A),
)

/** The design system's styles Material's type scale has no slot for. */
@Immutable
data class GsType(
    val display: TextStyle,
    val eyebrow: TextStyle,
    val key: TextStyle,
    val mono: TextStyle,
)

private val chakraPetch =
    FontFamily(
        Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
        Font(R.font.chakra_petch_bold, FontWeight.Bold),
    )

private fun variable(
    res: Int,
    weight: Int,
) = Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val atkinson =
    FontFamily(
        variable(R.font.atkinson_hyperlegible_next, 400),
        variable(R.font.atkinson_hyperlegible_next, 600),
        variable(R.font.atkinson_hyperlegible_next, 700),
    )

private val atkinsonMono =
    FontFamily(
        variable(R.font.atkinson_hyperlegible_mono, 500),
        variable(R.font.atkinson_hyperlegible_mono, 600),
    )

private fun style(
    family: FontFamily,
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
)

// Material's slots, filled with the design system's styles: headlines and titles in Chakra Petch, what's read or
// pressed in Atkinson Hyperlegible Next.
private val typography =
    Typography(
        displaySmall = style(chakraPetch, 40, 44, FontWeight.SemiBold, -0.4f),
        // title-1: screen titles.
        headlineMedium = style(chakraPetch, 28, 32, FontWeight.SemiBold, -0.2f),
        // title-2: dialog titles, a prompt's game.
        headlineSmall = style(chakraPetch, 20, 24, FontWeight.SemiBold),
        // title-2: section headings, a setup step's heading.
        titleLarge = style(chakraPetch, 20, 24, FontWeight.SemiBold),
        // label: anything pressed or picked.
        titleMedium = style(atkinson, 16, 20, FontWeight.SemiBold),
        // label-sm.
        titleSmall = style(atkinson, 14, 18, FontWeight.SemiBold),
        labelLarge = style(atkinson, 14, 18, FontWeight.SemiBold),
        labelMedium = style(atkinson, 12, 16, FontWeight.SemiBold),
        // body.
        bodyLarge = style(atkinson, 16, 24, FontWeight.Normal),
        // body-sm: notes, details.
        bodyMedium = style(atkinson, 14, 20, FontWeight.Normal),
        bodySmall = style(atkinson, 12, 16, FontWeight.Normal),
    )

private val gsType =
    GsType(
        display = typography.displaySmall,
        eyebrow = style(chakraPetch, 12, 16, FontWeight.SemiBold, 1f),
        key = style(chakraPetch, 12, 16, FontWeight.Bold),
        mono = style(atkinsonMono, 14, 20, FontWeight.Medium),
    )

/** The design system's radii: no pills, no circles. */
object GsShapes {
    val small = RoundedCornerShape(4.dp)
    val medium = RoundedCornerShape(8.dp)
    val large = RoundedCornerShape(16.dp)
}

private val gsColors = GsColors()

private val colorScheme =
    with(gsColors) {
        darkColorScheme(
            primary = beak,
            onPrimary = onBeak,
            primaryContainer = beakSoft,
            onPrimaryContainer = ink,
            inversePrimary = beakInk,
            secondary = success,
            onSecondary = ground,
            secondaryContainer = successSoft,
            onSecondaryContainer = ink,
            tertiary = info,
            onTertiary = ground,
            tertiaryContainer = infoSoft,
            onTertiaryContainer = ink,
            background = ground,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = raised,
            onSurfaceVariant = inkMuted,
            surfaceTint = beak,
            inverseSurface = ink,
            inverseOnSurface = ground,
            error = danger,
            onError = ground,
            errorContainer = dangerSoft,
            onErrorContainer = ink,
            outline = lineStrong,
            outlineVariant = line,
            scrim = scrim,
            surfaceBright = raised,
            surfaceDim = ground,
            surfaceContainerLowest = sunken,
            surfaceContainerLow = ground,
            surfaceContainer = surface,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = raised,
        )
    }

private val shapes =
    Shapes(
        extraSmall = GsShapes.small,
        small = GsShapes.small,
        medium = GsShapes.medium,
        large = GsShapes.large,
        extraLarge = GsShapes.large,
    )

private val LocalGsColors = staticCompositionLocalOf { gsColors }
private val LocalGsType = staticCompositionLocalOf { gsType }

/** The design system's colours and extra styles, beside [MaterialTheme]'s. */
object GooseStationTheme {
    val colors: GsColors
        @Composable @ReadOnlyComposable
        get() = LocalGsColors.current

    val type: GsType
        @Composable @ReadOnlyComposable
        get() = LocalGsType.current
}

@Composable
fun GooseStationTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGsColors provides gsColors, LocalGsType provides gsType) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
    }
}
