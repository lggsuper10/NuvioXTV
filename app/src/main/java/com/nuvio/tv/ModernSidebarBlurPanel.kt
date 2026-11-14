package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.BrandLockup
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect

private val SidebarLeadingVisualSize = NuvioComponents.tokens.sidebar.leadingVisual
private val SidebarContentGap = NuvioComponents.tokens.sidebar.contentGap
private val SidebarProfileContentGap = NuvioComponents.tokens.sidebar.contentGap + NuvioTheme.spacing.xs

/** Shared navigation surface fill so the side panel and the top bar never drift apart. */
@Composable
internal fun rememberNavPanelBackgroundBrush(blurEnabled: Boolean): Brush {
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val isLight = colors.isLight
    return remember(blurEnabled, isLight, bgElevated, bgCard) {
        val alpha = when {
            blurEnabled && isLight -> 0.92f
            blurEnabled -> 0.65f
            isLight -> 0.99f
            else -> 0.97f
        }
        Brush.verticalGradient(listOf(
            bgElevated.copy(alpha = alpha),
            bgCard.copy(alpha = alpha)
        ))
    }
}

@Composable
internal fun rememberNavPanelBorderColor(blurEnabled: Boolean): Color {
    val colors = NuvioTheme.colors
    val borderBase = colors.Border
    val isLight = colors.isLight
    val isAmoledBlack = colors.BackgroundElevated == Color.Black
    return remember(isAmoledBlack, isLight, blurEnabled, borderBase) {
        when {
            isLight -> borderBase.copy(alpha = 0.82f)
            !blurEnabled && isAmoledBlack -> borderBase.copy(alpha = 0.9f)
            else -> borderBase.copy(alpha = 0.45f)
        }
    }
}

@Composable
internal fun ModernSidebarBlurPanel(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    keepSidebarFocusDuringCollapse: Boolean,
    sidebarLabelAlpha: Float,
    sidebarIconScale: Float,
    sidebarExpandProgress: Float,
    isSidebarExpanded: Boolean,
    sidebarCollapsePending: Boolean,
    blurEnabled: Boolean,
    sidebarHazeState: HazeState,
    panelShape: RoundedCornerShape,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit
) {
    val delayedBlurProgress =
        ((sidebarExpandProgress - 0.34f) / 0.66f).coerceIn(0f, 1f)
    val showPanelBlur = blurEnabled &&
        isSidebarExpanded &&
        !sidebarCollapsePending &&
        delayedBlurProgress > 0f
    val expandedPanelBlurModifier = if (showPanelBlur) {
        Modifier.hazeEffect(state = sidebarHazeState) {
            blurRadius = NuvioTheme.effects.blurPanel * delayedBlurProgress
            noiseFactor = 0.04f * delayedBlurProgress
            inputScale = HazeInputScale.Fixed(0.66f)
        }
    } else {
        Modifier
    }
    val panelBackgroundBrush = rememberNavPanelBackgroundBrush(blurEnabled)
    val panelBorderColor = rememberNavPanelBorderColor(blurEnabled)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .clip(panelShape)
            .then(expandedPanelBlurModifier)
            .background(brush = panelBackgroundBrush, shape = panelShape)
            .then(
                if (panelBorderColor != Color.Transparent) {
                    Modifier.border(width = NuvioStrokes.tokens.hairline, color = panelBorderColor, shape = panelShape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs)
    ) {
        // 顶部品牌: Logo + 应用名(跟随系统语言: 中文=非看, 英文=Fplayer)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = NuvioTheme.spacing.md),
            contentAlignment = Alignment.Center
        ) {
            BrandLockup(
                modifier = Modifier.fillMaxWidth(0.92f),
                badgeSize = 36.dp
            )
        }

        if (showProfileSelector && activeProfileName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SidebarProfileItem(
                    profileName = activeProfileName,
                    profileColorHex = activeProfileColorHex,
                    profileAvatarImageUrl = activeProfileAvatarImageUrl,
                    focusEnabled = keepSidebarFocusDuringCollapse,
                    labelAlpha = sidebarLabelAlpha,
                    onFocusChanged = { focused ->
                        if (focused) onDrawerItemFocused(drawerItems.size)
                    },
                    onClick = onSwitchProfile,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.offset(y = (-12).dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm - NuvioTheme.spacing.xxs)
            ) {
                drawerItems.forEachIndexed { index, item ->
                    key(item.route) {
                        SidebarNavigationItem(
                            label = item.label,
                            iconRes = item.iconRes,
                            icon = item.icon,
                            selected = selectedDrawerRoute == item.route,
                            focusEnabled = keepSidebarFocusDuringCollapse,
                            labelAlpha = sidebarLabelAlpha,
                            iconScale = sidebarIconScale,
                            onFocusChanged = {
                                if (it) {
                                    onDrawerItemFocused(index)
                                }
                            },
                            onClick = { onDrawerItemClick(item.route) },
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SidebarNavigationItem(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusEnabled: Boolean,
    labelAlpha: Float,
    iconScale: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val backgroundColorTarget = if (isFocused) colors.Secondary else Color.Transparent
    val animatedBackgroundColor by animateColorAsState(
        targetValue = backgroundColorTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemBackground"
    )
    val backgroundColor = if (isFocused) backgroundColorTarget else animatedBackgroundColor

    val contentColorTarget = when {
        isFocused -> colors.OnSecondary
        selected -> colors.Secondary
        else -> colors.TextPrimary
    }
    val animatedContentColor by animateColorAsState(
        targetValue = contentColorTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemContent"
    )
    val contentColor = if (isFocused) contentColorTarget else animatedContentColor

    val iconTintTarget = when {
        isFocused -> colors.OnSecondary
        selected -> colors.Secondary
        else -> colors.TextPrimary
    }
    val animatedIconTint by animateColorAsState(
        targetValue = iconTintTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemIconTint"
    )
    val iconTint = if (isFocused) iconTintTarget else animatedIconTint
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
        label = "sidebarItemScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, Color.Transparent),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .semantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selected
                }
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .size(SidebarLeadingVisualSize)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            contentAlignment = Alignment.Center
        ) {
            val iconModifier = Modifier.size(NuvioComponents.tokens.sidebar.iconSize)
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = iconModifier
                )
                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = iconModifier
                )
            }
        }
        Spacer(modifier = Modifier.width(SidebarContentGap))

        AutoResizeText(
            text = label,
            color = contentColor,
            modifier = Modifier
                .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                .graphicsLayer { alpha = labelAlpha }
        )
        }
    }
}

@Composable
private fun SidebarProfileItem(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    focusEnabled: Boolean,
    labelAlpha: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val backgroundColor = if (isFocused) colors.Secondary else Color.Transparent
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, Color.Transparent),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .semantics {
                    contentDescription = profileName
                    role = Role.Button
                }
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier.size(SidebarLeadingVisualSize),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = SidebarLeadingVisualSize,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
        }
        Spacer(modifier = Modifier.width(SidebarProfileContentGap))
        AutoResizeText(
            text = profileName,
            color = if (isFocused) colors.OnSecondary else colors.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                textDirection = profileName.contentTextDirection()
            )
        )
        }
    }
}
