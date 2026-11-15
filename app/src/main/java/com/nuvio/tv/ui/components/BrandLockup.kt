package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The app icon, presented as a deliberate circular badge.
 *
 * [R.drawable.app_logo_mark] is a white disc with a transparent margin; it reads as an icon on
 * dark backgrounds, but on the light navigation panel the disc blends into the panel and the
 * glyph ends up looking like a cut-out floating on white. Clipping it to a circle, scaling the
 * art up past its transparent margin and, on light backgrounds, adding a hairline ring gives it
 * an edge again so it reads as an icon everywhere.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppLogoBadge(
    size: Dp,
    modifier: Modifier = Modifier
) {
    val isLight = NuvioTheme.colors.isLight
    Box(
        modifier = modifier
            .size(size)
            .then(if (isLight) Modifier.shadow(elevation = 4.dp, shape = CircleShape) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White)
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo_mark),
                contentDescription = null,
                // 1.06 pushes the art's own ~4% transparent margin under the clip edge.
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.06f)
            )
        }
        if (isLight) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = NuvioStrokes.tokens.hairline,
                        color = NuvioTheme.colors.BorderMuted,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Brand lockup used in the navigation surfaces: icon badge plus the localised app name, with a
 * small tracked Latin tagline underneath for the Chinese brand.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    badgeSize: Dp = 36.dp,
    nameStyle: TextStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp
    ),
    nameColor: Color = NuvioTheme.colors.TextPrimary,
    showTagline: Boolean = true,
    gap: Dp = NuvioTheme.spacing.md
) {
    val name = stringResource(R.string.app_name)
    val tagline = stringResource(R.string.app_brand_tagline)
    // Latin tagline only earns its place under a CJK name; in English it would repeat the name.
    val taglineVisible = showTagline && name.any { it.code > 0x2E80 }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLogoBadge(size = badgeSize)
        Column(
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.hairline)
        ) {
            Text(
                text = name,
                style = nameStyle,
                color = nameColor,
                maxLines = 1
            )
            if (taglineVisible) {
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.8.sp
                    ),
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}
