package app.ceres.reader.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class CeresPalette(
    val ink: Color,
    val paper: Color,
    val gray: Color,
    val lightGray: Color,
)

val LocalPalette = staticCompositionLocalOf {
    CeresPalette(
        ink = Color.Black,
        paper = Color.White,
        gray = Color(0xFF666666),
        lightGray = Color(0xFFDDDDDD),
    )
}

/** Default (non-inverted) ink — keep for e-ink full-refresh flash. */
val FlashBlack = Color.Black

private fun palette(invert: Boolean) = if (invert) {
    CeresPalette(
        ink = Color.White,
        paper = Color.Black,
        gray = Color(0xFFAAAAAA),
        lightGray = Color(0xFF333333),
    )
} else {
    CeresPalette(
        ink = Color.Black,
        paper = Color.White,
        gray = Color(0xFF666666),
        lightGray = Color(0xFFDDDDDD),
    )
}

private fun colors(p: CeresPalette, invert: Boolean) = if (invert) {
    darkColorScheme(
        primary = p.ink, onPrimary = p.paper,
        primaryContainer = p.lightGray, onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.paper,
        secondaryContainer = p.lightGray, onSecondaryContainer = p.ink,
        tertiary = p.ink, onTertiary = p.paper,
        background = p.paper, onBackground = p.ink,
        surface = p.paper, onSurface = p.ink,
        surfaceVariant = p.paper, onSurfaceVariant = p.ink,
        surfaceTint = p.paper,
        surfaceContainerLowest = p.paper, surfaceContainerLow = p.paper, surfaceContainer = p.paper,
        surfaceContainerHigh = p.paper, surfaceContainerHighest = p.paper,
        inverseSurface = p.ink, inverseOnSurface = p.paper,
        outline = p.ink, outlineVariant = p.gray,
        error = p.ink, onError = p.paper, errorContainer = p.lightGray, onErrorContainer = p.ink,
        scrim = Color(0x66000000),
    )
} else {
    lightColorScheme(
        primary = p.ink, onPrimary = p.paper,
        primaryContainer = p.lightGray, onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.paper,
        secondaryContainer = p.lightGray, onSecondaryContainer = p.ink,
        tertiary = p.ink, onTertiary = p.paper,
        background = p.paper, onBackground = p.ink,
        surface = p.paper, onSurface = p.ink,
        surfaceVariant = p.paper, onSurfaceVariant = p.ink,
        surfaceTint = p.paper,
        surfaceContainerLowest = p.paper, surfaceContainerLow = p.paper, surfaceContainer = p.paper,
        surfaceContainerHigh = p.paper, surfaceContainerHighest = p.paper,
        inverseSurface = p.ink, inverseOnSurface = p.paper,
        outline = p.ink, outlineVariant = p.gray,
        error = p.ink, onError = p.paper, errorContainer = p.lightGray, onErrorContainer = p.ink,
        scrim = Color(0x66000000),
    )
}

/** Indication that draws nothing: pressed states would cause extra e-ink refreshes. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

private fun typography(p: CeresPalette): Typography {
    // Bake ink/gray into every style. Screen uses raw background() instead of Surface, so
    // LocalContentColor would otherwise stay default black and break invert mode titles.
    val base = Typography()
    fun TextStyle.ink(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight? = null) =
        copy(fontSize = size, fontWeight = weight ?: fontWeight, color = p.ink)
    return base.copy(
        displayLarge = base.displayLarge.ink(57.sp),
        displayMedium = base.displayMedium.ink(45.sp),
        displaySmall = base.displaySmall.ink(36.sp),
        headlineLarge = base.headlineLarge.ink(32.sp),
        headlineMedium = base.headlineMedium.ink(28.sp),
        headlineSmall = base.headlineSmall.ink(24.sp),
        titleLarge = base.titleLarge.ink(22.sp, FontWeight.Bold),
        titleMedium = base.titleMedium.ink(18.sp, FontWeight.Bold),
        titleSmall = base.titleSmall.ink(14.sp, FontWeight.Bold),
        bodyLarge = base.bodyLarge.ink(17.sp),
        bodyMedium = base.bodyMedium.ink(15.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, color = p.gray),
        labelLarge = base.labelLarge.ink(15.sp),
        labelMedium = base.labelMedium.ink(12.sp),
        labelSmall = base.labelSmall.ink(11.sp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CeresTheme(invert: Boolean = false, content: @Composable () -> Unit) {
    val p = palette(invert)
    val type = typography(p)
    MaterialTheme(colorScheme = colors(p, invert), typography = type) {
        CompositionLocalProvider(
            LocalPalette provides p,
            LocalContentColor provides p.ink,
            LocalTextStyle provides type.bodyLarge,
            LocalRippleConfiguration provides null,
            LocalIndication provides NoIndication,
            LocalOverscrollFactory provides null,
        ) {
            content()
        }
    }
}

val SmallText = TextStyle(fontSize = 13.sp, color = Color(0xFF666666))
