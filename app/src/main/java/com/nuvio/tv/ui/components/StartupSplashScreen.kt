package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives

@Composable
fun StartupSplashScreen(
    profileBackgroundUrl: String?,
    backgroundCacheKey: String? = null,
    skipGradient: Boolean = false,
    modifier: Modifier = Modifier
) {
    val baseBg = colorResource(R.color.splash_background)
    val brandGreen = colorResource(R.color.splash_brand_green)
    val brandGreenHighlight = colorResource(R.color.splash_brand_green_highlight)

    Box(modifier = modifier.fillMaxSize().background(baseBg)) {
        if (!profileBackgroundUrl.isNullOrBlank()) {
            val imageData: Any = if (profileBackgroundUrl.startsWith("file:")) {
                java.io.File(java.net.URI(profileBackgroundUrl))
            } else {
                profileBackgroundUrl
            }
            val request = ImageRequest.Builder(LocalContext.current)
                .data(imageData)
                .crossfade(false)
            if (backgroundCacheKey != null) {
                request.memoryCacheKey(backgroundCacheKey)
                    .diskCacheKey(backgroundCacheKey)
                    .placeholderMemoryCacheKey(backgroundCacheKey)
            }
            AsyncImage(
                model = request.build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!skipGradient) {
            // The launch surface stays brand-owned; profile colors return after startup.
            val baseBgElevated = NuvioPrimitives.neutral875
            val gradientTop = lerp(baseBgElevated, brandGreen, 0.24f)
            val gradientMid = lerp(baseBg, brandGreen, 0.12f)
            val halfFadeStrong = brandGreenHighlight.copy(alpha = 0.16f)
            val halfFadeSoft = brandGreen.copy(alpha = 0.06f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to gradientTop,
                                0.42f to gradientMid,
                                1f to baseBg
                            )
                        )
                    )
                    .background(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to halfFadeStrong,
                                0.45f to halfFadeSoft,
                                0.72f to Color.Transparent,
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo_mark),
                contentDescription = stringResource(R.string.cd_nuvio_logo),
                modifier = Modifier.size(180.dp),
            )
        }
    }
}
