package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Immutable
data class NuvioSurfaceColors(
    val background: Color,
    val raised: Color,
    val card: Color,
    val default: Color,
    val variant: Color,
    val panel: Color,
    val overlay: Color,
    val field: Color,
    val menu: Color,
    val modal: Color,
    val playerOverlay: Color,
    val divider: Color
)

@Immutable
data class NuvioTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val inverse: Color,
    val onAccent: Color,
    val onOverlay: Color,
    val metadata: Color
)

@Immutable
data class NuvioFocusColors(
    val ring: Color,
    val background: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color
)

@Immutable
data class NuvioSelectionColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val mutedBackground: Color,
    val mutedForeground: Color
)

@Immutable
data class NuvioMediaColors(
    val heroScrim: Color,
    val imageScrim: Color,
    val posterFallback: Color,
    val videoControlsScrim: Color,
    val glassPanelTop: Color,
    val glassPanelMiddle: Color,
    val glassPanelBottom: Color,
    val glow: Color
)

@Immutable
data class NuvioStatusColors(
    val rating: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    val watched: Color,
    val unwatched: Color,
    val cached: Color,
    val torrent: Color,
    val premium: Color
)

@Immutable
data class NuvioDisabledColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val overlay: Color
)

@Immutable
data class NuvioSourceColors(
    val trakt: Color,
    val tmdb: Color,
    val imdb: Color,
    val mdblist: Color
)

@Immutable
data class NuvioContrastPair(
    val foreground: Color,
    val background: Color
)

