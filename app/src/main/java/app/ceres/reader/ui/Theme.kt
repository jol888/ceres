package app.ceres.reader.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
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

private val EinkTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp),
    )
}

/** Indication that draws nothing: pressed states would cause extra e-ink refreshes. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CeresTheme(invert: Boolean = false, content: @Composable () -> Unit) {
    val p = palette(invert)
    val typography = EinkTypography.copy(
        bodySmall = EinkTypography.bodySmall.copy(fontSize = 13.sp, color = p.gray),
    )
    MaterialTheme(colorScheme = colors(p, invert), typography = typography) {
        CompositionLocalProvider(
            LocalPalette provides p,
            LocalRippleConfiguration provides null,
            LocalIndication provides NoIndication,
            LocalOverscrollFactory provides null,
        ) {
            content()
        }
    }
}

val SmallText = TextStyle(fontSize = 13.sp, color = Color(0xFF666666))
