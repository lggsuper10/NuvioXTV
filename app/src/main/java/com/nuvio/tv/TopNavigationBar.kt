package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.BrandLockup
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter

internal val TopNavBarHeight = 64.dp
internal val TopNavOuterTopMargin = 20.dp

/** Vertical space root screens keep clear so their own headers never sit under the bar. */
internal val TopNavContentInset = TopNavBarHeight + TopNavOuterTopMargin + 8.dp

private val TopNavHorizontalPadding = 48.dp
private val TopNavLogoWidth = 132.dp
private val TopNavLogoHeight = 34.dp
private val TopNavItemIconSize = 22.dp
private val TopNavItemGap = 10.dp
private val TopNavItemRadius = 10.dp
private val TopNavProfileSize = 48.dp

/**
 * Navigation bar used when [com.nuvio.tv.domain.model.NavBarPosition.TOP] is selected.
 *
 * Deliberately surface-less: the branding and destinations float directly over the artwork,
 * matching the app's full-bleed hero treatment. The selected destination gets the same filled
 * accent pill the modern side panel uses.
 */
@Composable
internal fun TopNavigationBar(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    visible: Boolean,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    onBarFocusChanged: (Boolean) -> Unit = {},
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "topNavigationAlpha"
    )
    var focusedNavItemCount by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(visible) {
        if (!visible) {
            focusedNavItemCount = 0
            onBarFocusChanged(false)
        }
    }

    if (alpha <= 0.001f) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = -(1f - alpha) * 18f
            }
            .padding(
                start = TopNavHorizontalPadding,
                end = TopNavHorizontalPadding,
                top = TopNavOuterTopMargin
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TopNavBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                drawerItems.forEachIndexed { index, item ->
                    TopNavItem(
                        label = item.label,
                        iconRes = item.iconRes,
                        icon = item.icon,
                        selected = selectedDrawerRoute == item.route,
                        onFocusChanged = { focused ->
                            focusedNavItemCount =
                                (focusedNavItemCount + if (focused) 1 else -1).coerceAtLeast(0)
                            onBarFocusChanged(focusedNavItemCount > 0)
                            if (focused) onDrawerItemFocused(index)
                        },
                        onClick = { onDrawerItemClick(item.route) },
                        modifier = Modifier
                            .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                    )
                }
            }

            if (showProfileSelector && activeProfileName.isNotEmpty()) {
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                TopBarProfileItem(
                    profileName = activeProfileName,
                    profileColorHex = activeProfileColorHex,
                    profileAvatarImageUrl = activeProfileAvatarImageUrl,
                    onClick = onSwitchProfile
                )
            }

            // 右上角品牌标识: Logo + 非看 Fplayer
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            BrandLockup(
                badgeSize = 34.dp,
                nameStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                ),
                gap = NuvioTheme.spacing.sm
            )
        }
    }
}

@Composable
private fun TopNavItem(
    label: String,
    iconRes: Int?,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    selected: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(TopNavItemRadius)

    // Only actual D-pad focus gets the filled treatment. The current destination stays
    // legible with an accent tint so it never looks like it still holds focus.
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) colors.Secondary else Color.Transparent,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "topNavItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> colors.OnSecondary
            selected -> colors.Secondary
            else -> colors.TextPrimary
        },
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "topNavItemContent"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    Color.Transparent
                ),
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
                .padding(
                    horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs,
                    vertical = NuvioTheme.spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(TopNavItemIconSize)
                )
                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(TopNavItemIconSize)
                )
            }
            Spacer(modifier = Modifier.width(TopNavItemGap))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TopBarProfileItem(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = CircleShape

    Card(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.hasFocus }
            .semantics {
                contentDescription = profileName
                role = Role.Button
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    if (isFocused) colors.FocusRing else Color.Transparent
                ),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .size(TopNavProfileSize)
                .padding(NuvioTheme.spacing.xxs),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = TopNavProfileSize - NuvioTheme.spacing.sm,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
        }
    }
}
