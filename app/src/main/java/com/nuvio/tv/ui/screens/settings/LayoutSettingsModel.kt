package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout

internal enum class LayoutSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val icon: ImageVector
) {
    /** Basic mode shows only this reduced entry: the home layout picker. */
    ESSENTIAL_HOME(R.string.layout_section_home, R.string.layout_section_home_desc, Icons.Default.Dashboard),
    HOME(R.string.layout_section_home_group, R.string.layout_section_home_group_desc, Icons.AutoMirrored.Filled.ViewSidebar),
    CARDS(R.string.layout_section_cards, R.string.layout_section_cards_desc, Icons.Default.PhotoLibrary),
    CONTINUE_WATCHING(R.string.layout_section_continue_watching, R.string.layout_section_continue_watching_desc, Icons.Default.History),
    DETAIL_PAGE(R.string.layout_section_detail, R.string.layout_section_detail_desc, Icons.Default.Description),
    EPISODES(R.string.layout_section_episodes, R.string.layout_section_episodes_desc, Icons.AutoMirrored.Filled.ViewList)
}

internal fun visibleLayoutSections(essentialMode: Boolean): List<LayoutSection> =
    if (essentialMode) {
        listOf(LayoutSection.ESSENTIAL_HOME)
    } else {
        LayoutSection.entries.filter { it != LayoutSection.ESSENTIAL_HOME }
    }

internal fun defaultExpandedSections(essentialMode: Boolean): List<String> =
    listOf(
        if (essentialMode) LayoutSection.ESSENTIAL_HOME.name else LayoutSection.HOME.name
    )

/**
 * Value shown at the right of a collapsed section, so the current configuration is readable
 * without expanding every block.
 */
@Composable
internal fun layoutSectionSummary(
    section: LayoutSection,
    uiState: LayoutSettingsUiState,
    streamBadgeUiState: StreamBadgeSettingsUiState
): String? = when (section) {
    LayoutSection.ESSENTIAL_HOME,
    LayoutSection.HOME -> homeSummary(uiState)
    LayoutSection.CARDS -> stringResource(
        R.string.layout_summary_cards,
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    )
    LayoutSection.CONTINUE_WATCHING -> if (uiState.continueWatchingEnabled) {
        continueWatchingStyleLabel(uiState.continueWatchingCardStyle)
    } else {
        stringResource(R.string.layout_summary_disabled)
    }
    LayoutSection.DETAIL_PAGE -> stringResource(
        if (uiState.detailPageTrailerAutoplayEnabled) {
            R.string.layout_summary_detail_on
        } else {
            R.string.layout_summary_detail_off
        }
    )
    LayoutSection.EPISODES -> episodesSummary(streamBadgeUiState)
}

@Composable
private fun homeSummary(uiState: LayoutSettingsUiState): String {
    val layoutLabel = stringResource(
        when (uiState.selectedLayout) {
            HomeLayout.MODERN -> R.string.layout_modern
            HomeLayout.GRID -> R.string.layout_grid
            HomeLayout.CLASSIC -> R.string.layout_classic
        }
    )
    if (uiState.selectedLayout != HomeLayout.MODERN) return layoutLabel
    val backdropLabel = stringResource(
        if (uiState.modernHeroFullScreenBackdropEnabled) {
            R.string.layout_summary_fullscreen
        } else {
            R.string.layout_summary_standard
        }
    )
    return stringResource(R.string.layout_summary_home, layoutLabel, backdropLabel)
}

@Composable
private fun continueWatchingStyleLabel(style: ContinueWatchingCardStyle): String = stringResource(
    when (style) {
        ContinueWatchingCardStyle.CARD -> R.string.layout_cw_card_style_card
        ContinueWatchingCardStyle.WIDE -> R.string.layout_cw_card_style_wide
        ContinueWatchingCardStyle.POSTER -> R.string.layout_cw_card_style_poster
    }
)

@Composable
private fun episodesSummary(streamBadgeUiState: StreamBadgeSettingsUiState): String {
    val imports = streamBadgeUiState.rules.normalized().imports.size
    return if (imports > 0) {
        stringResource(R.string.layout_summary_badges, imports)
    } else {
        stringResource(R.string.layout_summary_badges_empty)
    }
}

internal object LayoutSettingsTestTags {
    fun section(section: LayoutSection): String = "layout_section_${section.name.lowercase()}"
}