class NuvioColorScheme(
    palette: ThemeColorPalette,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    lightMode: Boolean = false
) {
    private val pureBlack = NuvioPrimitives.black
    private val pureBlackSurfaces = !lightMode && amoledMode && amoledSurfacesMode
    private val light = lightMode

    /** True when the scheme renders light surfaces. Screens use this to tune scrims. */
    val isLight: Boolean = lightMode

    // Light palette: a soft neutral canvas instead of pure white. Large white areas
    // glare on a TV, so the canvas sits a step below white and surfaces are off-white;
    // text keeps a near-neutral ink tone rather than pure black.
    private val lightBackground = Color(0xFFE7EAF0)
    private val lightElevated = Color(0xFFFAFBFC)
    private val lightSurface = Color(0xFFF7F8FA)
    private val lightField = Color(0xFFEDF0F4)
    private val lightPanel = Color(0xFFF1F3F7)
    private val lightMenu = Color(0xFFF8F9FB)
    private val lightSurfaceVariant = Color(0xFFDCE1E8)
    private val lightDivider = Color(0xFFC8CED7)
    private val lightTextPrimary = Color(0xFF17191C)
    private val lightTextSecondary = Color(0xFF4E545C)
    private val lightTextTertiary = Color(0xFF7C838C)
    private val paleAccentInLight = light && palette.secondary.luminance() > 0.7f
    private val lightFocusRing = if (palette.secondary.luminance() > 0.7f) {
        NuvioPrimitives.neutral800
    } else {
        palette.secondary
    }

    val Background = when {
        light -> lightBackground
        amoledMode -> pureBlack
        else -> palette.background
    }
    val BackgroundElevated = if (light) lightElevated else if (pureBlackSurfaces) pureBlack else palette.backgroundElevated
    val BackgroundCard = if (light) lightSurface else if (pureBlackSurfaces) pureBlack else palette.backgroundCard
    val Surface = if (light) lightSurface else if (pureBlackSurfaces) pureBlack else palette.surface
    val SurfaceVariant = if (light) lightSurfaceVariant else if (pureBlackSurfaces) pureBlack else palette.surfaceVariant
    val Panel = if (light) lightPanel else if (pureBlackSurfaces) pureBlack else palette.panel
    val Overlay = palette.overlay
    val Field = if (light) lightField else if (pureBlackSurfaces) pureBlack else palette.field
    val Menu = if (light) lightMenu else if (pureBlackSurfaces) pureBlack else palette.menu
    val Modal = if (light) lightMenu else if (pureBlackSurfaces) pureBlack else palette.modal
    val PlayerOverlay = palette.playerOverlay
    val Divider = if (light) lightDivider else NuvioPrimitives.neutral750

    val Primary = NuvioPrimitives.neutral500
    val PrimaryVariant = NuvioPrimitives.neutral650
    val OnPrimary = NuvioPrimitives.white
    val Secondary = if (paleAccentInLight) NuvioPrimitives.neutral800 else palette.secondary
    val SecondaryVariant = if (paleAccentInLight) NuvioPrimitives.neutral700 else palette.secondaryVariant
    val OnSecondary = if (paleAccentInLight) NuvioPrimitives.white else palette.onSecondary
    val OnSecondaryVariant = if (paleAccentInLight) NuvioPrimitives.white else palette.onSecondaryVariant

    val TextPrimary = if (light) lightTextPrimary else NuvioPrimitives.white
    val TextSecondary = if (light) lightTextSecondary else NuvioPrimitives.neutral400
    val TextTertiary = if (light) lightTextTertiary else NuvioPrimitives.neutral600
    val TextDisabled = if (light) NuvioPrimitives.neutral400 else NuvioPrimitives.neutral700
    val TextInverse = if (light) NuvioPrimitives.white else NuvioPrimitives.neutral925

    val FocusRing = if (light) lightFocusRing else palette.focusRing
    val FocusBackground = if (light) lightFocusRing.copy(alpha = 0.14f) else palette.focusBackground
    val FocusContent = if (light) lightTextPrimary else NuvioPrimitives.white
    val FocusScrim = NuvioPrimitives.black.copy(alpha = 0.32f)

    val Rating = NuvioPrimitives.rating
    val Error = NuvioPrimitives.error
    val Warning = NuvioPrimitives.warning
    val Success = NuvioPrimitives.success
    val Info = NuvioPrimitives.info
    val Watched = NuvioPrimitives.success
    val Unwatched = NuvioPrimitives.neutral600
    val Cached = NuvioPrimitives.blue300
    val Torrent = NuvioPrimitives.torrent
    val Premium = NuvioPrimitives.premium

    val Border = if (light) lightDivider else NuvioPrimitives.neutral750
    val BorderFocused = FocusRing
    val BorderMuted = (if (light) lightDivider else NuvioPrimitives.neutral750).copy(alpha = 0.58f)

    val Scrim = NuvioPrimitives.black.copy(alpha = 0.62f)
    val ImageScrim = NuvioPrimitives.black.copy(alpha = 0.58f)
    val VideoControlsScrim = NuvioPrimitives.black.copy(alpha = 0.72f)
    val PosterFallback = BackgroundCard

    val DisabledContainer = SurfaceVariant.copy(alpha = 0.42f)
    val DisabledContent = TextDisabled
    val DisabledBorder = Border.copy(alpha = 0.48f)
    val DisabledOverlay = NuvioPrimitives.black.copy(alpha = 0.42f)

    val surfaces = NuvioSurfaceColors(
        background = Background,
        raised = BackgroundElevated,
        card = BackgroundCard,
        default = Surface,
        variant = SurfaceVariant,
        panel = Panel,
        overlay = Overlay,
        field = Field,
        menu = Menu,
        modal = Modal,
        playerOverlay = PlayerOverlay,
        divider = Divider
    )

    val text = NuvioTextColors(
        primary = TextPrimary,
        secondary = TextSecondary,
        tertiary = TextTertiary,
        disabled = TextDisabled,
        inverse = TextInverse,
        onAccent = OnSecondary,
        onOverlay = NuvioPrimitives.white,
        metadata = TextSecondary
    )

    val focus = NuvioFocusColors(
        ring = FocusRing,
        background = FocusBackground,
        content = FocusContent,
        accent = Secondary,
        scrim = FocusScrim
    )

    val selection = NuvioSelectionColors(
        background = Secondary,
        foreground = OnSecondary,
        border = SecondaryVariant,
        mutedBackground = FocusBackground,
        mutedForeground = TextPrimary
    )

    val media = NuvioMediaColors(
        heroScrim = Scrim,
        imageScrim = ImageScrim,
        posterFallback = PosterFallback,
        videoControlsScrim = VideoControlsScrim,
        glassPanelTop = Color(0xD64A4F59),
        glassPanelMiddle = Color(0xCC3F454F),
        glassPanelBottom = Color(0xC640474F),
        glow = FocusRing.copy(alpha = 0.32f)
    )

    val status = NuvioStatusColors(
        rating = Rating,
        error = Error,
        warning = Warning,
        success = Success,
        info = Info,
        watched = Watched,
        unwatched = Unwatched,
        cached = Cached,
        torrent = Torrent,
        premium = Premium
    )

    val disabled = NuvioDisabledColors(
        container = DisabledContainer,
        content = DisabledContent,
        border = DisabledBorder,
        overlay = DisabledOverlay
    )

    val source = NuvioSourceColors(
        trakt = NuvioPrimitives.trakt,
        tmdb = NuvioPrimitives.tmdb,
        imdb = NuvioPrimitives.imdb,
        mdblist = NuvioPrimitives.mdblist
    )

    val contrastPairs = listOf(
        NuvioContrastPair(TextPrimary, Background),
        NuvioContrastPair(TextPrimary, BackgroundCard),
        NuvioContrastPair(TextSecondary, Background),
        NuvioContrastPair(OnSecondary, Secondary),
        NuvioContrastPair(FocusContent, FocusBackground),
        NuvioContrastPair(NuvioPrimitives.white, PlayerOverlay)
    )
}

object NuvioColors {
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundCard

    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SurfaceVariant

    val Primary = NuvioPrimitives.neutral500
    val PrimaryVariant = NuvioPrimitives.neutral650
    val OnPrimary = NuvioPrimitives.white
    val TextPrimary = NuvioPrimitives.white
    val TextSecondary = NuvioPrimitives.neutral400
    val TextTertiary = NuvioPrimitives.neutral600
    val TextDisabled = NuvioPrimitives.neutral700
    val Rating = NuvioPrimitives.rating
    val Error = NuvioPrimitives.error
    val Success = NuvioPrimitives.success
    val Warning = NuvioPrimitives.warning
    val Info = NuvioPrimitives.info
    val Border = NuvioPrimitives.neutral750

    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BorderFocused
}
