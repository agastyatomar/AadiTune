package com.zionhuang.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.google.material.color.dynamiccolor.DynamicScheme
import com.google.material.color.hct.Hct
import com.google.material.color.scheme.SchemeContent
import com.google.material.color.scheme.SchemeExpressive
import com.google.material.color.scheme.SchemeFruitSalad
import com.google.material.color.scheme.SchemeMonochrome
import com.google.material.color.scheme.SchemeNeutral
import com.google.material.color.scheme.SchemeRainbow
import com.google.material.color.scheme.SchemeTonalSpot
import com.google.material.color.scheme.SchemeVibrant
import com.google.material.color.score.Score
import com.zionhuang.music.constants.ThemeVariant

val DefaultThemeColor = Color(0xFF7C3AED)

@Composable
fun NovaTuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    themeVariant: ThemeVariant = ThemeVariant.TONAL_SPOT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(darkTheme, pureBlack, themeColor, themeVariant) {
        if (themeColor == DefaultThemeColor && themeVariant == ThemeVariant.TONAL_SPOT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context).pureBlack(pureBlack)
            else dynamicLightColorScheme(context)
        } else {
            val scheme = when (themeVariant) {
                ThemeVariant.TONAL_SPOT -> SchemeTonalSpot(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.VIBRANT -> SchemeVibrant(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.EXPRESSIVE -> SchemeExpressive(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.CONTENT -> SchemeContent(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.NEUTRAL -> SchemeNeutral(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.MONOCHROME -> SchemeMonochrome(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.RAINBOW -> SchemeRainbow(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
                ThemeVariant.FRUIT_SALAD -> SchemeFruitSalad(Hct.fromInt(themeColor.toArgb()), darkTheme, 0.0)
            }
            scheme
                .toColorScheme()
                .pureBlack(darkTheme && pureBlack)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

fun Bitmap.extractThemeColor(): Color {
    val colorsToPopulation = Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .swatches
        .associate { it.rgb to it.population }
    val rankedColors = Score.score(colorsToPopulation)
    return Color(rankedColors.first())
}

fun DynamicScheme.toColorScheme() = ColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(primary),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest)
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
