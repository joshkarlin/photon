package app.photon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.designVerticalPxToSp

/**
 * Photon's look is the Light SDK's: [LightTheme] in its dark palette (black
 * background, white content, `contentSecondary` for de-emphasised text) with
 * the LightOS type scale in Akkurat. Haptics are on, as in LightOS: SDK tools
 * get the user's preference from LightOS, which an ordinary app can't read.
 */
@Composable
fun PhotonTheme(content: @Composable () -> Unit) {
    LightTheme(colors = LightThemeColors.Dark) {
        CompositionLocalProvider(LocalHapticsEnabled provides true, content = content)
    }
}

/**
 * Colours beyond the SDK palette. Everything else uses
 * `LightThemeTokens.colors` (content, contentSecondary, background).
 */
object PhotonColors {
    /**
     * Text that must recede further than `contentSecondary`: deleted-message
     * placeholders, the empty input hint and disabled actions.
     */
    val Faint = Color(0xFF666666)
}

/** Layout measurements in LP3 grid units (`gridUnitsAsDp()`), kept together for tuning. */
object PhotonGrid {
    /**
     * Side margins of every page's content, as LightOS lays out Messages,
     * Settings and Search: on the LP3's 1080px, content spans x 80–1000.
     * Scroll views span the full width and inset their content by this on
     * both sides, so the SDK's scroll bar sits in the right margin, by the
     * screen's edge ([PageScrollView][app.photon.ui.shared.components.PageScrollView]).
     */
    const val MARGIN = 2f

    /**
     * Height of the SDK's `LightTopBar`. LightOS Messages, which has no top
     * bar, leaves this much empty above its list.
     */
    const val TOP_BAR_HEIGHT = 3f

    /** Height of a menu row, as LightOS Settings spaces its rows. */
    const val MENU_ROW_HEIGHT = 5f

    /** Height of a conversation-list row, as LightOS Messages spaces its rows. */
    const val LIST_ROW_HEIGHT = 4f

    /** Side margins of the message thread (the LP3 thread uses two units). */
    const val CHAT_MARGIN = 2f

    /** Widest a message body may grow before wrapping. */
    const val MESSAGE_MAX_WIDTH = 21f

    /** Space above the first message of a burst. */
    const val BURST_GAP = 1f

    /** Space between messages within a burst. */
    const val IN_BURST_GAP = 0.25f
}

/**
 * The [TextStyle] behind a [LightTextVariant], scaled the way `LightText`
 * scales it, for components that take a style rather than a variant (text
 * fields, line-height maths).
 */
@Composable
fun lightTextStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Title -> t.title
        LightTextVariant.Subtitle -> t.subtitle
        LightTextVariant.Heading -> t.heading
        LightTextVariant.Subheading -> t.subheading
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Button -> t.button
        LightTextVariant.Paragraph -> t.paragraph
        LightTextVariant.ParagraphWide -> t.paragraphWide
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Fine -> t.fine
        LightTextVariant.Superfine -> t.superfine
        LightTextVariant.Micro -> t.micro
    }
    return base.copy(
        fontSize = base.fontSize.scaled(),
        lineHeight = base.lineHeight.scaled(),
        letterSpacing = base.letterSpacing.scaled(),
    )
}

@Composable
private fun TextUnit.scaled(): TextUnit =
    if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()
